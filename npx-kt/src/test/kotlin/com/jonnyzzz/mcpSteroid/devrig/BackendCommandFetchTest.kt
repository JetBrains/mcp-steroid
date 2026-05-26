/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.server.NPX_NDJSON_MIME_TYPE
import com.jonnyzzz.mcpSteroid.server.NPX_PROJECTS_STREAM_PATH
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import com.jonnyzzz.mcpSteroid.server.NpxStreamEnvelope
import com.jonnyzzz.mcpSteroid.server.NpxStreamJson
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Wire-level coverage for the `backend` subcommand's fetcher. Spins up an
 * in-process Ktor server that mimics the IDE's `/npx/v1/projects/stream`
 * endpoint, then drives [collectMarkerSnapshots] against it.
 *
 * Pattern lifted from `IdeMonitorServiceTest` — same fake-server shape, but
 * here we want the one-shot semantics: connect, read until the first snapshot,
 * close, render.
 */
class BackendCommandFetchTest {

    private val seq = AtomicLong(0)
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private var port: Int = 0

    /** Envelopes the fake server emits before holding the connection open. */
    private val script = mutableListOf<NpxStreamEnvelope>()
    private val receivedAuthHeaders = mutableListOf<String?>()
    private val receivedClientInfos = mutableListOf<NpxStreamClientInfo>()

    @BeforeEach
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post(NPX_PROJECTS_STREAM_PATH) {
                    receivedAuthHeaders += call.request.headers["Authorization"]
                    val body = call.receiveText()
                    receivedClientInfos += NpxStreamJson.decodeClientInfo(body)
                    call.respondTextWriter(ContentType.parse(NPX_NDJSON_MIME_TYPE)) {
                        for (env in script) {
                            write(NpxStreamJson.encodeEnvelope(env))
                            write("\n")
                            flush()
                        }
                        // Hold the connection open until cancelled — that's what the
                        // real IDE does, and the backend command must close the
                        // connection itself the moment it has the snapshot.
                        while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                            delay(50.milliseconds)
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }

        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 2_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        server.stop(0L, 0L)
    }

    private fun snapshotEnv(projects: List<ProjectInfo>) = NpxStreamEnvelope(
        type = "snapshot",
        seq = seq.incrementAndGet(),
        sentAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
        instanceId = "fake-ide-backend-test",
        pid = 99999L,
        projects = projects,
    )

    private fun pingEnv() = NpxStreamEnvelope(
        type = "ping",
        seq = seq.incrementAndGet(),
        sentAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
        instanceId = "fake-ide-backend-test",
        pid = 99999L,
        projects = null,
    )

    private fun ide(token: String = "deadbeef", overrideUrl: String? = null): DiscoveredIde {
        val mcpUrl = overrideUrl ?: "http://127.0.0.1:$port/mcp"
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = 1234L,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = mcpUrl,
                port = port,
                headers = mapOf("Authorization" to "Bearer $token"),
            ),
            ide = IdeInfo("FakeIDE", "x", "y"),
            plugin = PluginInfo("x", "y", "z"),
            createdAt = "1970-01-01T00:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        return DiscoveredIde(pid = 1234L, mcpUrl = mcpUrl, markerPath = "/tmp/1234.mcp-steroid", marker = marker)
    }

    // ----------------------- single-IDE happy path -------------------------

    @Test
    fun `collects the first snapshot from a responsive IDE`() = runBlocking {
        script += snapshotEnv(listOf(ProjectInfo("alpha", "/p/alpha")))

        val rows = collectMarkerSnapshots(httpClient, listOf(ide()), perIdeTimeout = 5.seconds)

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(listOf(ProjectInfo("alpha", "/p/alpha")), row.projects)
        assertNull(row.errorMessage, "expected no error; got: ${row.errorMessage}")
    }

    @Test
    fun `skips ping envelopes and returns the snapshot that follows`() = runBlocking {
        // Real IDEs interleave pings between snapshots for keepalive. The
        // collector MUST keep draining until it sees `type=snapshot`.
        script += pingEnv()
        script += pingEnv()
        script += snapshotEnv(listOf(ProjectInfo("beta", "/p/beta")))

        val rows = collectMarkerSnapshots(httpClient, listOf(ide()), perIdeTimeout = 5.seconds)
        assertEquals(listOf(ProjectInfo("beta", "/p/beta")), rows.single().projects)
    }

    @Test
    fun `propagates empty projects list as empty, not as null`() = runBlocking {
        // Distinguishing "IDE answered with no projects" from "IDE unreachable"
        // is the renderer's job; the fetcher must preserve that signal.
        script += snapshotEnv(emptyList())

        val row = collectMarkerSnapshots(httpClient, listOf(ide()), perIdeTimeout = 5.seconds).single()
        assertEquals(emptyList<ProjectInfo>(), row.projects, "must distinguish empty from null")
        assertNull(row.errorMessage)
    }

    // ------------------ auth headers + client info round-trip -------------

    @Test
    fun `sends Bearer auth header from marker token`() = runBlocking {
        script += snapshotEnv(emptyList())
        collectMarkerSnapshots(httpClient, listOf(ide(token = "secret-123")), perIdeTimeout = 5.seconds)
        assertTrue(receivedAuthHeaders.any { it == "Bearer secret-123" },
            "expected the fetcher to send the bearer token; saw: $receivedAuthHeaders")
    }

    @Test
    fun `identifies itself in NpxStreamClientInfo`() = runBlocking {
        script += snapshotEnv(emptyList())
        collectMarkerSnapshots(httpClient, listOf(ide()), perIdeTimeout = 5.seconds)
        // The 'backend' subcommand should announce itself so IDE-side logs
        // distinguish backend pulls from MCP stream subscribers.
        val info = receivedClientInfos.single()
        assertTrue(info.client.contains("backend"),
            "client field should contain 'backend' marker; got: ${info.client}")
        assertNotNull(info.clientInstanceId, "instance id is part of the protocol contract")
        assertNotNull(info.clientVersion, "client version must be reported")
    }

    // ----------------------------- error paths ----------------------------

    @Test
    fun `unreachable IDE yields a row with null projects and an error message`() = runBlocking {
        // Bind a fresh port and close it so the connect immediately fails.
        val deadPort = ServerSocket(0).use { it.localPort }
        val deadIde = ide(overrideUrl = "http://127.0.0.1:$deadPort/mcp")

        val row = collectMarkerSnapshots(httpClient, listOf(deadIde), perIdeTimeout = 5.seconds).single()
        assertNull(row.projects, "projects must be null when fetch fails; got: ${row.projects}")
        assertNotNull(row.errorMessage, "errorMessage must be populated when fetch fails")
    }

    @Test
    fun `per-IDE timeout fires when the IDE never sends a snapshot`() = runBlocking {
        // Only pings, never a snapshot. With a tight per-IDE timeout the
        // collector must give up rather than hang.
        script += pingEnv()
        script += pingEnv()

        val started = System.nanoTime()
        val row = collectMarkerSnapshots(httpClient, listOf(ide()), perIdeTimeout = 500.milliseconds).single()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertNull(row.projects, "timeout must surface as null projects; got: ${row.projects}")
        assertTrue(row.errorMessage?.contains("timed out") == true,
            "error message must mention the timeout; got: ${row.errorMessage}")
        assertTrue(elapsedMs < 5_000,
            "timeout must actually fire fast; took ${elapsedMs}ms (limit was 500ms)")
    }

    // ---------------------- ordering / parallelism ------------------------

    @Test
    fun `preserves input IDE order in the returned rows`() = runBlocking {
        script += snapshotEnv(listOf(ProjectInfo("only", "/only")))
        val first = ide().copy(pid = 1L)
        val second = ide().copy(pid = 2L)
        val third = ide().copy(pid = 3L)

        val rows = collectMarkerSnapshots(httpClient, listOf(first, second, third), perIdeTimeout = 5.seconds)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.ide.pid },
            "result list must keep input order so the renderer's output is stable")
    }
}
