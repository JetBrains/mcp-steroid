/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.transport.http

import com.jonnyzzz.mcpSteroid.mcp.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * Protocol-level integration tests for McpHttpTransport.
 * Verifies MCP 2025-11-25 compliance over HTTP.
 */
class McpHttpTransportProtocolTest {

    private lateinit var mcpServer: McpServerCore
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var port: Int = 0

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }

        mcpServer = McpServerCore(
            serverInfo = ServerInfo(name = "test-server", version = "1.0.0"),
            capabilities = ServerCapabilities(
                tools = ToolsCapability(listChanged = false),
                resources = ResourcesCapability(),
                prompts = PromptsCapability(),
            )
        )

        // Register a test tool
        mcpServer.toolRegistry.registerTool(
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

        // Register a test resource
        mcpServer.resourceRegistry.registerResource(
            uri = "test://hello",
            name = "Hello Resource",
            description = "A test resource",
            mimeType = "text/plain",
            contentProvider = { "Hello, World!" }
        )

        // Register a test prompt
        mcpServer.promptRegistry.registerPrompt(
            prompt = Prompt(name = "test_prompt", description = "Test prompt"),
            renderer = { PromptGetResult(messages = listOf(PromptMessage(role = "user", content = PromptContent.Text("Hello from prompt")))) }
        )

        server = embeddedServer(CIO, port = port) {
            routing {
                with(McpHttpTransport) {
                    installMcp("/mcp", mcpServer)
                }
            }
        }
        server.start(wait = false)
        client = HttpClient(io.ktor.client.engine.cio.CIO)
    }

    @After
    fun tearDown() {
        client.close()
        server.stop(100, 100)
    }

    // === Helper ===

    private suspend fun postMcp(body: JsonObject, sessionId: String? = null): HttpResponse {
        return client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(body.toString())
            if (sessionId != null) {
                header(McpHttpTransport.SESSION_HEADER, sessionId)
            }
        }
    }

    private fun initializeRequest(id: Int = 1): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "test-client")
                put("version", "1.0.0")
            }
        }
    }

    private fun jsonRpcRequest(id: Int, method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) {
            put("params", params)
        }
    }

    // === Tests ===

    @Test
    fun `initialize handshake returns session and capabilities`() = runBlocking {
        val response = postMcp(initializeRequest())
        assertEquals(HttpStatusCode.OK, response.status)

        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Should return session ID", sessionId)

        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNotNull(body.result)
        val result = McpJson.decodeFromJsonElement<InitializeResult>(body.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, result.protocolVersion)
        assertEquals("test-server", result.serverInfo.name)
    }

    @Test
    fun `tools list returns registered tools`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val response = postMcp(jsonRpcRequest(2, "tools/list"), sessionId)
        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<ToolsListResult>(body.result!!)

        assertTrue("Should have at least one tool", result.tools.isNotEmpty())
        val echoTool = result.tools.find { it.name == "test_echo" }
        assertNotNull("Should find test_echo tool", echoTool)
        assertEquals("Echo the input back", echoTool!!.description)
    }

    @Test
    fun `tools call executes tool and returns result`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val callParams = buildJsonObject {
            put("name", "test_echo")
            putJsonObject("arguments") {
                put("message", "hello")
            }
        }
        val response = postMcp(jsonRpcRequest(2, "tools/call", callParams), sessionId)
        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(body.result!!)

        assertFalse("Should not be error", result.isError)
        val text = (result.content.first() as ContentItem.Text).text
        assertEquals("echo: hello", text)
    }

    @Test
    fun `resources list returns registered resources`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val response = postMcp(jsonRpcRequest(2, "resources/list"), sessionId)
        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<ResourcesListResult>(body.result!!)

        assertTrue("Should have resources", result.resources.isNotEmpty())
        assertNotNull(result.resources.find { it.uri == "test://hello" })
    }

    @Test
    fun `resources read returns resource content`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val readParams = buildJsonObject { put("uri", "test://hello") }
        val response = postMcp(jsonRpcRequest(2, "resources/read", readParams), sessionId)
        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<ResourceReadResult>(body.result!!)

        assertEquals(1, result.contents.size)
        assertEquals("Hello, World!", result.contents.first().text)
    }

    @Test
    fun `prompts list returns registered prompts`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val response = postMcp(jsonRpcRequest(2, "prompts/list"), sessionId)
        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<PromptsListResult>(body.result!!)

        assertTrue("Should have prompts", result.prompts.isNotEmpty())
        assertNotNull(result.prompts.find { it.name == "test_prompt" })
    }

    @Test
    fun `prompts get returns prompt messages`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val getParams = buildJsonObject { put("name", "test_prompt") }
        val response = postMcp(jsonRpcRequest(2, "prompts/get", getParams), sessionId)
        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<PromptGetResult>(body.result!!)

        assertEquals(1, result.messages.size)
        assertEquals("user", result.messages.first().role)
    }

    @Test
    fun `session reuse with header`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        // Second request with same session
        val resp2 = postMcp(jsonRpcRequest(2, "ping"), sessionId)
        assertEquals(HttpStatusCode.OK, resp2.status)

        // Should NOT create a new session
        val newSessionHeader = resp2.headers[McpHttpTransport.SESSION_HEADER]
        assertNull("Should not return new session on reuse", newSessionHeader)
    }

    @Test
    fun `unknown session creates new session with notice`() = runBlocking {
        val response = postMcp(jsonRpcRequest(1, "ping"), sessionId = "nonexistent-session")
        assertEquals(HttpStatusCode.OK, response.status)

        val newSessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Should create new session", newSessionId)

        val notice = response.headers[McpHttpTransport.SESSION_NOTICE_HEADER]
        assertNotNull("Should include notice about old session", notice)
    }

    @Test
    fun `invalid JSON returns parse error`() = runBlocking {
        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody("{invalid json")
        }

        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNotNull("Should have error", body.error)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, body.error!!.code)
    }

    @Test
    fun `unknown method returns method not found error`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val response = postMcp(jsonRpcRequest(2, "nonexistent/method"), sessionId)
        val body = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNotNull("Should have error", body.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, body.error!!.code)
    }

    @Test
    fun `CORS OPTIONS preflight returns ok`() = runBlocking {
        val response = client.options("http://localhost:$port/mcp") {
            header("Origin", "http://localhost")
            header("Access-Control-Request-Method", "POST")
        }
        // OPTIONS should succeed (200 or 204)
        assertTrue("OPTIONS should succeed", response.status.value in 200..204)
    }

    @Test
    fun `DELETE terminates session`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val deleteResp = client.delete("http://localhost:$port/mcp") {
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            accept(ContentType.Application.Json)
        }
        // DELETE should succeed
        assertTrue("DELETE should succeed", deleteResp.status.value in 200..204)

        // Subsequent request should get a new session (old one was removed)
        val afterDelete = postMcp(jsonRpcRequest(2, "ping"), sessionId)
        val newSessionId = afterDelete.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Should create new session after delete", newSessionId)
    }

    @Test
    fun `batch request returns batch response`() = runBlocking {
        val initResp = postMcp(initializeRequest())
        val sessionId = initResp.headers[McpHttpTransport.SESSION_HEADER]!!

        val batch = buildJsonArray {
            add(jsonRpcRequest(2, "tools/list"))
            add(jsonRpcRequest(3, "resources/list"))
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(batch.toString())
            header(McpHttpTransport.SESSION_HEADER, sessionId)
        }

        val body = McpJson.parseToJsonElement(response.bodyAsText())
        assertTrue("Batch response should be array", body is JsonArray)
        assertEquals(2, (body as JsonArray).size)
    }
}
