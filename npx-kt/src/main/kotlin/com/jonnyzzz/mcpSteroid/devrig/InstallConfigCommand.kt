/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.DEFAULT_SERVER_NAME
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.stdioMcpServersJson

/**
 * `devrig install config` — print the MANUAL MCP configuration recipe for devrig (issue #398): the
 * stdio `mcpServers` JSON snippet pointing at the stable `~/.mcp-steroid/bin` launcher, plus the
 * per-agent `mcp add` command lines. For MCP clients devrig cannot configure automatically (Cursor,
 * Windsurf, any mcp.json-style config). Informational and read-only: nothing is registered, nothing
 * is written — the recipe goes to stdout so it can be piped/copied.
 */
fun DevrigServices.runInstallConfigCommand(): Int {
    mcpStdout.print(renderInstallConfig(DevrigUserLauncher.invocation(homePaths, listOf("mcp"))))
    return 0
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
