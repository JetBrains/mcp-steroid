/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies [claudeRunPromptArgs] — the pure command line behind
 * `DockerClaudeSession.runPrompt`. Running the Claude CLI costs an API key and a
 * container, so the argument ORDER (the part that silently breaks the run) is
 * asserted here instead of end-to-end.
 */
class ClaudePromptArgsTest {

    @Test
    fun `settings file is passed only when configured`() {
        val without = claudeRunPromptArgs("claude-opus-5", null, null, "hi")
        assertFalse(without.contains("--settings"))

        val with = claudeRunPromptArgs("claude-opus-5", null, "/tmp/s.json", "hi")
        assertEquals(
            listOf("--settings", "/tmp/s.json"),
            with.zipWithNext().first { it.first == "--settings" }.let { listOf(it.first, it.second) },
        )
    }

    @Test
    fun `prompt stays the last argument so nothing can be parsed as a flag`() {
        val args = claudeRunPromptArgs("claude-haiku-4-5", "/tmp/mcp.json", "/tmp/s.json", "--do-things")
        assertEquals("--do-things", args.last())
        assertEquals("-p", args[args.size - 2])
    }

    @Test
    fun `no mcp config means no mcp flags at all`() {
        val args = claudeRunPromptArgs("claude-opus-5", null, null, "hi")
        assertFalse(args.contains("--mcp-config"))
        assertFalse(args.contains("--strict-mcp-config"))
    }

    @Test
    fun `the mcp config file is passed strictly`() {
        val args = claudeRunPromptArgs("claude-opus-5", "/tmp/mcp.json", null, "hi")
        assertEquals(listOf("--mcp-config", "/tmp/mcp.json", "--strict-mcp-config"), args.subList(
            args.indexOf("--mcp-config"),
            args.indexOf("--strict-mcp-config") + 1,
        ))
    }

    @Test
    fun `the model is the one requested`() {
        val args = claudeRunPromptArgs("claude-haiku-4-5", null, null, "hi")
        assertEquals("claude-haiku-4-5", args[args.indexOf("--model") + 1])
    }

    /**
     * Locks the baseline command line: the settings seam must be a pure addition, so
     * `settingsFile = null` has to reproduce the pre-seam argument list byte for byte.
     */
    @Test
    fun `without a settings file the command line is unchanged`() {
        assertEquals(
            listOf(
                "--permission-mode", "bypassPermissions",
                "--model", "claude-opus-5",
                "--tools", "default",
                "--input-format", "text",
                "--output-format", "stream-json",
                "--verbose",
                "--mcp-config", "/tmp/claude-mcp-config.json",
                "--strict-mcp-config",
                "-p", "do things",
            ),
            claudeRunPromptArgs("claude-opus-5", "/tmp/claude-mcp-config.json", null, "do things"),
        )
    }

    @Test
    fun `the settings file precedes the prompt`() {
        val args = claudeRunPromptArgs("claude-opus-5", "/tmp/mcp.json", "/tmp/s.json", "hi")
        assertTrue(args.indexOf("--settings") < args.indexOf("-p")) { "settings must not land after -p: $args" }
        assertEquals(
            listOf("--strict-mcp-config", "--settings", "/tmp/s.json", "-p", "hi"),
            args.takeLast(5),
        )
    }
}
