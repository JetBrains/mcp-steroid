/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for [IntelliJPortDiscovery]. The IDE side of the probe is
 * impersonated by a tiny Ktor server bound to a known port; the
 * discovery service points at that port range and reports what it
 * finds. Includes:
 *  - a "garbage" port (server returns 200 but non-IDE JSON)
 *  - a "never bound" port (connect refused)
 * so we verify the scanner doesn't crash on non-IDE responses or on
 * absent servers.
 */
class IntelliJPortDiscoveryTest {

    private lateinit var httpClient: HttpClient
    private var idePort: Int = 0
    private var garbagePort: Int = 0
    private var refusedPort: Int = 0
    private lateinit var ideServer: EmbeddedServer<*, *>
    private lateinit var garbageServer: EmbeddedServer<*, *>

    @BeforeEach
    fun setUp() {
        // Bind the fake servers with port=0 so Ktor asks the OS for a free port and keeps it bound.
        // The refused probe uses a fixed reserved port that should never expose an IntelliJ /api/about.
        refusedPort = 1

        ideServer = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/api/about") {
                    call.respondText(
                        """
                        {
                          "name": "IntelliJ IDEA 2025.3 Ultimate",
                          "productName": "IDEA",
                          "edition": "Ultimate",
                          "baselineVersion": 253,
                          "buildNumber": "253.28294.334"
                        }
                        """.trimIndent(),
                        io.ktor.http.ContentType.Application.Json
                    )
                }
            }
        }.start(wait = false)
        idePort = ideServer.resolvedPort()

        garbageServer = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/api/about") {
                    // Looks like JSON but doesn't carry IDE-identifying
                    // fields — must NOT be classified as an IntelliJ.
                    call.respondText(
                        """{"hello":"world"}""",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
                get("/other") {
                    call.respond(HttpStatusCode.OK, "not relevant")
                }
            }
        }.start(wait = false)
        garbagePort = garbageServer.resolvedPort()

        httpClient = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 5_000 }
            expectSuccess = false
        }
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        ideServer.stop(0L, 0L)
        garbageServer.stop(0L, 0L)
    }

    @Test
    fun `scanOnce detects an IDE on the impersonated port`() = runBlocking {
        val discovery = IntelliJPortDiscovery(
            httpClient = httpClient,
            portRanges = listOf(idePort..idePort, garbagePort..garbagePort, refusedPort..refusedPort),
            probeTimeout = 800.milliseconds,
        )
        try {
            discovery.scanOnce()
            val detected = discovery.detected.value
            assertEquals(1, detected.size, "expected only the IDE port to surface, got: $detected")
            val ide = detected.single()
            assertEquals(idePort, ide.port)
            assertEquals("IDEA", ide.productName)
            assertEquals("IntelliJ IDEA 2025.3 Ultimate", ide.productFullName)
            assertEquals("Ultimate", ide.edition)
            assertEquals(253, ide.baselineVersion)
            assertEquals("253.28294.334", ide.buildNumber)
            assertEquals("http://127.0.0.1:$idePort", ide.baseUrl)
        } finally {
            discovery.close()
        }
    }

    @Test
    fun `scanOnce rejects non-IDE responses (no productName, no name)`() = runBlocking {
        val discovery = IntelliJPortDiscovery(
            httpClient = httpClient,
            portRanges = listOf(garbagePort..garbagePort),
            probeTimeout = 800.milliseconds,
        )
        try {
            discovery.scanOnce()
            assertTrue(discovery.detected.value.isEmpty(), "garbage port must not appear: ${discovery.detected.value}")
        } finally {
            discovery.close()
        }
    }

    @Test
    fun `scanOnce tolerates connection-refused without crashing`() = runBlocking {
        val discovery = IntelliJPortDiscovery(
            httpClient = httpClient,
            portRanges = listOf(refusedPort..refusedPort),
            probeTimeout = 800.milliseconds,
        )
        try {
            discovery.scanOnce()
            assertTrue(discovery.detected.value.isEmpty())
        } finally {
            discovery.close()
        }
    }

    @Test
    fun `probes run on dedicated daemon threads with the expected name prefix`() = runBlocking {
        val discovery = IntelliJPortDiscovery(
            httpClient = httpClient,
            portRanges = listOf(idePort..idePort),
            probeTimeout = 800.milliseconds,
            parallelism = 4,
        )
        try {
            discovery.scanOnce()
            val scanThreads = Thread.getAllStackTraces().keys
                .filter { it.name.startsWith("mcp-steroid-port-scan-") }
            assertTrue(
                scanThreads.isNotEmpty(),
                "expected at least one mcp-steroid-port-scan-* thread to exist; saw ${Thread.getAllStackTraces().keys.map { it.name }.sorted()}"
            )
            assertTrue(
                scanThreads.all { it.isDaemon },
                "every scan thread must be a daemon; saw: ${scanThreads.map { it.name to it.isDaemon }}"
            )
        } finally {
            discovery.close()
        }
    }

    private fun EmbeddedServer<*, *>.resolvedPort(): Int = runBlocking {
        engine.resolvedConnectors().first().port
    }
}
