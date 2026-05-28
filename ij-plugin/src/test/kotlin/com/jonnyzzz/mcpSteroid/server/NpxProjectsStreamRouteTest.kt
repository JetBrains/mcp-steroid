/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * In-process Ktor test for [streamProjectsNdjson] — boots a tiny ktor
 * server, drives the projects flow from the test, and asserts the wire
 * shape of the NDJSON stream.
 */
class NpxProjectsStreamRouteTest {

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private val flow = MutableStateFlow(listOf(ProjectInfo(name = "alpha", path = "/p/alpha")))
    private val seq = AtomicLong(0)
    private val clientInfos = mutableListOf<NpxStreamClientInfo>()
    private var port: Int = 0

    @Before
    fun setUp() {
        port = freePort()
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post("/npx/v1/projects/stream") {
                    call.streamProjectsNdjson(
                        projectsFlow = flow,
                        instanceId = "test-instance",
                        pid = 99999L,
                        nextSeq = { seq.incrementAndGet() },
                        pingInterval = 200.milliseconds,
                        onClientInfo = { clientInfos += it },
                    )
                }
            }
        }.also { it.start(wait = false) }

        // Wait for the engine to actually accept connections.
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }

        client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
            }
            expectSuccess = false
        }
    }

    @After
    fun tearDown() {
        client.close()
        server.stop(0L, 0L)
    }

    @Test
    fun `initial snapshot is delivered immediately on connect`(): Unit = runBlocking {
        readEnvelopes(expectedAtLeast = 1).let { envelopes ->
            val snap = envelopes.first()
            assertEquals("snapshot", snap.type)
            assertEquals("test-instance", snap.instanceId)
            assertEquals(99999L, snap.pid)
            assertEquals(listOf(ProjectInfo("alpha", "/p/alpha")), snap.projects)
        }
        assertEquals(1, clientInfos.size)
    }

    @Test
    fun `flow updates produce fresh snapshot envelopes`(): Unit = runBlocking {
        val deferred = CompletableDeferred<List<NpxStreamEnvelope>>()
        val readerJob = launch(Dispatchers.IO) {
            val collected = mutableListOf<NpxStreamEnvelope>()
            consumeStream { env ->
                collected += env
                if (env.type == "snapshot" && env.projects?.size == 2) {
                    deferred.complete(collected.toList())
                    return@consumeStream false
                }
                true
            }
        }
        // Push an update after the consumer has likely subscribed.
        delay(200)
        flow.value = listOf(
            ProjectInfo("alpha", "/p/alpha"),
            ProjectInfo("beta", "/p/beta"),
        )

        val collected = withTimeout(5.seconds) { deferred.await() }
        readerJob.cancelAndJoin()

        val snapshots = collected.filter { it.type == "snapshot" }
        assertTrue(
            "expected at least 2 snapshots (initial + update), got: $snapshots",
            snapshots.size >= 2
        )
        val final = snapshots.last()
        assertEquals(2, final.projects?.size)
        assertEquals("beta", final.projects?.get(1)?.name)
    }

    @Test
    fun `ping events are emitted on the configured interval`(): Unit = runBlocking {
        val deferred = CompletableDeferred<NpxStreamEnvelope>()
        val readerJob = launch(Dispatchers.IO) {
            consumeStream { env ->
                if (env.type == "ping") {
                    deferred.complete(env)
                    return@consumeStream false
                }
                true
            }
        }
        val ping = withTimeout(5.seconds) { deferred.await() }
        readerJob.cancelAndJoin()

        assertEquals("ping", ping.type)
        assertEquals("test-instance", ping.instanceId)
        assertEquals(99999L, ping.pid)
        assertNotNull(ping.sentAt)
    }

    @Test
    fun `client info from POST body is surfaced to the handler`(): Unit = runBlocking {
        val body = """
            {
              "client": "test-suite",
              "clientPid": 4242,
              "clientVersion": "1.2.3",
              "clientInstanceId": "abc",
              "platform": "darwin",
              "arch": "aarch64",
              "futureField": "must-be-ignored"
            }
        """.trimIndent()
        readEnvelopes(expectedAtLeast = 1, postBody = body)

        assertEquals(1, clientInfos.size)
        val info = clientInfos.single()
        assertEquals("test-suite", info.client)
        assertEquals(4242L, info.clientPid)
        assertEquals("1.2.3", info.clientVersion)
        assertEquals("abc", info.clientInstanceId)
        assertEquals("darwin", info.platform)
        assertEquals("aarch64", info.arch)
    }

    private suspend fun consumeStream(
        postBody: String = "{}",
        onEnvelope: suspend (NpxStreamEnvelope) -> Boolean,
    ) {
        client.preparePost("http://127.0.0.1:$port/npx/v1/projects/stream") {
            headers { append(HttpHeaders.ContentType, "application/json") }
            setBody(postBody)
        }.execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val env = NpxStreamJson.decodeEnvelope(line)
                if (!onEnvelope(env)) return@execute
            }
        }
    }

    private suspend fun readEnvelopes(
        expectedAtLeast: Int,
        postBody: String = "{}",
    ): List<NpxStreamEnvelope> {
        val collected = mutableListOf<NpxStreamEnvelope>()
        withTimeout(5.seconds) {
            consumeStream(postBody) { env ->
                collected += env
                collected.size < expectedAtLeast
            }
        }
        return collected
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
