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
 *
 * The prompt is deliberately absent from the command line and travels on stdin, so the assertion that
 * matters most is that NO argument carries prompt text: a single `docker exec` argument is capped at
 * 128 KiB, and a repair prompt that carries the compiler output plus every failing file crosses it.
 */
class ClaudePromptArgsTest {

    @Test
    fun `settings file is passed only when configured`() {
        val without = claudeRunPromptArgs("claude-opus-5", null, null)
        assertFalse(without.contains("--settings"))

        val with = claudeRunPromptArgs("claude-opus-5", null, "/tmp/s.json")
        assertEquals(
            listOf("--settings", "/tmp/s.json"),
            with.zipWithNext().first { it.first == "--settings" }.let { listOf(it.first, it.second) },
        )
    }

    @Test
    fun `the prompt is on none of the arguments and -p stays a bare flag`() {
        val args = claudeRunPromptArgs("claude-haiku-4-5", "/tmp/mcp.json", "/tmp/s.json")
        assertEquals("-p", args.last()) { "-p must stay last so nothing lands behind it: $args" }
        assertEquals(1, args.count { it == "-p" })
    }

    @Test
    fun `no mcp config means no mcp flags at all`() {
        val args = claudeRunPromptArgs("claude-opus-5", null, null)
        assertFalse(args.contains("--mcp-config"))
        assertFalse(args.contains("--strict-mcp-config"))
    }

    @Test
    fun `the mcp config file is passed strictly`() {
        val args = claudeRunPromptArgs("claude-opus-5", "/tmp/mcp.json", null)
        assertEquals(listOf("--mcp-config", "/tmp/mcp.json", "--strict-mcp-config"), args.subList(
            args.indexOf("--mcp-config"),
            args.indexOf("--strict-mcp-config") + 1,
        ))
    }

    @Test
    fun `the model is the one requested`() {
        val args = claudeRunPromptArgs("claude-haiku-4-5", null, null)
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
                "-p",
            ),
            claudeRunPromptArgs("claude-opus-5", "/tmp/claude-mcp-config.json", null),
        )
    }

    /**
     * The regression this whole shape exists for. `docker exec <id> bash -c '<everything>'` hands the
     * kernel one argument, and Linux caps a single argument at `MAX_ARG_STRLEN` = 128 KiB no matter how
     * much total argv space is available. While the prompt sat on the command line, a repair turn
     * carrying the compiler output plus every failing file blew past that and the exec died with
     * `E2BIG` — after the agent had already spent its budget, so the run was lost rather than merely
     * failed. With the prompt on stdin the command line is bounded by the flags alone, and this asserts
     * that bound rather than trusting it.
     */
    @Test
    fun `the assembled command line cannot approach the single-argument limit`() {
        val commandLine = escapeShellArgs(
            listOf("claude") + claudeRunPromptArgs("claude-opus-5", "/tmp/mcp.json", "/tmp/s.json")
        )
        assertTrue(commandLine.length < 1_024) {
            "the in-container command line is ${commandLine.length} chars; anything that grows with the " +
                "prompt belongs on stdin, not here: $commandLine"
        }
    }

    @Test
    fun `the settings file precedes the print flag`() {
        val args = claudeRunPromptArgs("claude-opus-5", "/tmp/mcp.json", "/tmp/s.json")
        assertTrue(args.indexOf("--settings") < args.indexOf("-p")) { "settings must not land after -p: $args" }
        assertEquals(
            listOf("--strict-mcp-config", "--settings", "/tmp/s.json", "-p"),
            args.takeLast(4),
        )
    }
}
