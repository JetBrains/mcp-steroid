/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.jonnyzzz.mcpSteroid.mcp.*
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for MCP Sampling feature.
 *
 * MCP Sampling allows servers to request LLM completions from clients.
 * This test verifies:
 * 1. Protocol types are correctly serialized/deserialized
 * 2. Server can detect client sampling capability
 * 3. Tool can request sampling from client
 * 4. Server correctly routes sampling responses
 */
@TestApplication
class McpSamplingIntegrationTest {

    private lateinit var client: HttpClient
    private val previousProps = mutableMapOf<String, String?>()

    @BeforeEach
    fun setUp() {
        // Bind MCP server to 0.0.0.0 so Docker containers can reach it via host.docker.internal
        setProp("mcp.steroid.server.host", "0.0.0.0")
        // Allow CI/release-builder to override the test port to avoid host port conflicts.
        val testPort = System.getenv("MCP_STEROID_TEST_PORT")
            ?.takeIf { it.isNotBlank() }
            ?: "17820"
        setProp("mcp.steroid.server.port", testPort)
        client = HttpClient(CIO) {
            expectSuccess = false
        }
    }

    @AfterEach
    fun tearDown() {
        try {
            client.close()
        } finally {
            for ((name, oldValue) in previousProps) {
                if (oldValue != null) System.setProperty(name, oldValue) else System.clearProperty(name)
            }
            previousProps.clear()
        }
    }

    private fun setProp(name: String, value: String) {
        previousProps.getOrPut(name) { System.getProperty(name) }
        System.setProperty(name, value)
    }

    // testSamplingProtocolTypes — pure JSON round-trip — moved to
    // :mcp-core/.../McpSamplingProtocolTypesTest so it runs without the
    // IntelliJ Platform / Ktor harness.

    /**
     * Tests that the server detects client sampling capability during initialization.
     */
    @Test
    fun clientSamplingCapabilityDetection(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize WITH sampling capability
        val initRequestWithSampling = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "init-sampling")
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {
                    putJsonObject("sampling") {} // Empty object = supports sampling
                }
                putJsonObject("clientInfo") {
                    put("name", "test-client-with-sampling")
                    put("version", "1.0.0")
                }
            }
        }.toString()

        val responseWithSampling = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequestWithSampling)
        }

        assertEquals(HttpStatusCode.OK, responseWithSampling.status)
        val sessionIdWithSampling = responseWithSampling.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull(sessionIdWithSampling, "Should get session ID")

        // Verify the session detected sampling support
        val sessionWithSampling = server.getServer().sessionManager.getSession(sessionIdWithSampling!!)
        assertNotNull(sessionWithSampling, "Session should exist")
        assertTrue(sessionWithSampling!!.supportsSampling(), "Session should support sampling")

        // Initialize WITHOUT sampling capability
        val initRequestWithoutSampling = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "init-no-sampling")
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {
                    // No sampling field
                }
                putJsonObject("clientInfo") {
                    put("name", "test-client-no-sampling")
                    put("version", "1.0.0")
                }
            }
        }.toString()

        val responseWithoutSampling = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequestWithoutSampling)
        }

        assertEquals(HttpStatusCode.OK, responseWithoutSampling.status)
        val sessionIdWithoutSampling = responseWithoutSampling.headers[McpHttpTransport.SESSION_HEADER]

        val sessionWithoutSampling = server.getServer().sessionManager.getSession(sessionIdWithoutSampling!!)
        assertNotNull(sessionWithoutSampling, "Session should exist")
        assertFalse(sessionWithoutSampling!!.supportsSampling(), "Session should NOT support sampling")
    }

    /**
     * Tests that the test sampling tool returns appropriate error when client
     * doesn't support sampling.
     */
    @Test
    fun samplingToolWithoutClientSupport(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Register the test sampling tool
        server.getServer().toolRegistry.registerTool(SamplingTestToolHandler())

        // Initialize WITHOUT sampling capability
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "init")
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("capabilities") {} // No sampling
                    putJsonObject("clientInfo") {
                        put("name", "test-client")
                        put("version", "1.0.0")
                    }
                }
            }.toString())
        }

        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call the test sampling tool
        val toolResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "call-sampling")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", SamplingTestToolHandler.TOOL_NAME)
                    putJsonObject("arguments") {
                        put("prompt", "What is 2+2?")
                    }
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, toolResponse.status)
        val rpcResponse = McpJson.decodeFromString<JsonRpcResponse>(toolResponse.bodyAsText())
        assertNull(rpcResponse.error, "No JSON-RPC error")

        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpcResponse.result!!)
        val output = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== SAMPLING WITHOUT SUPPORT RESPONSE ===")
        println("isError: ${toolResult.isError}")
        println("Output: $output")
        println("=== END ===")

        assertTrue(toolResult.isError, "Should be marked as error")
        assertTrue(output.contains("SAMPLING_NOT_SUPPORTED"), "Should indicate sampling not supported")
    }

    /**
     * Tests the bidirectional sampling flow using coroutines.
     * This simulates a client that responds to sampling requests.
     */
    @Test
    fun samplingBidirectionalFlow(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Register the test sampling tool
        server.getServer().toolRegistry.registerTool(SamplingTestToolHandler())

        // Initialize WITH sampling capability
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "init")
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("capabilities") {
                        putJsonObject("sampling") {} // Supports sampling
                    }
                    putJsonObject("clientInfo") {
                        put("name", "test-client-sampling")
                        put("version", "1.0.0")
                    }
                }
            }.toString())
        }

        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]!!
        val session = server.getServer().sessionManager.getSession(sessionId)!!

        assertTrue(session.supportsSampling(), "Session should support sampling")

        // Start a coroutine to listen for outgoing requests and respond
        val samplingResponseJob = launch {
            // Wait for an outgoing request (the sampling request)
            val samplingRequest = session.outgoingRequests().first()

            println("=== RECEIVED SAMPLING REQUEST ===")
            println("Method: ${samplingRequest.method}")
            println("ID: ${samplingRequest.id}")
            println("Params: ${samplingRequest.params}")
            println("=== END ===")

            assertEquals(McpMethods.SAMPLING_CREATE_MESSAGE, samplingRequest.method)

            // Simulate processing and sending back a response
            val requestIdString = samplingRequest.id.toString().trim('"')

            // Create a mock LLM response
            val mockResponse = CreateMessageResult(
                role = "assistant",
                content = SamplingContent.Text(text = "The answer is 4."),
                model = "mock-model",
                stopReason = "endTurn"
            )

            // Route the response back to the session
            val responseJson = McpJson.encodeToJsonElement(mockResponse)
            session.handleResponse(requestIdString, responseJson)
        }

        // Small delay to ensure the listener is ready
        delay(100)

        // Call the test sampling tool
        val toolResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "call-sampling")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", SamplingTestToolHandler.TOOL_NAME)
                    putJsonObject("arguments") {
                        put("prompt", "What is 2+2?")
                    }
                }
            }.toString())
        }

        // Wait for the response job to complete
        samplingResponseJob.join()

        assertEquals(HttpStatusCode.OK, toolResponse.status)
        val rpcResponse = McpJson.decodeFromString<JsonRpcResponse>(toolResponse.bodyAsText())
        assertNull(rpcResponse.error, "No JSON-RPC error: ${rpcResponse.error}")

        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpcResponse.result!!)
        val output = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== SAMPLING SUCCESS RESPONSE ===")
        println("isError: ${toolResult.isError}")
        println("Output: $output")
        println("=== END ===")

        assertFalse(toolResult.isError, "Should NOT be marked as error")
        assertTrue(output.contains("SAMPLING_SUCCESS"), "Should contain success marker")
        assertTrue(output.contains("The answer is 4."), "Should contain the mock response")
    }

    /**
     * Tests that the McpMethods constant for sampling is correct.
     */
    @Test
    fun samplingMethodConstant() {
        assertEquals("sampling/createMessage", McpMethods.SAMPLING_CREATE_MESSAGE)
    }

    /**
     * Tests that image content in sampling messages serializes correctly.
     */
    @Test
    fun samplingImageContent(): Unit = timeoutRunBlocking(10.seconds) {
        val imageMessage = SamplingMessage(
            role = "user",
            content = SamplingContent.Image(
                data = "base64encodeddata==",
                mimeType = "image/png"
            )
        )

        val json = McpJson.encodeToJsonElement(imageMessage)
        println("Image message JSON: $json")

        val decoded = McpJson.decodeFromJsonElement<SamplingMessage>(json)
        assertTrue(decoded.content is SamplingContent.Image)
        val imageContent = decoded.content as SamplingContent.Image
        assertEquals("base64encodeddata==", imageContent.data)
        assertEquals("image/png", imageContent.mimeType)
    }
}
