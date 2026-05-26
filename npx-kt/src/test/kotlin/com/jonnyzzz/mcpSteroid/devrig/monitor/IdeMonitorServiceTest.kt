/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.server.NPX_NDJSON_MIME_TYPE
import com.jonnyzzz.mcpSteroid.server.NPX_PROJECTS_STREAM_PATH
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import com.jonnyzzz.mcpSteroid.server.NpxStreamEnvelope
import com.jonnyzzz.mcpSteroid.server.NpxStreamJson
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end round-trip: a tiny Ktor server stands in for the IDE, we point
 * an [IdeDiscoveryService] at a fake `~/.mcp-steroid/markers/<pid>.mcp-steroid` marker, and
 * verify that [IdeMonitorService] connects, receives the IDE's snapshot
 * envelopes, and updates [IdeMonitorService.states].
 */
class IdeMonitorServiceTest {

    private val ourPid = ProcessHandle.current().pid()
    private val seq = AtomicLong(0)
    private val instanceId = "fake-ide-test"

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private lateinit var scope: CoroutineScope
    private val receivedClientInfos = mutableListOf<NpxStreamClientInfo>()
    private var port: Int = 0

    /** Per-test handle that tells the server which envelopes to emit and when. */
    private val script = mutableListOf<NpxStreamEnvelope>()

    /** Authorization headers seen by the fake server, indexed by request. */
    private val receivedAuthHeaders = mutableListOf<String?>()

    @BeforeEach
    fun setUp() {
        port = freePort()
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post(NPX_PROJECTS_STREAM_PATH) {
                    receivedAuthHeaders += call.request.headers["Authorization"]
                    val body = call.receiveText()
                    val info = NpxStreamJson.decodeClientInfo(body)
                    receivedClientInfos += info
                    call.respondTextWriter(ContentType.parse(NPX_NDJSON_MIME_TYPE)) {
                        for (env in script) {
                            write(NpxStreamJson.encodeEnvelope(env))
                            write("\n")
                            flush()
                        }
                        // Hold the connection open until cancelled — production
                        // semantics are "stream stays open forever". The test
                        // tears the server down to drop the connection.
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
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 2_000
            }
            expectSuccess = false
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        httpClient.close()
        server.stop(0L, 0L)
    }

    @Test
    fun `monitor receives the IDE's first snapshot and surfaces it on states`(
        @TempDir homeDir: Path,
    ) = runBlocking {
        // Marker pointing the discovery layer at our fake IDE.
        writeMarker(homeDir, port)

        // Pre-load one snapshot the server will emit on connect.
        script += snapshot(listOf(ProjectInfo("alpha", "/p/alpha")))

        val discovery = IdeDiscoveryService(
            markersDir = PidMarker.markerDirectory(homeDir),
            allowHosts = listOf("127.0.0.1"),
            scanInterval = 200.milliseconds,
        )
        val monitor = IdeMonitorService(
            httpClient = httpClient,
            discovery = discovery,
            clientInfo = NpxStreamClientInfo(client = "test-suite", clientPid = 4242L),
            reconnectBackoff = 200.milliseconds,
        )

        discovery.start(scope)
        monitor.start(scope)

        val state = withTimeout(10.seconds) {
            monitor.states.first { it[ourPid]?.lastSnapshot?.isNotEmpty() == true }
        }

        val ide = state.getValue(ourPid)
        assertEquals(IdeMonitorStatus.CONNECTED, ide.status)
        assertEquals(listOf(ProjectInfo("alpha", "/p/alpha")), ide.lastSnapshot)
        assertEquals(instanceId, ide.ideInstanceId)
        assertNotNull(ide.lastSeenAt)

        assertTrue(receivedClientInfos.any { it.client == "test-suite" && it.clientPid == 4242L })
        assertTrue(
            receivedAuthHeaders.any { it == "Bearer deadbeef" },
            "expected the monitor to send Authorization: Bearer <token>; saw: $receivedAuthHeaders"
        )
    }

    @Test
    fun `monitor follows multiple snapshot envelopes from the IDE`(
        @TempDir homeDir: Path,
    ) = runBlocking {
        writeMarker(homeDir, port)

        script += snapshot(listOf(ProjectInfo("a", "/p/a")))
        script += snapshot(listOf(ProjectInfo("a", "/p/a"), ProjectInfo("b", "/p/b")))
        script += snapshot(listOf(ProjectInfo("b", "/p/b")))

        val discovery = IdeDiscoveryService(
            markersDir = PidMarker.markerDirectory(homeDir),
            allowHosts = listOf("127.0.0.1"),
            scanInterval = 200.milliseconds,
        )
        val monitor = IdeMonitorService(
            httpClient = httpClient,
            discovery = discovery,
            clientInfo = NpxStreamClientInfo(client = "test-suite"),
            reconnectBackoff = 200.milliseconds,
        )

        discovery.start(scope)
        monitor.start(scope)

        val finalState = withTimeout(10.seconds) {
            monitor.states.first { state ->
                val snap = state[ourPid]?.lastSnapshot
                snap?.singleOrNull()?.name == "b"
            }
        }
        assertEquals(listOf(ProjectInfo("b", "/p/b")), finalState.getValue(ourPid).lastSnapshot)
    }

    private fun writeMarker(homeDir: Path, port: Int, token: String = "deadbeef") {
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = ourPid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://127.0.0.1:$port/mcp",
                port = port,
                headers = mapOf("Authorization" to "Bearer $token"),
            ),
            ide = IdeInfo(name = "FakeIDE", version = "x", build = "y"),
            plugin = PluginInfo(id = "x", name = "y", version = "z"),
            createdAt = "2026-05-10T12:34:56Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        val markerDir = PidMarker.markerDirectory(homeDir)
        java.nio.file.Files.createDirectories(markerDir)
        File(markerDir.toFile(), PidMarker.markerFileNameFor(ourPid))
            .writeText(PidMarkerJson.encode(marker))
    }

    private fun snapshot(projects: List<ProjectInfo>) = NpxStreamEnvelope(
        type = "snapshot",
        seq = seq.incrementAndGet(),
        sentAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
        instanceId = instanceId,
        pid = 99999L,
        projects = projects,
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
