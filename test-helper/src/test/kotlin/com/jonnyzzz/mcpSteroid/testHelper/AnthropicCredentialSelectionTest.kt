/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure [selectAnthropicCredential] selection function used by
 * [DockerClaudeSession] to choose between a raw API key (`x-api-key`) and an OAuth
 * bearer token (`Authorization: Bearer`, forwarded as `ANTHROPIC_AUTH_TOKEN`).
 *
 * Precedence (documented on the function): eval key / API key first (unchanged CI
 * behavior), then the OAuth bearer token, then the `~/.anthropic` file content
 * (treated as a raw API key), then null.
 */
class AnthropicCredentialSelectionTest {

    @Test
    fun `api key only selects ANTHROPIC_API_KEY`() {
        val credential = selectAnthropicCredential(
            evalApiKey = null,
            anthropicApiKey = "sk-ant-key",
            anthropicAuthToken = null,
            anthropicFileContent = null,
        )
        assertEquals(AnthropicCredential("sk-ant-key", "ANTHROPIC_API_KEY"), credential)
    }

    @Test
    fun `eval key takes precedence over api key`() {
        val credential = selectAnthropicCredential(
            evalApiKey = "eval-key",
            anthropicApiKey = "sk-ant-key",
            anthropicAuthToken = null,
            anthropicFileContent = null,
        )
        assertEquals(AnthropicCredential("eval-key", "ANTHROPIC_API_KEY"), credential)
    }

    @Test
    fun `only auth token selects ANTHROPIC_AUTH_TOKEN`() {
        val credential = selectAnthropicCredential(
            evalApiKey = null,
            anthropicApiKey = null,
            anthropicAuthToken = "oauth-bearer-token",
            anthropicFileContent = null,
        )
        assertEquals(AnthropicCredential("oauth-bearer-token", "ANTHROPIC_AUTH_TOKEN"), credential)
    }

    @Test
    fun `api key wins over auth token when both present`() {
        val credential = selectAnthropicCredential(
            evalApiKey = null,
            anthropicApiKey = "sk-ant-key",
            anthropicAuthToken = "oauth-bearer-token",
            anthropicFileContent = null,
        )
        assertEquals(AnthropicCredential("sk-ant-key", "ANTHROPIC_API_KEY"), credential)
    }

    @Test
    fun `only file content selects ANTHROPIC_API_KEY`() {
        val credential = selectAnthropicCredential(
            evalApiKey = null,
            anthropicApiKey = null,
            anthropicAuthToken = null,
            anthropicFileContent = "file-key",
        )
        assertEquals(AnthropicCredential("file-key", "ANTHROPIC_API_KEY"), credential)
    }

    @Test
    fun `nothing present returns null`() {
        val credential = selectAnthropicCredential(
            evalApiKey = null,
            anthropicApiKey = null,
            anthropicAuthToken = null,
            anthropicFileContent = null,
        )
        assertNull(credential)
    }

    @Test
    fun `blank strings are treated as absent`() {
        val credential = selectAnthropicCredential(
            evalApiKey = "   ",
            anthropicApiKey = "",
            anthropicAuthToken = "  ",
            anthropicFileContent = "",
        )
        assertNull(credential)
    }

    @Test
    fun `blank eval key does not skip a valid api key`() {
        // A present-but-blank CLAUDE_EVAL_API_KEY (common when CI templates default-set the
        // var) must NOT null out a valid ANTHROPIC_API_KEY and fall through to the bearer token.
        val credential = selectAnthropicCredential(
            evalApiKey = "",
            anthropicApiKey = "sk-ant-key",
            anthropicAuthToken = null,
            anthropicFileContent = null,
        )
        assertEquals(AnthropicCredential("sk-ant-key", "ANTHROPIC_API_KEY"), credential)
    }

    @Test
    fun `blank api key does not block the auth token`() {
        val credential = selectAnthropicCredential(
            evalApiKey = "",
            anthropicApiKey = "",
            anthropicAuthToken = "sk-ant-oat-token",
            anthropicFileContent = null,
        )
        assertEquals(AnthropicCredential("sk-ant-oat-token", "ANTHROPIC_AUTH_TOKEN"), credential)
    }
}
