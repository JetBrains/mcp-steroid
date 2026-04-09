/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.transport.stdio

import com.jonnyzzz.mcpSteroid.mcp.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

/**
 * Protocol-level integration tests for McpStdioTransport.
 * Uses piped streams to simulate stdio communication.
 */
class McpStdioTransportProtocolTest {

    private fun createServer(): McpServerCore {
        val server = McpServerCore(
            serverInfo = ServerInfo(name = "test-server", version = "1.0.0"),
            capabilities = ServerCapabilities(
                tools = ToolsCapability(listChanged = false),
                resources = ResourcesCapability(),
                prompts = PromptsCapability(),
            )
        )

        server.toolRegistry.registerTool(
            name = "test_echo",
            description = "Echo the input back",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("message") { put("type", "string") }
                }
            },
            handler = { context ->
                val msg = context.params.arguments?.get("message")
                    ?.let { (it as? JsonPrimitive)?.content } ?: "no message"
                ToolCallResult(content = listOf(ContentItem.Text(text = "echo: $msg")))
            }
        )

        server.resourceRegistry.registerResource(
            uri = "test://hello",
            name = "Hello Resource",
            description = "A test resource",
            mimeType = "text/plain",
            contentProvider = { "Hello, World!" }
        )

        server.promptRegistry.registerPrompt(
            prompt = Prompt(name = "test_prompt", description = "Test prompt"),
            renderer = {
                PromptGetResult(
                    messages = listOf(
                        PromptMessage(role = "user", content = PromptContent.Text("Hello from prompt"))
                    )
                )
            }
        )

        return server
    }

    private fun jsonRpcRequest(id: Int, method: String, params: JsonObject? = null): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        return McpJson.encodeToString(JsonObject.serializer(), obj)
    }

    private fun initializeRequest(id: Int = 1): String {
        val params = buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "test-client")
                put("version", "1.0.0")
            }
        }
        return jsonRpcRequest(id, "initialize", params)
    }

    private fun runTransport(server: McpServerCore, inputLines: List<String>): List<String> = runBlocking {
        val input = ByteArrayInputStream((inputLines.joinToString("\n") + "\n").toByteArray())
        val output = ByteArrayOutputStream()
        val transport = McpStdioTransport(server, input, output)
        withTimeout(5000) { transport.run() }
        output.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    @Test
    fun `initialize handshake over stdio`() {
        val server = createServer()
        val responses = runTransport(server, listOf(initializeRequest()))
        assertTrue("Should have at least one response", responses.isNotEmpty())
        val body = McpJson.decodeFromString<JsonRpcResponse>(responses.first())
        assertNotNull(body.result)
        val result = McpJson.decodeFromJsonElement<InitializeResult>(body.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, result.protocolVersion)
        assertEquals("test-server", result.serverInfo.name)
    }

    @Test
    fun `tools list returns registered tools`() {
        val server = createServer()
        val responses = runTransport(server, listOf(initializeRequest(), jsonRpcRequest(2, "tools/list")))
        assertTrue("Should have 2 responses", responses.size >= 2)
        val body = McpJson.decodeFromString<JsonRpcResponse>(responses[1])
        val result = McpJson.decodeFromJsonElement<ToolsListResult>(body.result!!)
        assertNotNull(result.tools.find { it.name == "test_echo" })
    }

    @Test
    fun `tools call executes tool`() {
        val server = createServer()
        val callParams = buildJsonObject {
            put("name", "test_echo")
            putJsonObject("arguments") { put("message", "hello") }
        }
        val responses = runTransport(server, listOf(initializeRequest(), jsonRpcRequest(2, "tools/call", callParams)))
        val body = McpJson.decodeFromString<JsonRpcResponse>(responses[1])
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(body.result!!)
        assertEquals("echo: hello", (result.content.first() as ContentItem.Text).text)
    }

    @Test
    fun `resources list and read`() {
        val server = createServer()
        val readParams = buildJsonObject { put("uri", "test://hello") }
        val responses = runTransport(server, listOf(
            initializeRequest(), jsonRpcRequest(2, "resources/list"), jsonRpcRequest(3, "resources/read", readParams)
        ))
        val readBody = McpJson.decodeFromString<JsonRpcResponse>(responses[2])
        val readResult = McpJson.decodeFromJsonElement<ResourceReadResult>(readBody.result!!)
        assertEquals("Hello, World!", readResult.contents.first().text)
    }

    @Test
    fun `prompts list and get`() {
        val server = createServer()
        val getParams = buildJsonObject { put("name", "test_prompt") }
        val responses = runTransport(server, listOf(
            initializeRequest(), jsonRpcRequest(2, "prompts/list"), jsonRpcRequest(3, "prompts/get", getParams)
        ))
        val getBody = McpJson.decodeFromString<JsonRpcResponse>(responses[2])
        val getResult = McpJson.decodeFromJsonElement<PromptGetResult>(getBody.result!!)
        assertEquals(1, getResult.messages.size)
    }

    @Test
    fun `error handling for unknown method`() {
        val server = createServer()
        val responses = runTransport(server, listOf(initializeRequest(), jsonRpcRequest(2, "nonexistent/method")))
        val body = McpJson.decodeFromString<JsonRpcResponse>(responses[1])
        assertNotNull(body.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, body.error!!.code)
    }

    @Test
    fun `EOF causes graceful shutdown`() {
        val server = createServer()
        val responses = runTransport(server, emptyList())
        assertNotNull("Should complete without error", responses)
    }

    @Test
    fun `blank lines are ignored`() {
        val server = createServer()
        val responses = runTransport(server, listOf("", "  ", initializeRequest(), ""))
        assertTrue("Should have response for initialize", responses.isNotEmpty())
    }
}
