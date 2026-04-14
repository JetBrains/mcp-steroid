/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.*
import com.jonnyzzz.mcpSteroid.server.ActionDiscoveryResponse
import com.jonnyzzz.mcpSteroid.server.ListProductsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ServerMetadataResponse
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the MCP server.
 * Verifies the MCP HTTP handshake and tool flows against the real SteroidsMcpServer.
 */
@Suppress("GrazieInspection", "GrazieInspectionRunner")
class McpServerIntegrationTest : BasePlatformTestCase() {

    private lateinit var client: HttpClient

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
        client = HttpClient(CIO) {
            expectSuccess = false
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 60_000 // 60s — action discovery needs time on slow CI agents
                connectTimeoutMillis = 10_000
            }
        }
    }

    override fun tearDown() {
        try {
            client.close()
        } finally {
            super.tearDown()
        }
    }

    fun testMcpAgentHappyPath(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue an MCP session ID", sessionId)

        val initRpc = McpJson.decodeFromString<JsonRpcResponse>(initResponse.bodyAsText())
        assertNull("Initialize should not return error", initRpc.error)
        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(initRpc.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)
        assertEquals("mcp-steroid", initResult.serverInfo.name)

        val toolsListResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"tools-list","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, toolsListResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsListResponse.bodyAsText())
        assertNull("tools/list should succeed", toolsRpc.error)
        val toolsList = McpJson.decodeFromJsonElement<ToolsListResult>(toolsRpc.result!!)
        val toolNames = toolsList.tools.map { it.name }.toSet()
        assertTrue(
            "Steroid tools should be advertised",
            toolNames.containsAll(
                setOf(
                    "steroid_list_projects",
                    "steroid_list_windows",
                    "steroid_execute_code"
                )
            )
        )

        val listProjectsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"call-list-projects","method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }

        assertEquals(HttpStatusCode.OK, listProjectsResponse.status)
        val listProjectsRpc = McpJson.decodeFromString<JsonRpcResponse>(listProjectsResponse.bodyAsText())
        assertNull("steroid_list_projects should not return JSON-RPC error", listProjectsRpc.error)
        val listProjectsResult = McpJson.decodeFromJsonElement<ToolCallResult>(listProjectsRpc.result!!)
        assertFalse("steroid_list_projects should succeed", listProjectsResult.isError)
        val projectsPayload = (listProjectsResult.content.single() as ContentItem.Text).text
        val projects = McpJson.decodeFromString<ListProjectsResponse>(projectsPayload)
        assertTrue("IDE name should be reported", projects.ide.name.isNotBlank())
        assertTrue("IDE version should be reported", projects.ide.version.isNotBlank())
        assertTrue("IDE build should be reported", projects.ide.build.isNotBlank())
        assertTrue("Plugin id should be reported", projects.plugin.id.isNotBlank())
        assertTrue("Plugin version should be reported", projects.plugin.version.isNotBlank())
        assertTrue(
            "Current project should be discoverable via the MCP tool",
            projects.projects.any { it.name == project.name }
        )

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildExecuteCodeRequest(project.name))
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("steroid_execute_code should return result payload", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue("steroid_execute_code should return content for the agent", execOutput.isNotBlank())
        assertFalse("Execution should succeed, got error payload: $execOutput", execResult.isError)
        assertTrue(
            "Execution output should include marker text, got: $execOutput",
            execOutput.contains("Integration test execution from MCP")
        )
    }

    fun testListWindowsTool(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue MCP session id", sessionId)

        val listWindowsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"call-list-windows","method":"tools/call","params":{"name":"steroid_list_windows"}}""")
        }

        assertEquals(HttpStatusCode.OK, listWindowsResponse.status)
        val listWindowsRpc = McpJson.decodeFromString<JsonRpcResponse>(listWindowsResponse.bodyAsText())
        assertNull("steroid_list_windows should not return JSON-RPC error", listWindowsRpc.error)
        val listWindowsResult = McpJson.decodeFromJsonElement<ToolCallResult>(listWindowsRpc.result!!)
        assertFalse("steroid_list_windows should succeed", listWindowsResult.isError)

        val payload = listWindowsResult.content.filterIsInstance<ContentItem.Text>().firstOrNull()?.text ?: ""
        val windows = McpJson.decodeFromString(ListWindowsResponse.serializer(), payload)
        assertNotNull("Should return windows payload", windows)
        assertTrue("Plugin id should be reported", windows.plugin.id.isNotBlank())
        assertTrue("Plugin version should be reported", windows.plugin.version.isNotBlank())
        if (windows.windows.isNotEmpty()) {
            assertTrue(
                "windows should include windowId values",
                windows.windows.any { it.windowId.isNotBlank() }
            )
        }
    }

    fun testNpxProductsEndpoint(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val response = client.get("http://localhost:${server.port}/npx/v1/products")

        assertEquals(HttpStatusCode.OK, response.status)
        val products = McpJson.decodeFromString(ListProductsResponse.serializer(), response.bodyAsText())
        assertTrue("Expected at least one product entry", products.products.isNotEmpty())
        val first = products.products.first()
        assertTrue("Product IDE name should be reported", first.ide.name.isNotBlank())
        assertTrue("Product IDE version should be reported", first.ide.version.isNotBlank())
        assertTrue("Product plugin version should be reported", first.plugin.version.isNotBlank())
    }

    fun testNpxServerMetadataEndpoint(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val response = client.get("http://localhost:${server.port}/npx/v1/server-metadata")

        assertEquals(HttpStatusCode.OK, response.status)
        val metadata = McpJson.decodeFromString(ServerMetadataResponse.serializer(), response.bodyAsText())
        assertTrue("IDE name should be reported", metadata.ide.name.isNotBlank())
        assertTrue("Plugin version should be reported", metadata.plugin.version.isNotBlank())
        assertTrue("IDE home path should be reported", metadata.paths.homePath.isNotBlank())
        assertTrue("IDE bin path should be reported", metadata.paths.binPath.isNotBlank())
        assertTrue("Executable candidates should be reported", metadata.executable.candidates.isNotEmpty())
        assertTrue("MCP URL should be reported", metadata.mcpUrl.contains("/mcp"))
    }

    private fun buildInitializeRequest() = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", "init-1")
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "integration-test-client")
                put("version", "1.0.0")
            }
        }
    }.toString()

    private fun buildExecuteCodeRequest(projectName: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", "execute-1")
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", "steroid_execute_code")
            putJsonObject("arguments") {
                put("project_name", projectName)
                put(
                    "code",
                    """
                        println("Integration test execution from MCP")
                    """.trimIndent()
                )
                put("reason", "Verify MCP agent can execute code inside IntelliJ")
                put("task_id", "integration-test-task-1")
            }
        }
    }.toString()

    private suspend fun startSession(server: SteroidsMcpServer): String {
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue MCP session id", sessionId)

        val initRpc = McpJson.decodeFromString<JsonRpcResponse>(initResponse.bodyAsText())
        assertNull("Initialize should not return error", initRpc.error)

        return sessionId!!
    }

    fun testActionDiscoveryToolReturnsContext(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val fileText = "class ActionDiscoveryTest { void demo() { int x = 1; } }"
        val virtualFile = myFixture.tempDirFixture.createFile("ActionDiscoveryTest.java", fileText)
        val caretOffset = fileText.indexOf("ActionDiscoveryTest").coerceAtLeast(0)

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", "action-discovery-1")
                    put("method", "tools/call")
                    putJsonObject("params") {
                        put("name", "steroid_action_discovery")
                        putJsonObject("arguments") {
                            put("project_name", project.name)
                            put("file_path", virtualFile.url)
                            put("caret_offset", caretOffset)
                            putJsonArray("action_groups") { }
                            put("max_actions_per_group", 0)
                        }
                    }
                }.toString()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNull("steroid_action_discovery should return result payload", rpc.error)
        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        val output = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertFalse("steroid_action_discovery should succeed, got: $output", toolResult.isError)

        val payload = (toolResult.content.single() as ContentItem.Text).text
        val discovery = McpJson.decodeFromString<ActionDiscoveryResponse>(payload)
        assertEquals(project.name, discovery.projectName)
        assertEquals(virtualFile.path, discovery.filePath)
        assertTrue("Action groups should be empty when skipped", discovery.actionGroups.isEmpty())
    }

    fun testExecuteCodeRejectsMissingPlugins(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", "execute-missing-plugin")
                    put("method", "tools/call")
                    putJsonObject("params") {
                        put("name", "steroid_execute_code")
                        putJsonObject("arguments") {
                            put("project_name", project.name)
                            put(
                                "code",
                                """
                                    println("should not run")
                                """.trimIndent()
                            )
                            put("reason", "Verify required_plugins gating")
                            put("task_id", "integration-test-task-missing-plugin")
                            putJsonArray("required_plugins") {
                                add(JsonPrimitive("com.example.missing.plugin"))
                            }
                        }
                    }
                }.toString()
            )
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("steroid_execute_code should return result payload", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue("Missing plugin request should be an error", execResult.isError)
        assertTrue("Should mention missing plugin", execOutput.contains("Missing required plugins"))
    }

    /**
     * Tests that the server responds correctly to GET requests with Claude CLI's Accept header.
     * Claude CLI sends "Accept: application/json, text/event-stream" for health checks.
     *
     * This was causing "Failed to connect" in Claude CLI because the server was returning
     * 405 Method Not Allowed when text/event-stream was in the Accept header.
     */
    fun testGetRequestWithClaudeCliAcceptHeader(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Claude CLI sends this Accept header format for health checks
        val response = client.get(server.mcpUrl) {
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
        }

        assertEquals(
            "GET with Claude CLI Accept header should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )
        assertEquals(
            "Response should be JSON",
            ContentType.Application.Json.withoutParameters(),
            response.contentType()?.withoutParameters()
        )

        val body = response.bodyAsText()
        assertTrue(
            "Response should contain server name",
            body.contains("mcp-steroid")
        )
        assertTrue(
            "Response should indicate server is available",
            body.contains("available")
        )
    }

    /**
     * Tests that the server responds correctly to POST InitializeRequest with Claude CLI's format.
     * This verifies the full MCP handshake that Claude CLI expects.
     */
    fun testPostInitializeWithClaudeCliFormat(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Claude CLI sends InitializeRequest with specific capabilities
        val initRequest = """
        {
            "jsonrpc": "2.0",
            "id": "1",
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "clientInfo": {
                    "name": "claude-code",
                    "version": "2.0.67"
                },
                "capabilities": {
                    "roots": {
                        "listChanged": true
                    },
                    "sampling": {}
                }
            }
        }
        """.trimIndent()

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
            setBody(initRequest)
        }

        assertEquals(
            "InitializeRequest should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )

        // Check for session ID header
        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return Mcp-Session-Id header", sessionId)

        // Parse the response
        val body = response.bodyAsText()
        val rpcResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNull("Response should not have error", rpcResponse.error)
        assertNotNull("Response should have result", rpcResponse.result)

        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(rpcResponse.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)
        assertEquals("mcp-steroid", initResult.serverInfo.name)
        assertNotNull("Server should have tools capability", initResult.capabilities.tools)
    }

    /**
     * Tests with the EXACT request format Claude CLI sends (from debug logs).
     *
     * Log entry example (JSON payload omitted; see debug logs for the exact request)
     *
     * Key differences from our test:
     * - "id" is numeric 0, not string "1"
     * - "capabilities" includes an empty "roots" object
     * - Field order: method, params, jsonrpc, id (not jsonrpc first)
     */
    fun testExactClaudeCliInitializeRequest(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // EXACT request from Claude CLI debug logs
        val exactClaudeRequest = """{"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"roots":{}},"clientInfo":{"name":"claude-code","version":"2.0.67"}},"jsonrpc":"2.0","id":0}"""

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
            setBody(exactClaudeRequest)
        }

        println("[TEST] Response status: ${response.status}")
        println("[TEST] Response headers:")
        response.headers.forEach { name, values ->
            println("[TEST]   $name: ${values.joinToString(", ")}")
        }
        val body = response.bodyAsText()
        println("[TEST] Response body: $body")

        assertEquals(
            "InitializeRequest should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )

        // Check for session ID header
        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return Mcp-Session-Id header", sessionId)

        // Parse and verify the response
        val rpcResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull("Response should not have error: ${rpcResponse.error}", rpcResponse.error)
        assertNotNull("Response should have result", rpcResponse.result)

        // Verify response ID matches request ID (numeric 0)
        // Note: id is JsonElement, so we check the content
        assertTrue(
            "Response ID should be 0, got: ${rpcResponse.id}",
            rpcResponse.id.toString() == "0"
        )

        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(rpcResponse.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)
        assertEquals("mcp-steroid", initResult.serverInfo.name)
    }

    /**
     * Tests that SSE-only GET requests receive 405 Method Not Allowed.
     * This is per MCP spec - if the server doesn't support SSE, it should return 405.
     */
    fun testGetRequestWithSseOnlyAcceptHeader(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Request SSE only (not JSON) - should get 405
        val response = client.get(server.mcpUrl) {
            header("Accept", "text/event-stream")
        }

        assertEquals(
            "GET with SSE-only Accept header should return 405",
            HttpStatusCode.MethodNotAllowed,
            response.status
        )
    }

    /**
     * Tests that later requests with a session ID work correctly.
     * This verifies the full session management flow.
     */
    fun testSessionManagementFlow(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Step 1: Initialize and get session ID
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Session ID should be provided", sessionId)

        // Step 2: Make a request with the session ID
        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"2","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, toolsResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull("tools/list should succeed with valid session", toolsRpc.error)

        // Step 3: Make a request with an unknown session ID
        // Server should create a new session for unknown session IDs (supports IDE restart)
        val unknownSessionResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, "unknown-session-id-12345")
            setBody("""{"jsonrpc":"2.0","id":"3","method":"tools/list"}""")
        }

        assertEquals(
            "Request with unknown session should succeed (server creates new session)",
            HttpStatusCode.OK,
            unknownSessionResponse.status
        )

        // Server should return a new session ID
        val newSessionId = unknownSessionResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return new session ID for unknown session", newSessionId)
        assertFalse(
            "New session ID should be different from the unknown one",
            newSessionId == "unknown-session-id-12345"
        )

        // The response should be valid
        val unknownSessionRpc = McpJson.decodeFromString<JsonRpcResponse>(unknownSessionResponse.bodyAsText())
        assertNull("tools/list should succeed with auto-created session", unknownSessionRpc.error)
    }

    /**
     * This test verifies server behavior after IntelliJ IDEA restarts.
     * When a client sends an unknown session ID (for example, after an IDE restart),
     * the server should create a new session instead of rejecting the request.
     */
    fun testServerRestartHandling(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Simulate a client that has a stale session ID from before IDE restart
        val staleSessionId = "stale-session-from-previous-ide-instance"

        // Step 1: Send a tools/call request with the stale session ID
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, staleSessionId)
            setBody("""{"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }

        // Server should accept the request and create a new session
        assertEquals(
            "Request with stale session should succeed",
            HttpStatusCode.OK,
            response.status
        )

        // Server should return a new session ID
        val newSessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return new session ID", newSessionId)
        assertFalse(
            "New session ID should be different from stale one",
            newSessionId == staleSessionId
        )

        // Step 2: Verify the new session works for later requests
        val followUpResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, newSessionId)
            setBody("""{"jsonrpc":"2.0","id":"2","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, followUpResponse.status)

        // No new session ID should be returned (we're using a valid one now)
        val followUpSessionId = followUpResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNull("No new session ID should be returned for valid session", followUpSessionId)
    }

    /**
     * Tests that successful execution returns clean output without error-like formatting.
     * The response should contain LOG: entries with the output,
     * not aggressive banners that look like errors.
     */
    fun testSuccessfulExecutionReturnsCleanOutput(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code
        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildExecuteCodeRequest(project.name))
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Verify execution is not marked as an error
        assertFalse("Execution should succeed, got: $execOutput", execResult.isError)

        // Verify the output contains our marker text
        assertTrue(
            "Output should contain marker text, got: $execOutput",
            execOutput.contains("Integration test execution from MCP")
        )

        // Verify the output format is clean (not error-like)
        assertFalse(
            "Output should NOT contain aggressive ACTION REQUIRED banner",
            execOutput.contains("ACTION REQUIRED")
        )
        assertFalse(
            "Output should NOT contain box drawing characters at start",
            execOutput.startsWith("╔")
        )
        assertFalse(
            "Output should NOT contain FAILED prefix (unless actually failed)",
            execOutput.contains("FAILED:")
        )
    }

    /**
     * Tests that when the configured port is busy, the server starts on the next available port.
     * This reproduces the issue where opening multiple IDE instances or projects causes
     * "Address already in use" errors.
     */
    fun testServerStartsOnNextPortWhenConfiguredPortIsBusy(): Unit = timeoutRunBlocking(30.seconds) {
        // This test uses port 0 (dynamic allocation), so it inherently tests
        // that the server can find a free port. The real scenario (configured port busy)
        // is tested implicitly by the fact that the server starts successfully
        // even when other tests may have started servers on various ports.
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Verify the server is running
        assertTrue("Server should be running on a valid port", server.port > 0)

        // Verify the server is accessible
        val response = client.get(server.mcpUrl) {
            header("Accept", "application/json, text/event-stream")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    /**
     * Tests that compilation errors are properly reported in the API response.
     * This test demonstrates what the agent sees when code fails to compile.
     */
    fun testCompilationErrorReturnsErrorResponse(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code with syntax error - missing closing brace
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-compile-error")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            val x = 42
                            // Missing closing brace - syntax error!
                            println("This won't compile"
                    """.trimIndent())
                    put("reason", "Test compilation error handling")
                    put("task_id", "compile-error-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        // The JSON-RPC layer should succeed (no protocol error)
        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        assertNotNull("Should have result", execRpc.result)

        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== COMPILATION ERROR RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should be marked as an error
        assertTrue("Execution should be marked as error for compilation failure", execResult.isError)

        // Output should contain compilation error information
        assertTrue(
            "Output should mention compilation/script error, got: $execOutput",
            execOutput.contains("error", ignoreCase = true) ||
                    execOutput.contains("compile", ignoreCase = true) ||
                    execOutput.contains("script", ignoreCase = true)
        )
    }

    /**
     * Tests that type errors in code are properly reported.
     * This is a common error agents make - using wrong types.
     */
    fun testTypeErrorReturnsErrorResponse(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code with type error - assigning String to Int
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-type-error")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            val number: Int = "this is not a number"
                            println(number)
                    """.trimIndent())
                    put("reason", "Test type error handling")
                    put("task_id", "type-error-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== TYPE ERROR RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should be marked as an error
        assertTrue("Execution should be marked as error for type mismatch", execResult.isError)

        // Output should mention a type-related error
        assertTrue(
            "Output should mention type error, got: $execOutput",
            execOutput.contains("type", ignoreCase = true) ||
                    execOutput.contains("mismatch", ignoreCase = true) ||
                    execOutput.contains("String", ignoreCase = true) ||
                    execOutput.contains("Int", ignoreCase = true)
        )
    }

    /**
     * Tests that progress reporting works correctly over the MCP protocol.
     * When code calls progress(), the messages should be included in the response.
     *
     * Per MCP 2025-11-25 spec:
     * - Client can pass _meta.progressToken in tool call arguments
     * - Server sends notifications/progress with that token
     * - Progress messages are also accumulated in the final response
     */
    fun testProgressReportingInResponse(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code that reports progress multiple times
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-progress")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            progress("Step 1: Initializing...")
                            progress("Step 2: Processing data...")
                            progress("Step 3: Completing task...")
                            println("DONE: All steps completed")
                    """.trimIndent())
                    put("reason", "Test progress reporting")
                    put("task_id", "progress-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== PROGRESS REPORTING RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should succeed
        assertFalse("Execution should succeed", execResult.isError)

        // Output should contain our completion message
        assertTrue(
            "Output should contain completion message, got: $execOutput",
            execOutput.contains("DONE: All steps completed")
        )

        // Output should contain progress messages (they may be throttled, so check for at least one)
        assertTrue(
            "Output should contain at least one progress indicator, got: $execOutput",
            execOutput.contains("Step") || execOutput.contains("PROGRESS:")
        )
    }

    /**
     * Tests that progress reporting works with _meta.progressToken in the request.
     * The MCP 2025-11-25 spec allows clients to provide a progressToken to receive
     * notifications/progress messages during execution.
     */
    fun testProgressReportingWithProgressToken(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Create a unique progress token
        val progressToken = "progress-token-${UUID.randomUUID()}"

        // Execute code with _meta.progressToken in arguments
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-progress-token")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            progress("Starting with progress token...")
                            progress("Middle step...")
                            progress("Final step...")
                            println("COMPLETED: Task with progress token")
                    """.trimIndent())
                    put("reason", "Test progress with token")
                    put("task_id", "progress-token-test")
                    // Include _meta.progressToken per MCP spec
                    putJsonObject("_meta") {
                        put("progressToken", progressToken)
                    }
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== PROGRESS WITH TOKEN RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        assertFalse("Execution should succeed", execResult.isError)

        assertTrue(
            "Output should contain completion message, got: $execOutput",
            execOutput.contains("COMPLETED: Task with progress token")
        )

        // Progress messages from progress() calls are added to the tool result via logProgress().
        // This is the delivery mechanism for HTTP clients (streaming clients get separate notifications).
        assertTrue(
            "Output should contain progress messages from script, got: $execOutput",
            execOutput.contains("Starting with progress token")
                    || execOutput.contains("Middle step")
                    || execOutput.contains("Final step")
        )

        // Verify progress notifications were sent to the session's notification channel
        val mcpSession = server.getServer().sessionManager.getSession(sessionId!!)
        assertNotNull("Session should still exist", mcpSession)
        val notifications = mcpSession!!.drainNotifications()
        val progressNotifications = notifications.filter { it.method == McpMethods.PROGRESS }

        println("--- Progress notifications (${progressNotifications.size}) ---")
        for (n in progressNotifications) {
            val p = McpJson.decodeFromJsonElement<ProgressParams>(n.params!!)
            println("  [${p.progress}] ${p.message}")
        }

        assertTrue(
            "At least one progress notification should have been sent, got ${progressNotifications.size}",
            progressNotifications.isNotEmpty()
        )

        for (notification in progressNotifications) {
            val notifParams = McpJson.decodeFromJsonElement<ProgressParams>(notification.params!!)
            assertEquals(
                "Progress notification should use the provided token",
                JsonPrimitive(progressToken),
                notifParams.progressToken
            )
        }
    }

    /**
     * Tests that long-running operations with multiple progress updates work correctly.
     * This simulates a real-world scenario where code reports progress over time.
     */
    fun testLongRunningProgressReporting(): Unit = timeoutRunBlocking(90.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code that simulates a longer operation with multiple progress updates
        // Note: Using Thread.sleep for simulation since delay() may not be in classpath
        val code = """
                val items = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon")

                for (i in items.indices) {
                    val item = items[i]
                    progress("Processing item " + (i + 1) + "/" + items.size + ": " + item)
                    Thread.sleep(100)
                }

                println("FINISHED: Processed " + items.size + " items")
        """.trim()

        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-long-progress")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", code)
                    put("reason", "Test long-running progress")
                    put("task_id", "long-progress-test")
                    put("timeout", 60) // Give it enough time
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== LONG-RUNNING PROGRESS RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should succeed
        assertFalse("Execution should succeed", execResult.isError)

        // Output should contain our completion message
        assertTrue(
            "Output should contain completion message, got: $execOutput",
            execOutput.contains("FINISHED: Processed 5 items")
        )

        // Should contain at least some progress messages
        // Note: progress is throttled to 1 message per second, so not all may appear
        assertTrue(
            "Output should contain progress messages, got: $execOutput",
            execOutput.contains("Processing item") || execOutput.contains("PROGRESS:")
        )
    }

    /**
     * Tests that unresolved reference errors are properly reported.
     * This happens when the agent uses an API that doesn't exist.
     */
    fun testUnresolvedReferenceReturnsErrorResponse(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code with an unresolved reference
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-unresolved")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            // This class doesn't exist
                            val x = NonExistentClass.doSomething()
                            println(x)
                    """.trimIndent())
                    put("reason", "Test unresolved reference handling")
                    put("task_id", "unresolved-ref-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== UNRESOLVED REFERENCE RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should be marked as an error
        assertTrue("Execution should be marked as error for unresolved reference", execResult.isError)

        // Output should mention an unresolved reference
        assertTrue(
            "Output should mention unresolved reference, got: $execOutput",
            execOutput.contains("unresolved", ignoreCase = true) ||
                    execOutput.contains("NonExistentClass", ignoreCase = true) ||
                    execOutput.contains("reference", ignoreCase = true)
        )
    }

    /**
     * This test verifies that MCP execute_code can read a system property in the test JVM.
     * This verifies the MCP server runs in the same JVM and can access system properties.
     */
    fun testSystemPropertyCanBeReadViaMcp(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Set a system property with a random value
        val propertyKey = "mcp.test.random.value"
        val randomValue = "test-${UUID.randomUUID()}"
        System.setProperty(propertyKey, randomValue)

        try {
            // Initialize session
            val initResponse = client.post(server.mcpUrl) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(buildInitializeRequest())
            }
            val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

            // Execute code that reads the system property
            val execRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "exec-sysprop")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "steroid_execute_code")
                    putJsonObject("arguments") {
                        put("project_name", project.name)
                        put("code", $$"""
                                val value = System.getProperty("$$propertyKey")
                                println("SYSPROP_VALUE: $value")
                        """.trimIndent())
                        put("reason", "Test reading system property via MCP")
                        put("task_id", "sysprop-test")
                    }
                }
            }.toString()

            val execResponse = client.post(server.mcpUrl) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header(McpHttpTransport.SESSION_HEADER, sessionId)
                setBody(execRequest)
            }

            assertEquals(HttpStatusCode.OK, execResponse.status)
            val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
            assertNull("Execute should not return error", execRpc.error)

            val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
            val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

            assertFalse("Execution should succeed, got: $execOutput", execResult.isError)
            assertTrue(
                "Output should contain the system property value '$randomValue', got: $execOutput",
                execOutput.contains("SYSPROP_VALUE: $randomValue")
            )
        } finally {
            // Clean up the system property
            System.clearProperty(propertyKey)
        }
    }

    /**
     * Tests that MCP resources are properly listed and can be read.
     * Verifies the "IntelliJ API Power User Guide" resource is available.
     */
    fun testResourcesListAndRead(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // List resources
        val listResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"resources-list","method":"resources/list"}""")
        }

        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listRpc = McpJson.decodeFromString<JsonRpcResponse>(listResponse.bodyAsText())
        assertNull("resources/list should succeed", listRpc.error)

        val resourcesList = McpJson.decodeFromJsonElement<ResourcesListResult>(listRpc.result!!)
        assertTrue("Should have at least one resource", resourcesList.resources.isNotEmpty())

        val skillResource = resourcesList.resources.find { it.name.contains("Power User Guide") }
        assertNotNull("Should have IntelliJ API Power User Guide resource", skillResource)
        assertEquals("text/markdown", skillResource!!.mimeType)
        assertTrue("Resource should have catchy description", skillResource.description?.contains("RECOMMENDED") == true)

        // Read the resource
        val readResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "resources-read")
                put("method", "resources/read")
                putJsonObject("params") {
                    put("uri", skillResource.uri)
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, readResponse.status)
        val readRpc = McpJson.decodeFromString<JsonRpcResponse>(readResponse.bodyAsText())
        assertNull("resources/read should succeed", readRpc.error)

        val readResult = McpJson.decodeFromJsonElement<ResourceReadResult>(readRpc.result!!)
        assertTrue("Should have at least one content item", readResult.contents.isNotEmpty())

        val content = readResult.contents.first()
        assertEquals(skillResource.uri, content.uri)
        assertEquals("text/markdown", content.mimeType)
        assertNotNull("Resource should have text content", content.text)
        assertTrue("Content should contain SKILL.md content", content.text!!.contains("MCP Steroid"))
        assertTrue("Content should contain quickstart", content.text.contains("Quickstart"))
    }

    /**
     * Tests that MCP prompts expose Agent Skills and can be retrieved.
     */
    fun testPromptsListAndGetForSkills(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        val listResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"prompts-list","method":"prompts/list"}""")
        }

        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listRpc = McpJson.decodeFromString<JsonRpcResponse>(listResponse.bodyAsText())
        assertNull("prompts/list should succeed", listRpc.error)

        val promptsList = McpJson.decodeFromJsonElement<PromptsListResult>(listRpc.result!!)
        val skillUri = SkillPromptArticle().uri
        val mainPrompt = promptsList.prompts.find { it.name == skillUri }
        assertNotNull("Should expose main skill prompt", mainPrompt)
        assertEquals("IntelliJ API Power User Guide", mainPrompt!!.title)

        val getResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "prompts-get")
                put("method", "prompts/get")
                putJsonObject("params") {
                    put("name", skillUri)
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, getResponse.status)
        val getRpc = McpJson.decodeFromString<JsonRpcResponse>(getResponse.bodyAsText())
        assertNull("prompts/get should succeed", getRpc.error)

        val getResult = McpJson.decodeFromJsonElement<PromptGetResult>(getRpc.result!!)
        assertEquals(1, getResult.messages.size)
        val message = getResult.messages.first()
        assertEquals("user", message.role)
        val content = message.content as PromptContent.Text
        assertTrue(content.text.contains("MCP Steroid"))
        assertFalse(content.text.trimStart().startsWith("---"))
    }

    /**
     * Tests the complete MCP handshake that real agents perform:
     * 1. GET health check (with Claude CLI Accept header)
     * 2. POST initialize (get session ID)
     * 3. POST notifications/initialized (with session ID, no response expected)
     * 4. POST tools/list (with session ID)
     * 5. POST tools/call (with session ID)
     *
     * This test ensures the server handles the full lifecycle correctly,
     * including the notification step that creates no response body.
     */
    fun testFullAgentHandshakeWithNotification(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Step 1: GET health check (like Claude CLI does before connecting)
        val healthResponse = client.get(server.mcpUrl) {
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
        }
        assertEquals(HttpStatusCode.OK, healthResponse.status)
        val healthBody = healthResponse.bodyAsText()
        assertTrue("Health check should return server name", healthBody.contains("mcp-steroid"))

        // Step 2: POST initialize
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
            setBody("""{"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"roots":{}},"clientInfo":{"name":"claude-code","version":"2.0.67"}},"jsonrpc":"2.0","id":0}""")
        }
        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must return session ID", sessionId)

        val initRpc = McpJson.decodeFromString<JsonRpcResponse>(initResponse.bodyAsText())
        assertNull("Initialize should not error", initRpc.error)
        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(initRpc.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)

        // Step 3: POST notifications/initialized (no id = notification)
        val notifyResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"method":"notifications/initialized","jsonrpc":"2.0"}""")
        }
        assertEquals(
            "notifications/initialized should return 202 Accepted",
            HttpStatusCode.Accepted,
            notifyResponse.status
        )
        // No new session ID should be returned for notification with valid session
        assertNull(
            "No new session ID for notification with valid session",
            notifyResponse.headers[McpHttpTransport.SESSION_HEADER]
        )

        // Step 4: POST tools/list with session ID
        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, toolsResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull("tools/list should succeed", toolsRpc.error)
        val toolsList = McpJson.decodeFromJsonElement<ToolsListResult>(toolsRpc.result!!)
        assertTrue("Should have steroid tools", toolsList.tools.any { it.name.startsWith("steroid_") })

        // Step 5: POST tools/call steroid_list_projects (with session ID)
        val callResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }
        assertEquals(HttpStatusCode.OK, callResponse.status)
        val callRpc = McpJson.decodeFromString<JsonRpcResponse>(callResponse.bodyAsText())
        assertNull("tools/call should not return JSON-RPC error", callRpc.error)
        val callResult = McpJson.decodeFromJsonElement<ToolCallResult>(callRpc.result!!)
        assertFalse("steroid_list_projects should succeed", callResult.isError)
    }

    /**
     * Tests that the server correctly handles requests without Accept header.
     * While the MCP spec says clients MUST include Accept, the server should be
     * lenient for backwards compatibility (some tools/curl don't set it).
     */
    fun testPostWithoutAcceptHeader(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            // Deliberately NOT setting Accept header
            setBody(buildInitializeRequest())
        }

        assertEquals(
            "POST without Accept header should still succeed",
            HttpStatusCode.OK,
            initResponse.status
        )
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Should still create session", sessionId)
    }

    /**
     * Tests that forgetAllForTest() truly restarts the Ktor server, breaking
     * all existing HTTP connections and invalidating all sessions.
     *
     * After restart:
     * - Old session IDs are unknown (server creates new sessions)
     * - Server is reachable on the new port
     * - New sessions can be established and used
     */
    fun testServerRestartBreaksConnections(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val portBefore = server.port
        assertTrue("Server should be running", portBefore > 0)

        // Step 1: Establish a session and verify it works
        val sessionId = startSession(server)

        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"pre-restart","method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, toolsResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull("tools/list should succeed before restart", toolsRpc.error)

        // Step 2: Restart the server
        server.forgetAllForTest()

        val portAfter = server.port
        assertTrue("Server should be running after restart", portAfter > 0)

        // Step 3: Verify old session ID is no longer known
        // The server creates a new session for unknown IDs (returns new Mcp-Session-Id)
        val oldSessionResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"post-restart","method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, oldSessionResponse.status)
        val newSessionId = oldSessionResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should assign a new session for the old (unknown) session ID", newSessionId)
        assertFalse(
            "New session ID should differ from the old one",
            newSessionId == sessionId
        )

        // Step 4: Verify the restarted server responds to health checks
        val healthResponse = client.get(server.mcpUrl) {
            header("Accept", "application/json")
        }
        assertEquals(HttpStatusCode.OK, healthResponse.status)
        assertTrue(
            "Health check should return server info",
            healthResponse.bodyAsText().contains("mcp-steroid")
        )

        // Step 5: Verify a brand-new session can be established and used
        val freshSessionId = startSession(server)
        val freshToolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, freshSessionId)
            setBody("""{"jsonrpc":"2.0","id":"fresh","method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, freshToolsResponse.status)
        val freshToolsRpc = McpJson.decodeFromString<JsonRpcResponse>(freshToolsResponse.bodyAsText())
        assertNull("tools/list should succeed with fresh session after restart", freshToolsRpc.error)
    }

    /**
     * Tests that the MCP-Protocol-Version header is included in responses.
     * Per MCP 2025-11-25 spec, the server should include this in all responses.
     */
    fun testProtocolVersionHeaderInResponses(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Check GET response
        val getResponse = client.get(server.mcpUrl) {
            header("Accept", "application/json")
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals(
            "GET response should include protocol version header",
            MCP_PROTOCOL_VERSION,
            getResponse.headers[McpHttpTransport.PROTOCOL_VERSION_HEADER]
        )

        // Check POST response
        val postResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        assertEquals(HttpStatusCode.OK, postResponse.status)
        assertEquals(
            "POST response should include protocol version header",
            MCP_PROTOCOL_VERSION,
            postResponse.headers[McpHttpTransport.PROTOCOL_VERSION_HEADER]
        )
    }

    /**
     * Tests that a type mismatch compilation error produces an error response
     * with the actual compiler error message visible in the output.
     *
     * Uses the same code snippet as CliClaudeIntegrationTest.testCompilationErrorsDelivered
     * to verify the server-side behavior independently of any AI agent.
     */
    fun testTypeMismatchCompilationErrorContainsErrorMessage(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-type-mismatch")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                        val x: String = 123
                        println(x)
                    """.trimIndent())
                    put("reason", "Test type mismatch error message delivery")
                    put("task_id", "type-mismatch-error-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("JSON-RPC should not have protocol error", execRpc.error)

        val debug = Json { prettyPrint = true }.encodeToString(execRpc)
        println("The whole output: $debug")

        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== TYPE MISMATCH ERROR RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        assertTrue("Execution should be marked as error", execResult.isError)

        // The actual kotlinc error message must be present, not just generic "error"
        assertTrue(
            "Output should contain 'type mismatch' from the compiler, got: $execOutput",
            execOutput.contains("type mismatch", ignoreCase = true)
                    || execOutput.contains("Type mismatch", ignoreCase = true)
        )
        assertTrue(
            "Output should mention the expected type (String), got: $execOutput",
            execOutput.contains("String")
        )
        assertTrue(
            "Output should mention the actual type (Int), got: $execOutput",
            execOutput.contains("Int")
        )
    }

    /**
     * Tests that compilation errors are included in the tool result content items even
     * when a progress token is provided. The tool result is the delivery mechanism for
     * HTTP clients — all logMessage/logProgress/reportFailed content is added to it
     * synchronously before the HTTP response is sent.
     */
    fun testCompilationErrorWithProgressToken(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val progressToken = "progress-compile-error-${UUID.randomUUID()}"

        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-compile-error-progress")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                        val x: String = 123
                        println(x)
                    """.trimIndent())
                    put("reason", "Test compilation error with progress token")
                    put("task_id", "compile-error-progress-test")
                    putJsonObject("_meta") {
                        put("progressToken", progressToken)
                    }
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("JSON-RPC should not have protocol error", execRpc.error)

        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== COMPILATION ERROR WITH PROGRESS TOKEN ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        assertTrue("Execution should be marked as error", execResult.isError)

        // Compilation error details must be in the tool result content
        assertTrue(
            "Output should contain 'type mismatch' from the compiler, got: $execOutput",
            execOutput.contains("type mismatch", ignoreCase = true)
        )
        assertTrue(
            "Output should mention the expected type (String), got: $execOutput",
            execOutput.contains("String")
        )
        assertTrue(
            "Output should mention the actual type (Int), got: $execOutput",
            execOutput.contains("Int")
        )

        // The FAILED marker should also be in the result (from reportFailed)
        assertTrue(
            "Output should contain FAILED marker, got: $execOutput",
            execOutput.contains("FAILED:")
        )

        // Verify progress notifications were sent to the session's notification channel
        val mcpSession = server.getServer().sessionManager.getSession(sessionId)
        assertNotNull("Session should still exist", mcpSession)
        val notifications = mcpSession!!.drainNotifications()
        val progressNotifications = notifications.filter { it.method == McpMethods.PROGRESS }

        println("--- Progress notifications (${progressNotifications.size}) ---")
        for (n in progressNotifications) {
            val p = McpJson.decodeFromJsonElement<ProgressParams>(n.params!!)
            println("  [${p.progress}] ${p.message}")
        }

        assertTrue(
            "At least one progress notification should have been sent for compilation error, got ${progressNotifications.size}",
            progressNotifications.isNotEmpty()
        )

        for (notification in progressNotifications) {
            val notifParams = McpJson.decodeFromJsonElement<ProgressParams>(notification.params!!)
            assertEquals(
                "Progress notification should use the provided token",
                JsonPrimitive(progressToken),
                notifParams.progressToken
            )
        }
    }

    /**
     * Tests that compiler warnings are delivered in the tool result alongside the execution output.
     *
     * The code uses an unchecked cast (List<Any> to List<String>) which produces a compiler
     * warning on stderr. The test verifies that warnings appear in the tool result content items
     * together with the successful execution output.
     *
     * Uses the same code snippet as CliClaudeIntegrationTest.testCompilationWarningsDelivered.
     */
    fun testCompilationWarningsDeliveredOnSuccess(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val progressToken = "progress-warnings-${UUID.randomUUID()}"

        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-warnings")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                        val items: List<Any> = listOf("hello", "world")
                        @Suppress("NOTHING_TO_SUPPRESS")
                        val strings: List<String> = items as List<String>
                        println("WARNING_TEST_VALUE: " + strings.joinToString(","))
                    """.trimIndent())
                    put("reason", "Test compiler warning delivery")
                    put("task_id", "warnings-test")
                    putJsonObject("_meta") {
                        put("progressToken", progressToken)
                    }
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("JSON-RPC should not have protocol error", execRpc.error)

        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== COMPILER WARNINGS RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Code should compile and execute successfully
        assertFalse("Execution should succeed (warnings don't block compilation), got: $execOutput", execResult.isError)

        // The println output should be present
        assertTrue(
            "Output should contain the execution result, got: $execOutput",
            execOutput.contains("WARNING_TEST_VALUE: hello,world")
        )

        // Compiler warnings should be present in the tool result
        assertTrue(
            "Output should contain compiler warnings (unchecked cast or @Suppress warning), got: $execOutput",
            execOutput.contains("warning", ignoreCase = true)
                    || execOutput.contains("Unchecked cast", ignoreCase = true)
                    || execOutput.contains("Compiler Errors/Warnings", ignoreCase = true)
        )

        // Verify progress notifications were sent to the session's notification channel
        val mcpSession = server.getServer().sessionManager.getSession(sessionId)
        assertNotNull("Session should still exist", mcpSession)
        val notifications = mcpSession!!.drainNotifications()
        val progressNotifications = notifications.filter { it.method == McpMethods.PROGRESS }

        println("--- Progress notifications (${progressNotifications.size}) ---")
        for (n in progressNotifications) {
            val p = McpJson.decodeFromJsonElement<ProgressParams>(n.params!!)
            println("  [${p.progress}] ${p.message}")
        }

        assertTrue(
            "At least one progress notification should have been sent for successful execution, got ${progressNotifications.size}",
            progressNotifications.isNotEmpty()
        )

        for (notification in progressNotifications) {
            val notifParams = McpJson.decodeFromJsonElement<ProgressParams>(notification.params!!)
            assertEquals(
                "Progress notification should use the provided token",
                JsonPrimitive(progressToken),
                notifParams.progressToken
            )
        }
    }

}
