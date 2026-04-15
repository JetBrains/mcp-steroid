/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.ClaudeOutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import java.io.File

/**
 * Manages a Claude CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Claude config.
 */
class DockerClaudeSession(
    private val session: ContainerDriver,
    private val apiKey: String,
    private val debug: Boolean = false,
    val model: String = DEFAULT_MODEL,
) : AiAgentSession {
    override val displayName: String = Companion.displayName

    override fun registerHttpMcp(mcpUrl: String, mcpName: String) {
        runInContainer(args = claudeMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0) { "MCP server registration" }
            .assertNoErrorsInOutput("MCP server registration")
    }

    override fun registerNpxMcp(npxCommand: StdioMcpCommand, mcpName: String) {
        runInContainer(args = claudeMcpAddStdioArgs(npxCommand, mcpName))
            .assertExitCode(0) { "NPX MCP server registration" }
            .assertNoErrorsInOutput("NPX MCP server registration")
    }

    /**
     * Runs a Claude command inside the Docker container.
     * Debug mode is always enabled to see MCP connection details.
     */
    fun runInContainer(args: List<String>, timeoutSeconds: Long = 120): StartedProcess {
        val claudeArgs = buildList {
            add("claude")
            if (debug) {
                add("--debug")
                add("--mcp-debug")
                add("--verbose")
            }
            addAll(args)
        }
        val env = buildMap {
            put("ANTHROPIC_API_KEY", apiKey)
            if (debug) {
                put("CLAUDE_CODE_DEBUG", "1")
                put("DEBUG", "*")
            }
        }

        return session.startProcessInContainer {
            this
                .args(claudeArgs)
                .timeoutSeconds(timeoutSeconds)
                .description("Claude: " + claudeArgs.joinToString(" ").take(80))
                .secretPatterns(apiKey)
                .extraEnv(env)
        }
    }

    /**
     * Runs Claude in non-interactive mode with a prompt.
     *
     * Uses `--output-format stream-json --verbose` so that tool calls, assistant
     * messages, and progress events stream to stdout in real time (instead of only
     * the final text response appearing at the end). The raw NDJSON output is
     * post-processed via [ClaudeOutputFilter] to produce human-readable text.
     *
     * @param prompt The prompt to send to Claude
     * @param timeoutSeconds Maximum time to wait for the command
     */
    override fun runPrompt(
        prompt: String,
        timeoutSeconds: Long,
    ): AiStartedProcess {
        val claudeArgs = buildList {
            add("--permission-mode")
            add("bypassPermissions")
            add("--model")
            add(model)
            add("--tools")
            add("default")
            add("--input-format")
            add("text")
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("-p")
            add(prompt)
        }

        return runInContainer(
            args = claudeArgs,
            timeoutSeconds = timeoutSeconds
        ).toAiStartedProcess()
    }

    companion object : AIAgentCompanion<DockerClaudeSession>("claude-cli") {
        /** Default Claude model for all test runs. Override via system property `claude.model`. */
        const val DEFAULT_MODEL = "claude-opus-4-6"

        override val displayName = "Claude Code"
        override val outputFilter get() = ClaudeOutputFilter()

        override val apiKeyHint = "set env ANTHROPIC_API_KEY or ~/.anthropic"

        override fun readApiKey(): String? {
            System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".anthropic")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            return null
        }

        override fun createImpl(session: ContainerDriver, apiKey: String): DockerClaudeSession {
            val model = System.getProperty("claude.model", DEFAULT_MODEL)
            return DockerClaudeSession(session, apiKey, model = model)
        }
    }
}
