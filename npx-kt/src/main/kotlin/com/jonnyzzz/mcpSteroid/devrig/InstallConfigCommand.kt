/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.DEFAULT_SERVER_NAME
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.stdioMcpServersJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val installConfigJson = Json { prettyPrint = true }

/**
 * `devrig install config` — print the MANUAL MCP configuration recipe for devrig (issue #398): the
 * stdio `mcpServers` JSON snippet pointing at the stable `~/.mcp-steroid/bin` launcher, plus the
 * per-agent `mcp add` command lines. For MCP clients devrig cannot configure automatically (Cursor,
 * Windsurf, any mcp.json-style config). Informational and read-only: nothing is registered, nothing
 * is written — the recipe goes to stdout so it can be piped/copied.
 */
fun DevrigServices.runInstallConfigCommand(json: Boolean): Int {
    val invocation = DevrigUserLauncher.invocation(homePaths, listOf("mcp"))
    if (json) {
        mcpStdout.println(renderInstallConfigJson(invocation))
    } else {
        mcpStdout.print(renderInstallConfig(invocation))
    }
    return 0
}

fun renderInstallConfigJson(mcpCommand: StdioMcpCommand): String {
    val mcpServers = Json.parseToJsonElement(stdioMcpServersJson(mcpCommand)).jsonObject["mcpServers"]
        ?: error("generated MCP configuration has no mcpServers object")
    val payload = buildJsonObject {
        put("serverName", DEFAULT_SERVER_NAME)
        put("mcpServers", mcpServers)
        putJsonObject("agentCommands") {
            for (agent in AiAgentCli.entries) {
                putJsonArray(agent.binary) {
                    for (token in listOf(agent.binary) + agent.mcpAddStdioArgs(mcpCommand)) add(JsonPrimitive(token))
                }
            }
        }
    }
    return installConfigJson.encodeToString(JsonObject.serializer(), payload)
}

/**
 * Pure renderer of the manual-configuration recipe. [mcpCommand] is the OS-correct launcher invocation
 * ([DevrigUserLauncher.invocation] with the `mcp` subcommand) — the SAME command `devrig install <agent>`
 * registers, so the manual and automatic paths can never drift apart. The JSON block comes from
 * [stdioMcpServersJson] (kotlinx.serialization — a Windows launcher path needs its backslashes escaped).
 */
fun renderInstallConfig(mcpCommand: StdioMcpCommand): String = buildString {
    appendLine("Manual MCP configuration — register devrig as the '$DEFAULT_SERVER_NAME' stdio MCP server.")
    appendLine()
    appendLine("For MCP clients configured through an 'mcpServers' JSON file (mcp.json or similar), add:")
    appendLine()
    appendLine(stdioMcpServersJson(mcpCommand))
    appendLine()
    appendLine("Agents with a CLI are configured automatically by 'devrig install claude|codex|gemini',")
    appendLine("or with the agent's own command:")
    for (agent in AiAgentCli.entries) {
        appendLine("  ${agent.binary} ${agent.mcpAddStdioArgs(mcpCommand).joinToString(" ")}")
    }
}
