/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One IntelliJ-family IDE detected by an active probe of the local
 * machine's TCP ports — not via the `.mcp-steroid` JSON marker.
 *
 * `productFullName` is the unrestricted `name` field returned by
 * `/api/about`; `productName` is the short product slug
 * (`IDEA`, `PyCharm`, `GoLand`, …). `buildNumber` is absent on snapshot
 * builds.
 */
data class DiscoveredIdeByPort(
    val port: Int,
    val baseUrl: String,
    val productName: String?,
    val productFullName: String?,
    val edition: String?,
    val baselineVersion: Int?,
    val buildNumber: String?,
) {
    /** Human-friendly identifier used in logs (`IntelliJ IDEA Ultimate :63342`). */
    val label: String
        get() = (productFullName ?: productName ?: "ide") + " :$port"
}

/**
 * Active discovery of IntelliJ-family IDEs running on `127.0.0.1` by
 * probing common HTTP-server ports.
 *
 * Complements [IdeDiscoveryService] (which discovers `mcp-steroid`-aware
 * IDEs via their managed PID marker). Port-based discovery finds *any*
 * JetBrains IDE — even ones without the
 * `mcp-steroid` plugin installed.
 *
 * Scope of the scan: by default 63342..63361 (the IntelliJ Platform's
 * Netty built-in server picks the first free port starting at 63342
 * with up to 19 fallback ports) and 64342..64361 (the bundled MCP
 * Server plugin uses the same 20-port fallback scheme on top of
 * `DEFAULT_MCP_PORT = 64342`).
 *
 * **Parallelism is bounded by a dedicated daemon-thread pool**
 * (`mcp-steroid-port-scan-<n>`). Probes run on that pool, never on the
 * coroutine dispatcher driving the rest of the process, so a single
 * slow TCP connect cannot stall the stdio MCP server or marker
 * discovery.
 *
 * The service is [Closeable] — call [close] from the wiring root to
 * drain the executor on shutdown. (Threads are daemon, so a forgotten
 * close won't prevent JVM exit.)
 */
class IntelliJPortDiscovery(
    private val httpClient: HttpClient,
    private val portRanges: List<IntRange> = DEFAULT_PORT_RANGES,
    private val scanInterval: Duration = 30.seconds,
    private val probeTimeout: Duration = 1500.milliseconds,
    parallelism: Int = 8,
) : Closeable {
    private val log = LoggerFactory.getLogger(IntelliJPortDiscovery::class.java)
    private val threadCounter = AtomicInteger(0)

    private val executor: ExecutorService = Executors.newFixedThreadPool(parallelism) { runnable ->
        Thread(runnable, "mcp-steroid-port-scan-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val scanDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val _detected = MutableStateFlow<Set<DiscoveredIdeByPort>>(emptySet())
    val detected: StateFlow<Set<DiscoveredIdeByPort>> = _detected.asStateFlow()

    /**
     * Launch the periodic scan loop on [scope]. The returned [Job] is
     * cancellable; when it cancels, the dedicated thread pool is drained
     * on a non-cancellable finally so in-flight probes don't leak.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            scanOnce()
            while (isActive) {
                delay(scanInterval)
                scanOnce()
            }
        } finally {
            withContext(NonCancellable) { shutdownExecutor() }
        }
    }

    /**
     * One scan pass. Probes every port in [portRanges] concurrently on
     * the dedicated dispatcher, replaces [detected] with the resulting
     * set. Exposed for tests + for forced refresh after a known event
     * (e.g. an IDE just started).
     */
    suspend fun scanOnce() {
        val ports = portRanges.flatMap { it.toList() }.toSortedSet()
        val results: Set<DiscoveredIdeByPort> = coroutineScope {
            ports.map { port ->
                async(scanDispatcher) { probePort(port) }
            }.awaitAll().filterNotNull().toSet()
        }
        _detected.value = results
    }

    /**
     * Probe a single port. Returns `null` for every non-IDE response
     * (connection refused, non-2xx, non-JSON body, response that
     * doesn't look like an IDE's `/api/about`, or any other failure).
     * Never propagates an exception — a port that isn't an IDE is a
     * normal outcome, not an error.
     */
    private suspend fun probePort(port: Int): DiscoveredIdeByPort? = try {
        withTimeout(probeTimeout) {
            val response = httpClient.get("http://127.0.0.1:$port/api/about") {
                accept(ContentType.Application.Json)
            }
            if (!response.status.isSuccess()) return@withTimeout null
            val body = response.bodyAsText()
            val about = try {
                aboutJson.decodeFromString(AboutResponse.serializer(), body)
            } catch (e: Exception) {
                log.debug("Port {} did not return /api/about JSON: {}", port, e.message)
                return@withTimeout null
            }
            // Accept only responses that carry at least one IDE-identifying
            // field — a stray JSON 200 from a different service shouldn't
            // get classified as an IntelliJ.
            if (about.productName == null && about.name == null) return@withTimeout null
            DiscoveredIdeByPort(
                port = port,
                baseUrl = "http://127.0.0.1:$port",
                productName = about.productName,
                productFullName = about.name,
                edition = about.edition,
                baselineVersion = about.baselineVersion,
                buildNumber = about.buildNumber,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override fun close() {
        shutdownExecutor()
    }

    private fun shutdownExecutor() {
        if (executor.isShutdown) return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /**
         * Default scan ranges. Tracks both built-in HTTP server's
         * fallback range and the bundled MCP Server plugin's fallback
         * range — both pick the first free port in a 20-port window
         * above their default.
         */
        val DEFAULT_PORT_RANGES: List<IntRange> = listOf(
            63342..63361,
            64342..64361,
        )
    }
}

@Serializable
data class AboutResponse(
    val name: String? = null,
    val productName: String? = null,
    val edition: String? = null,
    val baselineVersion: Int? = null,
    val buildNumber: String? = null,
)

val aboutJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
