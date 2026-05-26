/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.mcp.MCP_PROTOCOL_VERSION
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.StdioMcpProcess
import com.jonnyzzz.mcpSteroid.testHelper.startStdioMcpProcess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Integration tests that drive the devrig CLI launcher (`bin/devrig`)
 * as a subprocess and exchange MCP 2025-11-25 JSON-RPC frames over stdio.
 *
 * Scope: confirm the stdio MCP server boots, completes the initialize handshake,
 * and answers list/ping requests with shapes that match the spec. Tool *calls*
 * are deliberately out of scope — every handler is currently stubbed via
 * [com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools] and would throw
 * `UnsupportedOperationException("not yet ready: ...")`. Cover-tool-invocations
 * tests will be added once real handlers land in devrig.
 *
 * The launcher path is provided by Gradle via the `devrig.launcher` system
 * property (set in `build.gradle.kts` for the `integrationTest` task). Running
 * the test directly from an IDE: pass `-Ddevrig.launcher=<path>` after
 * `./gradlew :npx-kt:installDist`.
 */
class CliMcpStdioIntegrationTest {

    private val lifetime = CloseableStackHost()
    private lateinit var process: StdioMcpProcess

    @BeforeEach
    fun setUp() {
        val launcherPath = System.getProperty("devrig.launcher")
            ?: error(
                "System property 'devrig.launcher' is not set. Run via " +
                        "`./gradlew :npx-kt:integrationTest`, or set the property " +
                        "to the path of the installDist launcher script."
            )
        val launcher = File(launcherPath)
        check(launcher.canExecute()) { "Launcher is not executable: $launcherPath" }
        process = startStdioMcpProcess(launcher, lifetime)
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    fun `initialize returns server info and advertised capabilities`() {
        val response = process.initialize()

        assertEquals("2.0", response["jsonrpc"]?.jsonPrimitive?.contentOrNull, "jsonrpc field")
        assertNull(response["error"], "initialize must not return an error: $response")

        val result = response["result"]?.jsonObject
            ?: error("initialize response missing `result`: $response")

        assertEquals(MCP_PROTOCOL_VERSION, result["protocolVersion"]?.jsonPrimitive?.contentOrNull)

        val serverInfo = result["serverInfo"]?.jsonObject
            ?: error("initialize result missing `serverInfo`: $result")
        assertEquals("devrig", serverInfo["name"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(serverInfo["version"]?.jsonPrimitive?.contentOrNull, "serverInfo.version")

        val capabilities = result["capabilities"]?.jsonObject
            ?: error("initialize result missing `capabilities`: $result")
        assertNotNull(capabilities["tools"], "capabilities.tools must be advertised")
        assertNotNull(capabilities["prompts"], "capabilities.prompts must be advertised")
        assertNotNull(capabilities["resources"], "capabilities.resources must be advertised")
    }

    @Test
    fun `tools list contains every steroid_ tool registered by McpSteroidTools`() {
        process.initialize()

        val response = process.request("tools/list", buildJsonObject {})
        val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray
            ?: error("tools/list result missing `tools` array: $response")

        val names = tools.map {
            val tool = it.jsonObject
            requireNotNull(tool["name"]?.jsonPrimitive?.contentOrNull) { "tool entry missing name: $tool" }
        }.toSet()

        // The expected set is shared with the agent E2E tests so a regression
        // in McpSteroidTools.registerAll surfaces from both directions.
        val missing = EXPECTED_STEROID_TOOL_NAMES - names
        assertTrue(
            missing.isEmpty(),
            "tools/list missing expected tools: $missing\nactual=$names"
        )

        // Each tool must have a name + description + inputSchema (Tool descriptor shape).
        tools.forEach {
            val tool = it.jsonObject
            assertNotNull(tool["description"]?.jsonPrimitive?.contentOrNull, "tool ${tool["name"]} missing description")
            assertNotNull(tool["inputSchema"]?.jsonObject, "tool ${tool["name"]} missing inputSchema")
        }
    }

    @Test
    fun `prompts list returns an array even when no prompts are registered`() {
        process.initialize()

        val response = process.request("prompts/list", buildJsonObject {})
        assertNull(response["error"], "prompts/list must not error: $response")

        val prompts = response["result"]?.jsonObject?.get("prompts")
        assertTrue(prompts is JsonArray, "result.prompts must be an array: $response")
    }

    @Test
    fun `resources list returns an array even when no resources are registered`() {
        process.initialize()

        val response = process.request("resources/list", buildJsonObject {})
        assertNull(response["error"], "resources/list must not error: $response")

        val resources = response["result"]?.jsonObject?.get("resources")
        assertTrue(resources is JsonArray, "result.resources must be an array: $response")
    }

    @Test
    fun `ping returns an empty result object`() {
        process.initialize()

        val response = process.request("ping", buildJsonObject {})
        assertNull(response["error"], "ping must not error: $response")

        val result = response["result"]?.jsonObject
            ?: error("ping must return a JSON object result: $response")
        assertTrue(result.isEmpty(), "ping result must be empty: $result")
    }

    @Test
    fun `unknown method returns JSON-RPC method-not-found error`() {
        process.initialize()

        val response = process.request("does/not/exist", buildJsonObject {})
        val error = response["error"]?.jsonObject
            ?: error("unknown method must return an error: $response")

        // JSON-RPC §5.1: -32601 = Method not found.
        assertEquals(
            -32601,
            error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            "method-not-found error code: $error"
        )
    }

    @Test
    fun `notifications without id receive no response`() {
        process.initialize()

        // Spec (JSON-RPC 2.0 §4.1): notifications must NEVER produce a response.
        // We send one, then drain the server's stdout for a window long enough
        // to catch a misbehaving "respond to every frame" implementation. Any
        // frame that arrives during the wait is a violation and fails the test.
        process.notify("notifications/cancelled", buildJsonObject {
            put("requestId", "this-request-does-not-exist")
            put("reason", "test")
        })

        val unexpected = process.drainNoMore(timeoutMillis = 500)
        assertTrue(
            unexpected.isEmpty(),
            "notifications must not yield any response, but server emitted: $unexpected",
        )

        // A real round-trip after the notification confirms the session is still
        // alive (i.e. the server didn't crash on the notification handling).
        val pingResponse = process.request("ping", buildJsonObject {})
        assertNull(pingResponse["error"], "ping after notification must succeed: $pingResponse")
    }
}
