/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.GeminiOutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import java.io.File

/**
 * Manages a Gemini CLI session running inside a Docker container.
 */
class DockerGeminiSession(
    private val session: ContainerDriver,
    private val apiKey: String,
    private val debug: Boolean = false,
) : AiAgentSession {
    override val displayName: String = Companion.displayName

    override fun registerHttpMcp(mcpUrl: String, mcpName: String) {
        runInContainer(args = geminiMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0) { "MCP server registration" }
            .assertNoErrorsInOutput(message = "MCP server registration")
    }

    override fun registerNpxMcp(npxCommand: StdioMcpCommand, mcpName: String) {
        runInContainer(args = geminiMcpAddStdioArgs(npxCommand, mcpName))
            .assertExitCode(0) { "NPX MCP server registration" }
            .assertNoErrorsInOutput(message = "NPX MCP server registration")
    }

    fun runInContainer(args: List<String>, timeoutSeconds: Long = 120): StartedProcess {
        val geminiArgs = buildList {
            add("gemini")
            if (debug) {
                add("--debug")
            }
            addAll(args.toList())
        }
        val env = buildMap {
            put("GEMINI_API_KEY", apiKey)
            if (debug) {
                put("GEMINI_DEBUG", "1")
                put("MCP_DEBUG", "1")
                put("DEBUG", "*")
            }
        }

        return session.startProcessInContainer {
            this
                .args(geminiArgs)
                .timeoutSeconds(timeoutSeconds = timeoutSeconds)
                .description(geminiArgs.joinToString(" ").take(80))
                .secretPatterns(apiKey)
                .extraEnv(env)
        }
    }

    /**
     * Run Gemini in non-interactive mode with stream-json output enabled.
     *
     * Primary flags:
     * `--screen-reader true --sandbox-mode none --approval-mode yolo --output-format stream-json --prompt <prompt>`.
     *
     * Newer Gemini CLI versions replaced `--sandbox-mode none` with `--sandbox false`.
     * We retry once with modern syntax when the legacy flag is rejected.
     *
     * The raw NDJSON output is post-processed via [GeminiOutputFilter] to produce
     * human-readable text.
     */
    override fun runPrompt(prompt: String, timeoutSeconds: Long): AiStartedProcess {
        val args = listOf(
            "--screen-reader", "true",
            "--approval-mode", "yolo",
            "--output-format", "stream-json",
            "--prompt", prompt,
        )

        return runInContainer(
            args = args,
            timeoutSeconds = timeoutSeconds
        ).toAiStartedProcess()
    }

    companion object : AIAgentCompanion<DockerGeminiSession>("gemini-cli") {
        override val displayName = "Gemini"
        override val outputFilter get() = GeminiOutputFilter()

        override val apiKeyHint = "set env GEMINI_API_KEY, GOOGLE_API_KEY, or ~/.vertex"

        override fun readApiKey(): String? {
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            System.getenv("GOOGLE_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".vertex")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            return null
        }

        override fun createImpl(session: ContainerDriver, apiKey: String): DockerGeminiSession {
            return DockerGeminiSession(session, apiKey)
        }
    }
}
