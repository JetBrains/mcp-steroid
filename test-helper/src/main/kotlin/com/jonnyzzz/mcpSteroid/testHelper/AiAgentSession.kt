/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.filter.AgentProgressOutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import java.io.File

interface AiAgentSession {
    val displayName: String

    /**
     * Run codex exec for non-interactive mode.
     */
    fun runPrompt(
        prompt: String,
        timeoutSeconds: Long = 120
    ): AiStartedProcess

    fun registerHttpMcp(mcpUrl: String, mcpName: String)

    fun registerNpxMcp(npxCommand: StdioMcpCommand, mcpName: String)

    /**
     * Ship the npx-kt installDist into the environment the agent runs in and
     * register it as a stdio MCP server under [mcpName].
     *
     * [installDir] is the local directory produced by `:npx-kt:installDist`
     * (e.g. `npx-kt/build/install/mcp-steroid-proxy`). The session decides
     * how to deliver it to the agent — for the Docker-backed sessions that's
     * a `docker cp` followed by [registerNpxMcp] pointing at the launcher
     * inside the container. Used by the
     * `Cli{Claude,Codex,Gemini}IntegrationTest` suite under `:npx-kt`.
     *
     * Centralising this in the session keeps the test code Docker-agnostic
     * (no `ContainerDriver` on the public surface) while letting the session
     * know what npx-kt is.
     */
    fun registerNpxKtMcp(installDir: File, mcpName: String)
}


interface AiStartedProcess : StartedProcess {
    val outputFilter: AgentProgressOutputFilter

    override fun awaitForProcessFinish(): AiProcessResult
}
