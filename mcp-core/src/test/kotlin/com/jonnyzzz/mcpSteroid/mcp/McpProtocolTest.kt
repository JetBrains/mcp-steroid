/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for MCP protocol types and serialization.
 */
class McpProtocolTest {

    @Test
    fun `test JsonRpcRequest serialization`() {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
            }
        )

        val json = McpJson.encodeToString(JsonRpcRequest.serializer(), request)
        assertTrue(json.contains("\"jsonrpc\"") && json.contains("\"2.0\""), "Should contain jsonrpc")
        assertTrue(json.contains("\"id\"") && json.contains("1"), "Should contain id")
        assertTrue(json.contains("\"method\"") && json.contains("\"initialize\""), "Should contain method")

        val decoded = McpJson.decodeFromString<JsonRpcRequest>(json)
        assertEquals(JSONRPC_VERSION, decoded.jsonrpc)
        assertEquals(1, decoded.id.jsonPrimitive.int)
        assertEquals("initialize", decoded.method)
    }

    @Test
    fun `test JsonRpcRequest with string id`() {
        val request = JsonRpcRequest(
            id = JsonPrimitive("request-123"),
            method = "tools/list"
        )

        val json = McpJson.encodeToString(JsonRpcRequest.serializer(), request)
        val decoded = McpJson.decodeFromString<JsonRpcRequest>(json)
        assertEquals("request-123", decoded.id.jsonPrimitive.content)
    }

    @Test
    fun `test JsonRpcResponse with result`() {
        val response = JsonRpcResponse(
            id = JsonPrimitive(1),
            result = buildJsonObject {
                put("tools", JsonArray(emptyList()))
            }
        )

        val json = McpJson.encodeToString(JsonRpcResponse.serializer(), response)
        val decoded = McpJson.decodeFromString<JsonRpcResponse>(json)

        assertEquals(JSONRPC_VERSION, decoded.jsonrpc)
        assertNotNull(decoded.result)
        assertNull(decoded.error)
    }

    @Test
    fun `test JsonRpcResponse with error`() {
        val response = JsonRpcResponse(
            id = JsonPrimitive(1),
            error = JsonRpcError(
                code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
                message = "Method not found"
            )
        )

        val json = McpJson.encodeToString(JsonRpcResponse.serializer(), response)
        val decoded = McpJson.decodeFromString<JsonRpcResponse>(json)

        assertNull(decoded.result)
        assertNotNull(decoded.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, decoded.error?.code)
    }

    @Test
    fun `test InitializeParams serialization`() {
        val params = InitializeParams(
            protocolVersion = MCP_PROTOCOL_VERSION,
            capabilities = ClientCapabilities(
                roots = RootsCapability(listChanged = true)
            ),
            clientInfo = ClientInfo(
                name = "test-client",
                version = "1.0.0"
            )
        )

        val json = McpJson.encodeToString(InitializeParams.serializer(), params)
        val decoded = McpJson.decodeFromString<InitializeParams>(json)

        assertEquals(MCP_PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals("test-client", decoded.clientInfo.name)
        assertEquals(true, decoded.capabilities.roots?.listChanged)
    }

    @Test
    fun `test InitializeResult serialization`() {
        val result = InitializeResult(
            protocolVersion = MCP_PROTOCOL_VERSION,
            capabilities = ServerCapabilities(
                tools = ToolsCapability(listChanged = true)
            ),
            serverInfo = ServerInfo(
                name = "test-server",
                version = "1.0.0"
            ),
            instructions = "Test instructions"
        )

        val json = McpJson.encodeToString(InitializeResult.serializer(), result)
        val decoded = McpJson.decodeFromString<InitializeResult>(json)

        assertEquals(MCP_PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals("test-server", decoded.serverInfo.name)
        assertEquals("Test instructions", decoded.instructions)
    }

    @Test
    fun `test Tool serialization`() {
        val tool = Tool(
            name = "test_tool",
            description = "A test tool",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("arg1") {
                        put("type", "string")
                    }
                }
            }
        )

        val json = McpJson.encodeToString(Tool.serializer(), tool)
        val decoded = McpJson.decodeFromString<Tool>(json)

        assertEquals("test_tool", decoded.name)
        assertEquals("A test tool", decoded.description)
        assertNotNull(decoded.inputSchema)
    }

    @Test
    fun `test ToolCallParams serialization`() {
        val params = ToolCallParams(
            name = "test_tool",
            arguments = buildJsonObject {
                put("arg1", "value1")
                put("arg2", 42)
            }
        )

        val json = McpJson.encodeToString(ToolCallParams.serializer(), params)
        val decoded = McpJson.decodeFromString<ToolCallParams>(json)

        assertEquals("test_tool", decoded.name)
        assertEquals("value1", decoded.arguments["arg1"]?.jsonPrimitive?.content)
        assertEquals(42, decoded.arguments["arg2"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test ToolCallResult with text content`() {
        val result = ToolCallResult(
            content = listOf(ContentItem.Text(text = "Hello, World!"))
        )

        val json = McpJson.encodeToString(ToolCallResult.serializer(), result)
        val decoded = McpJson.decodeFromString<ToolCallResult>(json)

        assertEquals(1, decoded.content.size)
        assertTrue(decoded.content[0] is ContentItem.Text)
        assertEquals("Hello, World!", (decoded.content[0] as ContentItem.Text).text)
    }

    @Test
    fun `test ToolCallResult with error`() {
        val result = ToolCallResult(
            content = listOf(ContentItem.Text(text = "Error message")),
            isError = true
        )

        val json = McpJson.encodeToString(ToolCallResult.serializer(), result)
        val decoded = McpJson.decodeFromString<ToolCallResult>(json)

        assertTrue(decoded.isError)
    }

    @Test
    fun `test ContentItem Image serialization`() {
        val image = ContentItem.Image(
            data = "base64encodeddata",
            mimeType = "image/png"
        )

        val json = McpJson.encodeToString(ContentItem.serializer(), image)
        val decoded = McpJson.decodeFromString<ContentItem>(json)

        assertTrue(decoded is ContentItem.Image)
        assertEquals("base64encodeddata", (decoded as ContentItem.Image).data)
        assertEquals("image/png", decoded.mimeType)
    }

    @Test
    fun `test ProgressParams serialization`() {
        val params = ProgressParams(
            progressToken = JsonPrimitive("token-123"),
            progress = 50.0,
            total = 100.0,
            message = "Processing..."
        )

        val json = McpJson.encodeToString(ProgressParams.serializer(), params)
        val decoded = McpJson.decodeFromString<ProgressParams>(json)

        assertEquals("token-123", decoded.progressToken.jsonPrimitive.content)
        assertEquals(50.0, decoded.progress, 0.01)
        assertEquals(100.0, decoded.total!!, 0.01)
        assertEquals("Processing...", decoded.message)
    }

    @Test
    fun `test JsonRpcNotification serialization`() {
        val notification = JsonRpcNotification(
            method = McpMethods.INITIALIZED
        )

        val json = McpJson.encodeToString(JsonRpcNotification.serializer(), notification)
        val decoded = McpJson.decodeFromString<JsonRpcNotification>(json)

        assertEquals(JSONRPC_VERSION, decoded.jsonrpc)
        assertEquals(McpMethods.INITIALIZED, decoded.method)
        assertNull(decoded.params)
    }

    @Test
    fun `test Prompt serialization with icons`() {
        val prompt = Prompt(
            name = "test-prompt",
            title = "Test Prompt",
            description = "Description",
            icons = listOf(
                Icon(
                    src = "https://example.com/icon.png",
                    mimeType = "image/png",
                    sizes = listOf("64x64"),
                )
            )
        )

        val json = McpJson.encodeToString(Prompt.serializer(), prompt)
        val decoded = McpJson.decodeFromString<Prompt>(json)
        assertEquals("test-prompt", decoded.name)
        assertEquals(1, decoded.icons?.size)
        assertEquals("https://example.com/icon.png", decoded.icons?.first()?.src)
        assertEquals(listOf("64x64"), decoded.icons?.first()?.sizes)
    }

    @Test
    fun `test PromptContent Image serialization`() {
        val image = PromptContent.Image(data = "base64", mimeType = "image/png")
        val json = McpJson.encodeToString(PromptContent.serializer(), image)
        val decoded = McpJson.decodeFromString<PromptContent>(json)
        assertTrue(decoded is PromptContent.Image)
    }

    @Test
    fun `test PromptContent Audio serialization`() {
        val audio = PromptContent.Audio(data = "base64", mimeType = "audio/mpeg")
        val json = McpJson.encodeToString(PromptContent.serializer(), audio)
        val decoded = McpJson.decodeFromString<PromptContent>(json)
        assertTrue(decoded is PromptContent.Audio)
    }

    @Test
    fun `test PromptContent Resource serialization`() {
        val resource = PromptContent.Resource(resource = EmbeddedResource(uri = "mcp://resource"))
        val json = McpJson.encodeToString(PromptContent.serializer(), resource)
        val decoded = McpJson.decodeFromString<PromptContent>(json)
        assertTrue(decoded is PromptContent.Resource)
    }
}
