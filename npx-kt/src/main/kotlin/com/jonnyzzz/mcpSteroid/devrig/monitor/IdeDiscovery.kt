/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One IDE instance discovered through a `~/.mcp-steroid/markers/<pid>.mcp-steroid` JSON marker.
 * Equality is keyed off the marker contents that affect the connection
 * (pid + mcpUrl) so [IdeDiscoveryService] can compare snapshots cheaply.
 */
data class DiscoveredIde(
    val pid: Long,
    /**
     * Full base URL of the IDE's devrig bridge, read verbatim from the marker's
     * [PidMarker.devrigEndpoint] (`rpcBaseUrl`). devrig appends only operation suffixes
     * (`/tools/call/stream`, `/projects/stream`, `/windows`) — it never derives this from the MCP URL
     * and never touches the `/mcp` MCP-client endpoint.
     */
    val rpcBaseUrl: String,
    /** Headers the bridge requires on every request, from [PidMarker.devrigEndpoint] (auth-scheme-agnostic). */
    val bridgeHeaders: Map<String, String>,
    /** Filename of the marker, useful when logging which file we picked up. */
    val markerPath: String,
    /** The full decoded marker — kept around for the IDE's [PidMarker.ide] / [PidMarker.plugin] metadata. */
    val marker: PidMarker,
) {
    /** Stable, human-friendly identifier used in logs (`IntelliJ IDEA pid=12345`). */
    val label: String
        get() = "${marker.ide.name} pid=$pid"
}

/**
 * Polling discovery layer for `~/.mcp-steroid/markers/<pid>.mcp-steroid` JSON markers.
 *
 * Exposes a typed [DiscoveredIde] flow and is the single source of truth for
 * the devrig monitor stack.
 *
 * Behaviour:
 *  - Scans [markersDir] every [scanInterval] and emits a fresh value on the
 *    flow whenever the discovered set changes (by [DiscoveredIde] equality).
 *  - Skips markers whose host is not in [allowHosts] — the same allowlist
 *    discipline as the legacy implementation.
 *  - Skips markers whose pid is no longer alive.
 *  - Tolerates malformed JSON: a single corrupt marker is logged and
 *    excluded; the rest of the scan continues.
 */
class IdeDiscoveryService(
    private val markersDir: Path,
    private val allowHosts: List<String>,
    private val scanInterval: Duration = 2.seconds,
) {
    private val log = LoggerFactory.getLogger(IdeDiscoveryService::class.java)

    private val _ides = MutableStateFlow<Set<DiscoveredIde>>(emptySet())
    val ides: StateFlow<Set<DiscoveredIde>> = _ides.asStateFlow()

    fun start(scope: CoroutineScope): Job = scope.launch {
        // Emit an initial snapshot synchronously so consumers don't have to wait
        // a full scanInterval before the first value lands on the flow.
        scanOnce()
        while (isActive) {
            delay(scanInterval)
            scanOnce()
        }
    }

    /** One-shot scan — exposed for test code and for manual refresh after a connection error. */
    fun scanOnce() {
        _ides.value = scanDirectory(markersDir).associateByTo(linkedMapOf()) { it.pid }.values.toSet()
    }

    private fun scanDirectory(dir: Path): List<DiscoveredIde> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = try {
            Files.list(dir).use { it.toList() }
        } catch (e: Exception) {
            log.warn("Failed to list marker directory {}: {}", dir, e.message)
            return emptyList()
        }
        val out = mutableListOf<DiscoveredIde>()
        for (file in files) {
            if (!file.isRegularFile()) continue
            val pid = PidMarker.pidFromFileName(file.name) ?: continue
            if (!ProcessHandle.of(pid).isPresent) continue
            val text = try { file.readText() } catch (e: Exception) {
                log.warn("Failed to read marker file {}: {}", file, e.message)
                continue
            }
            // Cheap legacy-format sniff: a current marker is a JSON object so the first
            // non-whitespace byte is '{'. Skip non-JSON files at DEBUG; only WARN on
            // text that LOOKS like JSON but fails to parse (truncated write, bit-flip, etc.).
            if (text.trimStart().firstOrNull() != '{') {
                log.debug("Skipping non-JSON marker file {}", file)
                continue
            }
            val marker = try {
                PidMarkerJson.decode(text)
            } catch (e: Exception) {
                log.warn("Skipping malformed marker file {}: {}", file, e.message)
                continue
            }
            // devrig speaks ONLY the bridge endpoint advertised by the marker — never the /mcp MCP
            // endpoint. A marker without devrigEndpoint is from an IDE that doesn't expose the bridge
            // (e.g. an older plugin); skip it rather than fall back to the MCP server.
            val devrigEndpoint = marker.devrigEndpoint
            if (devrigEndpoint == null) {
                log.debug("Skipping marker {} — no devrigEndpoint (IDE not devrig-capable)", file)
                continue
            }
            if (!isAllowedHost(devrigEndpoint.rpcBaseUrl)) {
                log.debug("Skipping marker {} — host not in allowlist", file)
                continue
            }
            out += DiscoveredIde(
                pid = pid,
                rpcBaseUrl = devrigEndpoint.rpcBaseUrl,
                bridgeHeaders = devrigEndpoint.headers,
                markerPath = file.toString(),
                marker = marker,
            )
        }
        return out
    }

    private fun isAllowedHost(url: String): Boolean = try {
        val host = URI(url).host ?: return false
        allowHosts.contains(host)
    } catch (e: Exception) {
        false
    }
}
