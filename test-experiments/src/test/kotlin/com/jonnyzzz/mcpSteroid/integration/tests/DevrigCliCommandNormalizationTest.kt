/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DevrigCliCommandNormalizationTest {
    @Test
    fun `accepts a direct raw devrig invocation`() {
        assertEquals(
            "$DEVRIG list_projects --json",
            normalizeRawDevrigCommand("$DEVRIG list_projects --json", DEVRIG),
        )
    }

    @Test
    fun `recognizes json before or after the command`() {
        assertEquals(true, invokesJsonOnlyDevrigAction("$DEVRIG list_projects --json", DEVRIG, "list_projects"))
        assertEquals(true, invokesJsonOnlyDevrigAction("$DEVRIG --json list_projects", DEVRIG, "list_projects"))
    }

    @Test
    fun `recognizes a subcommand after the global json flag`() {
        assertEquals(true, invokesDevrigCommand("$DEVRIG list_projects --json", DEVRIG, "list_projects"))
        assertEquals(true, invokesDevrigCommand("$DEVRIG --json list_projects", DEVRIG, "list_projects"))
    }

    @Test
    fun `recognizes a trailing flag inside the Codex bash transport`() {
        assertEquals(
            true,
            devrigCommandHasFlag("/bin/bash -lc '$DEVRIG list_projects --json'", DEVRIG, "--json"),
        )
    }

    @Test
    fun `recognizes a flag value inside the Codex bash transport`() {
        assertEquals(
            true,
            devrigCommandHasFlagValue(
                "/bin/bash -lc '$DEVRIG execute_code --project_name=project-key'",
                DEVRIG,
                "--project_name",
                "project-key",
            ),
        )
    }

    @Test
    fun `recognizes the positional prompt URI used by focused help`() {
        val command = "$DEVRIG fetch_resource mcp-steroid://prompt/skill --project_name=project-key --json"

        assertEquals(true, devrigCommandHasArgumentValue(command, DEVRIG, "mcp-steroid://prompt/skill"))
        assertEquals(false, devrigCommandHasFlag(command, DEVRIG, "--uri"))
    }

    @Test
    fun `recognizes shell-safe inline code inside the Codex bash transport`() {
        val wrapped = "/bin/bash -lc \"$DEVRIG execute_code --code='println(\\\"ok\\\")'\""
        assertEquals(true, devrigCommandHasShellSafeInlineCode(wrapped, DEVRIG, "println(\"ok\")"))
    }

    @Test
    fun `unwraps the Codex bash transport`() {
        assertEquals(
            "$DEVRIG help backend download",
            normalizeRawDevrigCommand("/bin/bash -lc '$DEVRIG help backend download'", DEVRIG),
        )
    }

    @Test
    fun `unwraps the Codex bare single-word bash transport`() {
        assertEquals(
            DEVRIG,
            normalizeRawDevrigCommand("/bin/bash -lc $DEVRIG", DEVRIG),
        )
    }

    @Test
    fun `unwraps the Codex double-quoted transport`() {
        val wrapped =
            "/bin/bash -lc \"$DEVRIG execute_code --code='println(\\\"ok\\\")' --reason='safe audit'\""
        assertEquals(
            """$DEVRIG execute_code --code='println("ok")' --reason='safe audit'""",
            normalizeRawDevrigCommand(wrapped, DEVRIG),
        )
    }

    @Test
    fun `preserves a safe backslash before an ordinary character in double quotes`() {
        val wrapped = "/bin/bash -lc \"$DEVRIG execute_code --code='println(\\n)'\""
        assertEquals(
            """$DEVRIG execute_code --code='println(\n)'""",
            normalizeRawDevrigCommand(wrapped, DEVRIG),
        )
    }

    @Test
    fun `preserves shell-safe single quotes inside the Codex transport`() {
        val wrapped = """/bin/bash -lc '$DEVRIG execute_code --code='\''println("ok")'\'''"""
        assertEquals(
            """$DEVRIG execute_code --code='println("ok")'""",
            normalizeRawDevrigCommand(wrapped, DEVRIG),
        )
    }

    @Test
    fun `rejects control syntax inside the Codex transport`() {
        assertNull(
            normalizeRawDevrigCommand(
                "/bin/bash -lc '$DEVRIG list_projects --json; touch /tmp/not-allowed'",
                DEVRIG,
            ),
        )
    }

    @Test
    fun `rejects commands appended outside the Codex transport payload`() {
        assertNull(
            normalizeRawDevrigCommand(
                "/bin/bash -lc '$DEVRIG list_projects --json' && touch /tmp/not-allowed",
                DEVRIG,
            ),
        )
    }

    @Test
    fun `rejects expansion in the Codex double-quoted transport`() {
        assertNull(
            normalizeRawDevrigCommand(
                "/bin/bash -lc \"$DEVRIG list_projects --reason=$(touch /tmp/not-allowed)\"",
                DEVRIG,
            ),
        )
    }

    @Test
    fun `rejects unrelated shell wrappers`() {
        assertNull(normalizeRawDevrigCommand("env DEBUG=1 $DEVRIG list_projects", DEVRIG))
    }

    private companion object {
        const val DEVRIG = "/home/agent/devrig"
    }
}
