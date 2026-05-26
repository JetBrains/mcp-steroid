/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

enum class AiAgentCli(
    val binary: String,
    val displayName: String,
) {
    CLAUDE("claude", "Claude"),
    CODEX("codex", "Codex"),
    GEMINI("gemini", "Gemini");

    fun mcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> = when (this) {
        CLAUDE -> claudeMcpAddStdioArgs(command, serverName)
        CODEX -> codexMcpAddStdioArgs(command, serverName)
        GEMINI -> geminiMcpAddStdioArgs(command, serverName)
    }

    companion object {
        fun parse(value: String): AiAgentCli? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.binary == value.lowercase() }
    }
}

data class AiAgentCliInvocation(
    val binary: String,
    val args: List<String>,
)

data class AiAgentCliResult(
    val exitCode: Int,
    val output: String,
)

fun interface AiAgentCliRunner {
    fun run(invocation: AiAgentCliInvocation): AiAgentCliResult
}

class ProcessAiAgentCliRunner : AiAgentCliRunner {
    override fun run(invocation: AiAgentCliInvocation): AiAgentCliResult {
        val process = ProcessBuilder(listOf(invocation.binary) + invocation.args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return AiAgentCliResult(exitCode, output)
    }
}

fun mcpAddStdioInvocation(
    agent: AiAgentCli,
    command: StdioMcpCommand,
    serverName: String = DEFAULT_SERVER_NAME,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpAddStdioArgs(command, serverName),
    )
