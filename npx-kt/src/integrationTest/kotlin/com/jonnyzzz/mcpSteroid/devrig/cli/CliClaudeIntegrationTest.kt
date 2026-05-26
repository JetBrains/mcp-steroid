/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession

/**
 * Drives the devrig stdio MCP server end-to-end with Claude Code CLI as the
 * MCP client. Mirrors the shape of `:ij-plugin`'s `CliClaudeIntegrationTest`
 * but for the devrig launcher rather than the in-IDE HTTP MCP server.
 *
 * Requires:
 *  - Docker running locally
 *  - `ANTHROPIC_API_KEY` (env or `~/.anthropic`)
 *
 * Skipped automatically when the API key isn't available, per
 * `AIAgentCompanion.requireApiKey()`.
 */
class CliClaudeIntegrationTest : CliMcpAgentIntegrationTestBase() {
    override fun createAiSession(): AiAgentSession = DockerClaudeSession.create(lifetime)

    // The IDE's JUnit test runner only picks up `@Test` methods declared on
    // the concrete class — overrides are required for individual-test runs to
    // appear in the gutter / Run-anything UI.
    override fun `agent registers devrig as stdio MCP and lists steroid_ tools`() {
        super.`agent registers devrig as stdio MCP and lists steroid_ tools`()
    }
}
