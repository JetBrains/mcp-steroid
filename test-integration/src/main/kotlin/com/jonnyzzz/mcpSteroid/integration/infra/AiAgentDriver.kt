/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.AIAgentCompanion
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File
import kotlin.getValue

/**
 * Determines which MCP transport is registered with agents when they are created.
 *
 * Set via [AiMode] on [IntelliJContainer.create]; the factory translates the mode
 * to the appropriate [McpConnectionMode] before constructing [AiAgentDriver].
 */
sealed class McpConnectionMode {
    /** Agents are available but MCP Steroid is NOT registered. */
    data object None : McpConnectionMode()

    /** Agents connect to MCP Steroid via direct HTTP. */
    data object Http : McpConnectionMode()

    /** Agents connect to MCP Steroid via an NPX stdio proxy. */
    data class Npx(val driver: NpxSteroidDriver) : McpConnectionMode()
}

/**
 * Manages AI agent sessions (Claude, Codex, Gemini) within an IntelliJ test container.
 *
 * Each agent is wrapped in [ConsoleAwareAgentSession] so all agent runs produce
 * real-time console output and write per-run log files to [logDir]:
 *  - `agent-{name}-{N}-raw.ndjson`   — raw NDJSON lines from STDOUT
 *  - `agent-{name}-{N}-decoded.txt`  — human-readable decoded output
 *
 * MCP Steroid connectivity is determined by [mcpConnection]:
 * - [McpConnectionMode.None]  — no MCP registered (baseline / control group)
 * - [McpConnectionMode.Http]  — HTTP transport ([AiMode.AI_MCP])
 * - [McpConnectionMode.Npx]   — NPX stdio proxy ([AiMode.AI_NPX])
 */
class AiAgentDriver(
    container: ContainerDriver,
    private val intellijDriver: IntelliJDriver,
    private val mcp: McpSteroidDriver,
    private val console: ConsoleDriver,
    private val mcpConnection: McpConnectionMode = McpConnectionMode.Http,
    private val logDir: File,
) {
    private val container by lazy {
        //TODO: Workdir in the container is not set for the agents!
        container.configureContainerExec { this.workingDirInContainer(intellijDriver.getGuestProjectDir()) }
    }

    val mcpSteroidHostUrl by mcp::hostMcpUrl
    val mcpSteroidGuestUrl by mcp::guestMcpUrl
    val mcpSteroidName: String = "mcp-steroid"

    private fun <R : AiAgentSession> prepareAIAgent(factory: AIAgentCompanion<R>): AiAgentSession {
        val agent: AiAgentSession = factory.create(container)
        val displayName: String = factory.displayName

        when (val conn = mcpConnection) {
            is McpConnectionMode.None -> { /* no MCP registered */ }
            is McpConnectionMode.Http -> agent.registerHttpMcp(mcpSteroidGuestUrl, mcpSteroidName)
            is McpConnectionMode.Npx -> agent.registerNpxMcp(conn.driver.npxCommand, mcpSteroidName)
        }

        // Wrap with console-aware session for real-time UI feedback and log file writing
        return ConsoleAwareAgentSession(agent, console, displayName, logDir)
    }

    val aiAgents: Map<String, AiAgentSession> by lazy {
        buildMap {
            put("claude", claude)
            put("codex", codex)
            put("gemini", gemini)
        }
    }

    val claude by lazy {
        prepareAIAgent(DockerClaudeSession)
    }

    val codex by lazy {
        prepareAIAgent(DockerCodexSession)
    }

    val gemini by lazy {
        prepareAIAgent(DockerGeminiSession)
    }
}
