/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import com.jonnyzzz.mcpSteroid.server.NPX_PROJECTS_STREAM_PATH
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import com.jonnyzzz.mcpSteroid.server.NpxStreamEnvelope
import com.jonnyzzz.mcpSteroid.server.NpxStreamJson
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Liveness state of a monitored IDE connection. */
enum class IdeMonitorStatus { CONNECTING, CONNECTED, RECONNECTING }

/**
 * Per-IDE monitoring state held by [IdeMonitorService.states].
 * Re-emitted on every status / snapshot change.
 */
data class IdeMonitorState(
    val ide: DiscoveredIde,
    val status: IdeMonitorStatus,
    val lastSnapshot: List<ProjectInfo> = emptyList(),
    /** ISO-8601 timestamp of the most recent envelope (snapshot OR ping). */
    val lastSeenAt: String? = null,
    /** Server-reported instance id from the first received envelope. */
    val ideInstanceId: String? = null,
    val errorMessage: String? = null,
)

/**
 * Subscribes to every discovered IDE's `/npx/v1/projects/stream`, holds the
 * latest snapshot per IDE, and reconnects on connection break.
 *
 * Wiring:
 *  - [discovery] is consumed via [IdeDiscoveryService.ides]; the orchestrator
 *    coroutine reacts to add/remove deltas.
 *  - For each newly-discovered IDE, a child coroutine is launched on
 *    [scope] that POSTs [clientInfo] and reads NDJSON envelopes.
 *  - When an IDE is removed from the discovery set, its child coroutine is
 *    cancelled (its connection drops).
 *  - On any connection error, the child loops with [reconnectBackoff].
 *  - State is exposed via [states] keyed by pid.
 */
class IdeMonitorService(
    private val httpClient: HttpClient,
    private val discovery: IdeDiscoveryService,
    private val clientInfo: NpxStreamClientInfo,
    private val streamPath: String = NPX_PROJECTS_STREAM_PATH,
    private val reconnectBackoff: Duration = 2.seconds,
) {
    private val log = LoggerFactory.getLogger(IdeMonitorService::class.java)

    private val _states = MutableStateFlow<Map<Long, IdeMonitorState>>(emptyMap())
    val states: StateFlow<Map<Long, IdeMonitorState>> = _states.asStateFlow()

    fun start(scope: CoroutineScope): Job {
        val workers: MutableMap<Long, Job> = mutableMapOf()
        return scope.launch {
            try {
                discovery.ides.collect { discovered ->
                    val live = discovered.associateBy { it.pid }

                    // Cancel workers whose IDEs are no longer discovered.
                    val gone = workers.keys - live.keys
                    for (pid in gone) {
                        workers.remove(pid)?.cancelAndJoin()
                        updateState(pid) { null }  // drop from states map
                    }

                    // Spawn a worker for each newly-discovered IDE.
                    for ((pid, ide) in live) {
                        if (workers.containsKey(pid)) continue
                        updateState(pid) { IdeMonitorState(ide = ide, status = IdeMonitorStatus.CONNECTING) }
                        workers[pid] = launch { runConnectionLoop(ide) }
                    }
                }
            } finally {
                workers.values.forEach { it.cancel() }
            }
        }
    }

    /**
     * Open one connection to [ide], read until it drops, repeat with [reconnectBackoff].
     * Loop exits when the calling coroutine is cancelled (either on shutdown or
     * because the IDE left the discovery set).
     */
    private suspend fun runConnectionLoop(ide: DiscoveredIde) {
        while (true) {
            try {
                connectAndStream(ide)
                // Stream ended cleanly (server closed). Treat as a transient
                // disconnect and reconnect after backoff.
                updateState(ide.pid) { (it ?: IdeMonitorState(ide, IdeMonitorStatus.RECONNECTING)).copy(
                    status = IdeMonitorStatus.RECONNECTING,
                    errorMessage = "stream closed by server"
                )}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Connection to ${ide.label} failed: ${e.message}")
                updateState(ide.pid) { (it ?: IdeMonitorState(ide, IdeMonitorStatus.RECONNECTING)).copy(
                    status = IdeMonitorStatus.RECONNECTING,
                    errorMessage = e.message,
                )}
            }
            delay(reconnectBackoff)
        }
    }

    /** Single-attempt connect + drain. Returns when the stream ends or throws on error. */
    private suspend fun connectAndStream(ide: DiscoveredIde) {
        val url = streamUrlFor(ide.mcpUrl, streamPath)
        val body = NpxStreamJson.encodeClientInfo(clientInfo)

        httpClient.preparePost(url) {
            headers {
                append(HttpHeaders.ContentType, "application/json")
                for ((name, value) in ide.marker.mcpSteroidServer.headers) {
                    append(name, value)
                }
            }
            setBody(body)
        }.execute { response ->
            if (!response.status.value.let { it in 200..299 }) {
                throw IllegalStateException("HTTP ${response.status.value} from ${ide.label}")
            }
            updateState(ide.pid) { (it ?: IdeMonitorState(ide, IdeMonitorStatus.CONNECTED)).copy(
                status = IdeMonitorStatus.CONNECTED,
                errorMessage = null,
            )}
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val env = try {
                    NpxStreamJson.decodeEnvelope(line)
                } catch (e: Exception) {
                    log.warn("Skipping malformed envelope from ${ide.label}: ${e.message}")
                    continue
                }
                applyEnvelope(ide, env)
            }
        }
    }

    private fun applyEnvelope(ide: DiscoveredIde, env: NpxStreamEnvelope) {
        val now = nowIso()
        updateState(ide.pid) { current ->
            val base = current ?: IdeMonitorState(ide = ide, status = IdeMonitorStatus.CONNECTED)
            base.copy(
                ide = ide,
                status = IdeMonitorStatus.CONNECTED,
                lastSeenAt = now,
                ideInstanceId = base.ideInstanceId ?: env.instanceId,
                lastSnapshot = if (env.type == "snapshot") env.projects ?: emptyList() else base.lastSnapshot,
                errorMessage = null,
            )
        }
    }

    /**
     * Atomically update a single pid's state. The mutator returns the new
     * value, or `null` to remove the entry.
     */
    private fun updateState(pid: Long, mutator: (IdeMonitorState?) -> IdeMonitorState?) {
        _states.update { current ->
            val next = mutator(current[pid])
            if (next == null) current - pid
            else current + (pid to next)
        }
    }
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return
    }
}

private fun streamUrlFor(mcpUrl: String, streamPath: String): String {
    // mcpUrl is the IDE's "http://host:port/mcp". The /npx/v1/* routes are siblings
    // — strip the trailing /mcp (and any trailing slash) before appending streamPath.
    val base = mcpUrl.trimEnd('/').removeSuffix("/mcp")
    return base + streamPath
}

private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
