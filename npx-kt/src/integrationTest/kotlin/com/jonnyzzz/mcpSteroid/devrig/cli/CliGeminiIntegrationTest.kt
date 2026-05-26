/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession

/**
 * Drives the devrig stdio MCP server end-to-end with the Gemini CLI as the
 * MCP client. Mirrors the shape of `:ij-plugin`'s `CliGeminiIntegrationTest`
 * but for the devrig launcher rather than the in-IDE HTTP MCP server.
 *
 * Requires:
 *  - Docker running locally
 *  - `GEMINI_API_KEY` (env or TC `%credentialsJSON:…%`)
 *
 * Skipped (not failed) when the key is missing on hosts that don't have one
 * configured — the CLAUDE.md "Gemini API key on CI" exception applies here
 * via `DockerGeminiSession.skipTestWhenKeyMissing = true`.
 */
class CliGeminiIntegrationTest : CliMcpAgentIntegrationTestBase() {
    override fun createAiSession(): AiAgentSession = DockerGeminiSession.create(lifetime)
    // Gemini's first MCP cold-start is the slowest of the three.
    override val promptTimeoutSeconds: Long = 300

    // The IDE's JUnit test runner only picks up `@Test` methods declared on
    // the concrete class — overrides are required for individual-test runs to
    // appear in the gutter / Run-anything UI.
    override fun `agent registers devrig as stdio MCP and lists steroid_ tools`() {
        super.`agent registers devrig as stdio MCP and lists steroid_ tools`()
    }
}
