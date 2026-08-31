/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.ClaudeOutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
    private var mcpConfigJson: String? = null
    private var settingsFile: String? = null
    private val sessionEnv = mutableMapOf<String, String>()
    private val mcpRegistrationLog = mutableListOf<McpRegistration>()
    override val mcpRegistrations: List<McpRegistration>
        get() = mcpRegistrationLog.toList()
    override val strictMcpConfigJson: String?
        get() = mcpConfigJson

    override fun registerHttpMcp(mcpUrl: String, mcpName: String) {
        runInContainer(args = claudeMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0) { "MCP server registration" }
            .assertNoErrorsInOutput("MCP server registration")
        mcpRegistrationLog += McpRegistration(
            name = mcpName,
            transport = McpRegistrationTransport.HTTP,
            url = mcpUrl,
        )
        mcpConfigJson = claudeHttpMcpConfig(mcpUrl, mcpName)
    }

    override fun registerStdioMcp(command: StdioMcpCommand, mcpName: String) {
        runInContainer(args = claudeMcpAddStdioArgs(command, mcpName))
            .assertExitCode(0) { "devrig MCP server registration" }
            .assertNoErrorsInOutput("devrig MCP server registration")
        mcpRegistrationLog += McpRegistration(
            name = mcpName,
            transport = McpRegistrationTransport.STDIO,
            command = command,
        )
        mcpConfigJson = claudeStdioMcpConfig(command, mcpName)
    }

    override fun registerDevrigMcp(installDir: File, mcpName: String) {
        registerStdioMcp(session.installDevrigMcp(installDir), mcpName)
    }

    /**
     * Adds one environment variable to every later CLI invocation of this session.
     *
     * Exists because some CLI behaviour is reachable ONLY through the process environment, and a test
     * that needs it otherwise has to fork the whole launch path. The case in hand is
     * `ENABLE_TOOL_SEARCH`: with MCP tool schemas deferred, an experiment comparing "agent with IDE
     * tools" against "agent without" can silently run both arms without them.
     */
    fun withSessionEnv(key: String, value: String) {
        sessionEnv[key] = value
    }

    /**
     * Runs a Claude command inside the Docker container.
     * Debug mode is always enabled to see MCP connection details.
     *
     * [stdin], when given, is fed to the CLI on standard input instead of being placed on the command
     * line. That is the only way to pass anything large: the whole in-container command line reaches
     * `docker exec` as a single argument, and one argument is capped at 128 KiB on Linux.
     */
    fun runInContainer(
        args: List<String>,
        timeoutSeconds: Long = 120,
        stdin: String? = null,
    ): StartedProcess {
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
            // Route through a host-side Anthropic-compatible gateway when one is configured (no-op on CI).
            resolveContainerAgentBaseUrl("ANTHROPIC_BASE_URL")?.let { put("ANTHROPIC_BASE_URL", it) }
            if (debug) {
                put("CLAUDE_CODE_DEBUG", "1")
                put("DEBUG", "*")
            }
            putAll(sessionEnv)
        }

        return session.startProcessInContainer {
            this
                .args(claudeArgs)
                .let { req -> if (stdin == null) req else req.interactive().stdin(flowOf(stdin.toByteArray())) }
                .timeoutSeconds(timeoutSeconds)
                // The prompt no longer appears in the arguments, so the run log would show one
                // indistinguishable "Claude: --permission-mode ..." line per turn without this. The
                // description is host-side text and never reaches an exec, so length costs nothing here.
                .description(
                    "Claude: " + claudeArgs.joinToString(" ").take(80) +
                        (stdin?.let { " <<< " + it.lineSequence().first().take(120) } ?: "")
                )
                .secretPatterns(apiKey)
                // MERGE (addEnv), don't replace: the container driver may have pre-set env via withEnv
                // (e.g. DISPLAY from the xcvb GUI container). `.extraEnv(map)` would overwrite it, leaving
                // an agent that runs a GUI app (e.g. `devrig backend start` for the managed IDE) with no
                // DISPLAY. Folding addEnv keeps the driver's env and adds the agent's.
                .let { req -> env.entries.fold(req) { acc, (k, v) -> acc.addEnv(k, v) } }
        }
    }

    /**
     * Makes every later [runPrompt] run under [settingsJson] as an explicit Claude Code settings file,
     * so a test can register e.g. a `PostToolUse` hook for the session.
     *
     * The JSON is written into the container as a FILE for the same Windows quote-stripping reason the
     * MCP config is a file (see the note in [runPrompt]) — inline JSON loses its `"` characters.
     */
    fun useSettings(settingsJson: String) {
        val file = "/tmp/claude-settings.json"
        session.writeFileInContainer(file, settingsJson)
        settingsFile = file
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
        val mcpConfigFile = mcpConfigJson?.let { configJson ->
            // Write MCP config to a file to avoid Windows ProcessBuilder double-quote stripping.
            // Passing JSON inline as a bash -c arg strips all " characters on Windows
            // (CommandLineToArgvW interprets them as quote delimiters), so Claude CLI sees
            // the unquoted string as a file path and fails with "MCP config file not found".
            val configFile = "/tmp/claude-mcp-config.json"
            session.writeFileInContainer(configFile, configJson)
            configFile
        }
        val claudeArgs = claudeRunPromptArgs(
            model = model,
            mcpConfigFile = mcpConfigFile,
            settingsFile = settingsFile,
        )

        return runInContainer(
            args = claudeArgs,
            timeoutSeconds = timeoutSeconds,
            stdin = prompt,
        ).toAiStartedProcess()
    }

    companion object : AIAgentCompanion<DockerClaudeSession>("claude-cli") {
        /** Default Claude model for all test runs. Override via system property `claude.model`. */
        const val DEFAULT_MODEL = "claude-opus-5"

        override val displayName = "Claude Code"
        override val outputFilter get() = ClaudeOutputFilter()

        override val apiKeyHint = "set env ANTHROPIC_API_KEY, CLAUDE_EVAL_API_KEY, or ~/.anthropic"

        override fun readApiKey(): String? {
            (System.getenv("CLAUDE_EVAL_API_KEY") ?: System.getenv("ANTHROPIC_API_KEY"))?.takeIf { it.isNotBlank() }?.let { return it }
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

private val claudeMcpConfigJson = Json

private fun claudeHttpMcpConfig(serverUrl: String, serverName: String): String =
    encodeClaudeMcpConfig(serverName) {
        put("type", "http")
        put("url", serverUrl)
    }

private fun claudeStdioMcpConfig(command: StdioMcpCommand, serverName: String): String =
    encodeClaudeMcpConfig(serverName) {
        put("type", "stdio")
        put("command", command.command)
        putJsonArray("args") {
            command.args.forEach { add(it) }
        }
    }

private fun encodeClaudeMcpConfig(
    serverName: String,
    serverConfig: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): String {
    val config = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject(serverName) {
                serverConfig()
            }
        }
    }
    return claudeMcpConfigJson.encodeToString(JsonObject.serializer(), config)
}
