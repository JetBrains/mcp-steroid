/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.cli

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession

/**
 * Drives the npx-kt stdio MCP server end-to-end with the Codex CLI as the
 * MCP client. Mirrors the shape of `:ij-plugin`'s `CliCodexIntegrationTest`
 * but for the npx-kt proxy launcher rather than the in-IDE HTTP MCP server.
 *
 * Requires:
 *  - Docker running locally
 *  - `OPENAI_API_KEY` (env or `~/.openai`)
 *
 * Skipped automatically when the API key isn't available, per
 * `AIAgentCompanion.requireApiKey()`.
 */
class CliCodexIntegrationTest : CliMcpAgentIntegrationTestBase() {
    override fun createAiSession(): AiAgentSession = DockerCodexSession.create(lifetime)

    // The IDE's JUnit test runner only picks up `@Test` methods declared on
    // the concrete class — overrides are required for individual-test runs to
    // appear in the gutter / Run-anything UI.
    override fun `agent registers npx-kt as stdio MCP and lists steroid_ tools`() {
        super.`agent registers npx-kt as stdio MCP and lists steroid_ tools`()
    }
}
