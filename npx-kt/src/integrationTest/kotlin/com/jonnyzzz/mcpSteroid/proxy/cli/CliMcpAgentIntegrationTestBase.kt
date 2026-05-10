/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.cli

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.NpxKtMcpInstaller
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Shared body for the npx-kt agent CLI integration tests
 * (Claude / Codex / Gemini variants).
 *
 * Each variant:
 *  1. Spins up its corresponding agent Docker container via the test-helper
 *     AISessionBase machinery (the agent containers now bundle a JRE — see
 *     `test-helper/src/main/docker/{claude,codex,gemini}-cli/Dockerfile`).
 *  2. Copies the npx-kt `installDist` tree into the container.
 *  3. Registers it as a stdio MCP via the agent CLI's MCP registration command.
 *  4. Runs a "list MCP tools" prompt and asserts the agent saw the steroid_*
 *     surface that [com.jonnyzzz.mcpSteroid.proxy.server.StubMcpSteroidTools]
 *     registers.
 *
 * Tool *invocations* are intentionally not exercised — every handler in
 * [com.jonnyzzz.mcpSteroid.proxy.server.StubMcpSteroidTools] throws
 * `UnsupportedOperationException("not yet ready: …")`, which the registry
 * surfaces as `ToolCallResult(isError=true)`. Wiring real handlers is a
 * separate milestone.
 */
abstract class CliMcpAgentIntegrationTestBase {

    protected val lifetime = CloseableStackHost()

    @AfterEach
    fun tearDownLifetime() {
        lifetime.closeAllStacks()
    }

    /** Construct a session bound to [lifetime]. Each subclass picks its own AI CLI. */
    protected abstract fun createAiSession(): AiAgentSession

    /**
     * Pin the prompt-running timeout per agent. Defaults to 240 s, which covers
     * the slowest agent flow we've seen (Gemini, MCP cold-start).
     */
    protected open val promptTimeoutSeconds: Long = 240

    @Test
    open fun `agent registers npx-kt as stdio MCP and lists steroid_ tools`() {
        val session = createAiSession()
        // The session knows how to ship the npx-kt installDist into wherever
        // its agent runs and register the launcher as a stdio MCP. No
        // ContainerDriver leak — the Docker plumbing lives behind the session.
        session.registerNpxKtMcp(
            installDir = NpxKtMcpInstaller.resolveInstallDir(),
            mcpName = "mcp-steroid-proxy",
        )

        val result = session.runPrompt(
            prompt = """
                You are validating an MCP integration. The MCP server "mcp-steroid-proxy"
                is registered with you over stdio. Use only THAT MCP server for tool
                discovery. Do NOT call any of its tools — they are not yet implemented and
                will throw errors.

                Do this:
                1. Enumerate all MCP tools whose name starts with "steroid_".
                2. For each, print one line in the form: TOOL: <name>

                After listing every tool, print a final line: DONE_LISTING

                Output plain text only. No markdown, no bold, no code blocks. Do not call
                any tool other than the discovery ones.
            """.trimIndent(),
            timeoutSeconds = promptTimeoutSeconds,
        ).awaitForProcessFinish()
            .assertExitCode(0) { "[${session.displayName}] prompt failed with exit ${exitCode}: $stderr" }

        val combined = result.stdout + "\n" + result.stderr

        // Assert the agent enumerated EVERY tool registered by
        // McpSteroidTools.registerAll (shared with CliMcpStdioIntegrationTest).
        // A partial set would otherwise silently pass — agents have been known
        // to truncate "long" listings.
        val missing = EXPECTED_STEROID_TOOL_NAMES.filterNot { name ->
            // "TOOL: <name>" is the format the prompt asks for; check both
            // exact form and the bare name to tolerate light formatting drift.
            combined.contains("TOOL: $name") || combined.lineSequence().any { line ->
                line.trim().endsWith(name) && "TOOL" in line
            }
        }
        check(missing.isEmpty()) {
            "[${session.displayName}] agent did not enumerate every steroid_* tool. " +
                    "missing=$missing\nstdout=\n${result.stdout}\nstderr=\n${result.stderr}"
        }

        // Sanity: the agent should NOT report a connection / handshake failure.
        // Stdout is for the agent's own narrative; stderr can carry Claude-CLI
        // debug noise — we only assert against stdout to avoid flaking on
        // unrelated agent CLI output.
        check(!result.stdout.contains("MCP server failed to connect", ignoreCase = true)) {
            "[${session.displayName}] agent reported an MCP connection failure.\n$combined"
        }
    }

}
