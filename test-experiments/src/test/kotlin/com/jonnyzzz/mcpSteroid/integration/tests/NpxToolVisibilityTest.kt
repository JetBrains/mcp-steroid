/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.shellEscape
import com.jonnyzzz.mcpSteroid.testHelper.docker.ExecContainerProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.prepareNpxProxyForUrl
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Acceptance test verifying that ALL MCP tools are visible through the NPX proxy.
 *
 * This is a pure protocol-level test: no AI agent, no prompt execution.
 * It starts IntelliJ + MCP Steroid in Docker, deploys the NPX proxy with a
 * fake marker file, then sends JSON-RPC initialize + tools/list via stdin
 * to the NPX proxy process and validates the response.
 *
 * Run:
 *   ./gradlew :test-experiments:test --tests '*NpxToolVisibilityTest*'
 */
class NpxToolVisibilityTest {

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * All 8 IDE tools that must be visible through the NPX proxy.
         */
        private val EXPECTED_IDE_TOOLS = setOf(
            "steroid_list_projects",
            "steroid_list_windows",
            "steroid_execute_code",
            "steroid_execute_feedback",
            "steroid_action_discovery",
            "steroid_take_screenshot",
            "steroid_input",
            "steroid_open_project",
        )
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `all IDE tools visible through NPX proxy and no proxy_ tools`() = runWithCloseableStack { lifetime ->
        // 1. Start IntelliJ container with MCP Steroid plugin
        val session = IntelliJContainer.create(lifetime, "ide-agent", consoleTitle = "NPX Visibility")
        val container = session.scope
        val console = session.console

        console.writeStep(1, "Deploying NPX proxy in container")

        // 2. Deploy NPX proxy pointed at the IDE's MCP server
        val userHome = "/home/agent"
        val npxCommand = container.prepareNpxProxyForUrl(
            ideMcpUrl = session.mcpSteroid.guestMcpUrl,
            userHome = userHome,
        )

        console.writeStep(2, "Sending JSON-RPC initialize + tools/list to NPX proxy")

        // 3. Build JSON-RPC messages in NDJSON format (one JSON per line)
        val initializeRequest = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"npx-visibility-test","version":"1.0"}}}"""
        val toolsListRequest = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""

        // Pipe both messages via stdin to the NPX proxy process.
        // The proxy auto-detects NDJSON mode from the first `{` character.
        // When stdin closes (printf completes), the proxy exits.
        val npxArgs = buildList {
            add(npxCommand.command)
            addAll(npxCommand.args)
        }
        val npxCmdStr = npxArgs.joinToString(" ") { shellEscape(it) }

        // Use printf to send NDJSON lines, pipe to node process.
        // Add a small sleep before EOF so the proxy has time to process both requests.
        val script = "{ printf '%s\\n%s\\n' ${shellEscape(initializeRequest)} ${shellEscape(toolsListRequest)}; sleep 2; } | $npxCmdStr 2>/dev/null"

        val result = container.startProcessInContainer {
            this
                .args("bash", "-c", script)
                .timeoutSeconds(60)
                .description("npx proxy tools list")
        }.awaitForProcessFinish()

        println("[NPX-VISIBILITY] Exit code: ${result.exitCode}")
        println("[NPX-VISIBILITY] Output (${result.stdout.length} chars):\n${result.stdout}")

        // 4. Parse NDJSON output — each line is a JSON-RPC response
        val responses = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                try {
                    json.parseToJsonElement(line).jsonObject
                } catch (_: Exception) {
                    null
                }
            }
            .toList()

        assertTrue(responses.isNotEmpty(), "NPX proxy should produce JSON-RPC responses, got: ${result.stdout}")

        // Find the tools/list response (id=2)
        val toolsResponse = responses.find { obj ->
            obj["id"]?.jsonPrimitive?.contentOrNull == "2"
        } ?: error("No tools/list response (id=2) found in NPX output. Responses: $responses")

        // 5. Extract tool names from the response
        val toolsArray = toolsResponse["result"]?.jsonObject?.get("tools")?.jsonArray
            ?: error("tools/list response missing result.tools: $toolsResponse")

        val toolNames = toolsArray.map { tool ->
            tool.jsonObject["name"]?.jsonPrimitive?.content
                ?: error("Tool entry missing 'name': $tool")
        }.toSet()

        println("[NPX-VISIBILITY] Discovered ${toolNames.size} tools: $toolNames")
        console.writeSuccess("Discovered ${toolNames.size} tools through NPX proxy")

        // 6. Assert ALL IDE tools are visible through the proxy
        console.writeStep(3, "Verifying tool visibility")
        val missingTools = EXPECTED_IDE_TOOLS - toolNames
        assertTrue(
            missingTools.isEmpty(),
            "IDE tools missing from NPX proxy: $missingTools. Available: $toolNames"
        )

        // 7. Assert NO proxy_ prefixed tools leaked through
        val leakedProxyTools = toolNames.filter { it.startsWith("proxy_") }
        assertTrue(
            leakedProxyTools.isEmpty(),
            "NPX must not expose proxy_ tools: $leakedProxyTools"
        )

        // 8. Verify initialize response is also valid (id=1)
        val initResponse = responses.find { obj ->
            obj["id"]?.jsonPrimitive?.contentOrNull == "1"
        }
        assertTrue(initResponse != null, "No initialize response (id=1) found")
        val serverInfo = initResponse!!["result"]?.jsonObject?.get("serverInfo")?.jsonObject
        assertTrue(serverInfo != null, "Initialize response should contain serverInfo")
        println("[NPX-VISIBILITY] Server info: $serverInfo")

        console.writeSuccess("All ${EXPECTED_IDE_TOOLS.size} IDE tools visible, no proxy_ tools")
    }
}
