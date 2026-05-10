/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
class McpSamplingIntegrationTest : BasePlatformTestCase() {

    private lateinit var client: HttpClient

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
        client = HttpClient(CIO) {
            expectSuccess = false
        }
    }

    override fun tearDown() {
        try {
            client.close()
        } finally {
            super.tearDown()
        }
    }

    // testSamplingProtocolTypes — pure JSON round-trip — moved to
    // :mcp-core/.../McpSamplingProtocolTypesTest so it runs without the
    // IntelliJ Platform / Ktor harness.

    /**
     * Tests that the server detects client sampling capability during initialization.
     */
    fun testClientSamplingCapabilityDetection(): Unit = timeoutRunBlocking(30.seconds) {
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
        assertNotNull("Should get session ID", sessionIdWithSampling)

        // Verify the session detected sampling support
        val sessionWithSampling = server.getServer().sessionManager.getSession(sessionIdWithSampling!!)
        assertNotNull("Session should exist", sessionWithSampling)
        assertTrue("Session should support sampling", sessionWithSampling!!.supportsSampling())

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
        assertNotNull("Session should exist", sessionWithoutSampling)
        assertFalse("Session should NOT support sampling", sessionWithoutSampling!!.supportsSampling())
    }

    /**
     * Tests that the test sampling tool returns appropriate error when client
     * doesn't support sampling.
     */
    fun testSamplingToolWithoutClientSupport(): Unit = timeoutRunBlocking(30.seconds) {
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
        assertNull("No JSON-RPC error", rpcResponse.error)

        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpcResponse.result!!)
        val output = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== SAMPLING WITHOUT SUPPORT RESPONSE ===")
        println("isError: ${toolResult.isError}")
        println("Output: $output")
        println("=== END ===")

        assertTrue("Should be marked as error", toolResult.isError)
        assertTrue("Should indicate sampling not supported", output.contains("SAMPLING_NOT_SUPPORTED"))
    }

    /**
     * Tests the bidirectional sampling flow using coroutines.
     * This simulates a client that responds to sampling requests.
     */
    fun testSamplingBidirectionalFlow(): Unit = timeoutRunBlocking(60.seconds) {
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

        assertTrue("Session should support sampling", session.supportsSampling())

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
        assertNull("No JSON-RPC error: ${rpcResponse.error}", rpcResponse.error)

        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpcResponse.result!!)
        val output = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== SAMPLING SUCCESS RESPONSE ===")
        println("isError: ${toolResult.isError}")
        println("Output: $output")
        println("=== END ===")

        assertFalse("Should NOT be marked as error", toolResult.isError)
        assertTrue("Should contain success marker", output.contains("SAMPLING_SUCCESS"))
        assertTrue("Should contain the mock response", output.contains("The answer is 4."))
    }

    /**
     * Tests that the McpMethods constant for sampling is correct.
     */
    fun testSamplingMethodConstant() {
        assertEquals("sampling/createMessage", McpMethods.SAMPLING_CREATE_MESSAGE)
    }

    /**
     * Tests that image content in sampling messages serializes correctly.
     */
    fun testSamplingImageContent(): Unit = timeoutRunBlocking(10.seconds) {
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
