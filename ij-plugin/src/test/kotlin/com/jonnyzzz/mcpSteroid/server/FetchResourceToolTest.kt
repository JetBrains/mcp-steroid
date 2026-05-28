/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.jonnyzzz.mcpSteroid.mcp.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
 * Integration tests for the steroid_fetch_resource MCP tool.
 * Verifies that agents can fetch MCP Steroid resources by URI via the tool protocol.
 */
@TestApplication
class FetchResourceToolTest {

    private val projectFixture = projectFixture()
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
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
            }
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

    @Test
    fun fetchResourceReturnsSkillGuide(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)
        val skillUri = com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle().uri
        val result = callFetchResource(server, sessionId, skillUri)

        assertFalse(result.isError, "steroid_fetch_resource should succeed")
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue(text.isNotBlank(), "Response should be non-empty")
        assertTrue(text.contains("MCP Steroid"), "Response should contain skill guide content")
    }

    @Test
    fun fetchResourceToolIsAdvertised(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)

        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"tools-list","method":"tools/list"}""")
        }

        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull(toolsRpc.error, "tools/list should succeed")
        val toolsList = McpJson.decodeFromJsonElement<ToolsListResult>(toolsRpc.result!!)
        val toolNames = toolsList.tools.map { it.name }.toSet()
        assertTrue(
            toolNames.contains("steroid_fetch_resource"),
            "steroid_fetch_resource should be in the tool list",
        )
    }

    @Test
    fun fetchResourceWithNonExistentUri(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)
        // Use a URI that follows the protocol but doesn't match any registered resource
        val result = callFetchResource(server, sessionId, "mcp-steroid://nonexistent/resource-that-does-not-exist")

        assertTrue(result.isError, "Should return error for non-existent resource")
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue(text.contains("not found"), "Error message should mention resource not found")
    }

    @Test
    fun fetchResourceWithMissingUri(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)

        // Call with empty arguments (no uri parameter)
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "fetch-missing-uri")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "steroid_fetch_resource")
                    putJsonObject("arguments") {}
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(
            McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText()).result!!
        )

        assertTrue(result.isError, "Should return error for missing uri")
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        // Pin the specific branch — the schema DSL throws
        // 'Parameter <name> of type <type> is required' for missing required
        // params, so naming both 'uri' and the type pins the specific branch
        // (project_name-missing would say 'Parameter project_name ...').
        assertTrue(
            text.contains("Parameter uri of type string is required"),
            "Error message should specifically mention the missing uri parameter, got: $text",
        )
    }

    private suspend fun initializeSession(server: SteroidsMcpServer): String {
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "init-1")
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "fetch-resource-test-client")
                        put("version", "1.0.0")
                    }
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull(sessionId, "Server must issue an MCP session ID")
        return sessionId!!
    }

    private suspend fun callFetchResource(
        server: SteroidsMcpServer,
        sessionId: String,
        uri: String,
    ): ToolCallResult {
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "fetch-resource-1")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "steroid_fetch_resource")
                    putJsonObject("arguments") {
                        put("uri", uri)
                        // `project_name` is required by FetchResourceToolHandler — even
                        // though PromptsContextHandlerIJ doesn't read it (it builds the
                        // context purely from ApplicationInfo). Pass the test fixture's
                        // project name so the call shape matches what a real agent sends.
                        put("project_name", projectFixture.get().name)
                    }
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNull(rpc.error, "steroid_fetch_resource should not return JSON-RPC error")
        return McpJson.decodeFromJsonElement(rpc.result!!)
    }
}
