/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DevrigCommandTest {
    @Test
    fun `command tree selects every top level mode`() {
        assertInvocation("help", DevrigCliMode.INFORMATIONAL)
        assertInvocation("devrig version", DevrigCliMode.INFORMATIONAL, "version")
        assertInvocation("devrig", DevrigCliMode.INFORMATIONAL, "--version")
        assertInvocation("devrig mcp", DevrigCliMode.MCP, "mcp")
        assertInvocation("devrig mpc", DevrigCliMode.MCP, "mpc")
        assertInvocation("devrig backend", DevrigCliMode.BACKEND, "backend")
        assertInvocation("devrig list_projects", DevrigCliMode.GENERATED_TOOL, "list_projects")
        assertInvocation("devrig list_projects", DevrigCliMode.GENERATED_TOOL, "project")
        assertInvocation("devrig install claude", DevrigCliMode.INSTALL, "install", "claude")
        assertInvocation("devrig install codex", DevrigCliMode.INSTALL, "install", "codex")
        assertInvocation("devrig install gemini", DevrigCliMode.INSTALL, "install", "gemini")
    }

    @Test
    fun `bare root help preserves the requested debug logging mode`() {
        val invocation = command("--debug")

        assertEquals("help", invocation.commandPath)
        assertTrue(invocation.debug)
    }

    @Test
    fun `backend actions retain command scoped arguments and generic options`() {
        val download = command("--debug", "backend", "download", "idea-community", "--version", "2025.3", "--json")
        assertEquals("devrig backend download", download.commandPath)
        assertEquals(DevrigCliMode.BACKEND, download.mode)
        assertTrue(download.debug)
        assertTrue(download.json)

        val interspersed = command("backend", "--json", "download", "idea-community")
        assertEquals("devrig backend download", interspersed.commandPath)
        assertTrue(interspersed.json)

        assertEquals("devrig backend start", command("backend", "start").commandPath)
        assertEquals("devrig backend stop", command("backend", "stop").commandPath)
        assertEquals("devrig backend provision", command("backend", "provision").commandPath)
    }

    @Test
    fun `json is accepted for document producing commands and rejected elsewhere`() {
        for (args in listOf(
            arrayOf("backend", "--json"),
            arrayOf("list_projects", "--json"),
            arrayOf("install", "--json"),
            arrayOf("install", "config", "--json"),
            arrayOf("version", "--json"),
        )) {
            assertTrue(command(*args).json, args.joinToString(" "))
        }

        assertEquals("parse-error", command("--json", "mcp").commandPath)
        assertEquals("parse-error", command("--json", "install", "claude").commandPath)
        assertEquals("parse-error", command("install", "plugin", "--json").commandPath)
    }

    @Test
    fun `only stdio MCP and generated JSON keep direct System out guarded`() {
        assertTrue(command("mcp").keepsSystemOutGuarded)
        assertTrue(command("list_projects", "--json").keepsSystemOutGuarded)
        assertFalse(command("list_projects").keepsSystemOutGuarded)
        assertFalse(command("backend", "--json").keepsSystemOutGuarded)
        assertFalse(command("version", "--json").keepsSystemOutGuarded)
    }

    @Test
    fun `mcp is canonical while mpc remains a hidden compatibility alias`() {
        assertEquals(DevrigCliMode.MCP, command("mcp").mode)
        assertEquals(DevrigCliMode.MCP, command("mpc").mode)
        assertTrue(command("mcp", "--debug").debug)

        val parseError = command("totally-unknown-subcommand")
        assertEquals("parse-error", parseError.commandPath)
    }

    @Test
    fun `list_projects is canonical while plural and singular aliases remain compatible`() {
        val canonical = command("list_projects", "--json")
        for (aliasName in listOf("projects", "project")) {
            val alias = command(aliasName, "--json")
            assertEquals(canonical.commandPath, alias.commandPath)
            assertEquals("devrig list_projects", alias.commandPath)
            assertEquals(canonical.generatedTool, alias.generatedTool)
        }
    }

    @Test
    fun `root and backend version options stay scoped`() {
        assertEquals("devrig", command("--version").commandPath)
        assertEquals(
            "devrig backend download",
            command("backend", "download", "idea-community", "--version", "2025.3").commandPath,
        )
    }

    @Test
    fun `invalid inputs become usage invocations instead of throwing`() {
        for (args in listOf(
            arrayOf("foo"),
            arrayOf("--no-such"),
            arrayOf("--home", "/tmp/devrig-home"),
            arrayOf("backend", "download", "idea-community", "extra"),
            arrayOf("install", "other"),
            arrayOf("install", "--check"),
            arrayOf("install", "config", "--check"),
            arrayOf("install", "devrig", "--check"),
            arrayOf("--json"),
            arrayOf("--json", "help"),
            arrayOf("backend", "download", "bogus"),
            arrayOf("backend", "start", "bogus"),
            arrayOf("backend", "stop", "bogus"),
            arrayOf("backend", "provision", "bogus"),
        )) {
            assertEquals("parse-error", command(*args).commandPath, args.joinToString(" "))
        }
    }

    @Test
    fun `install targets are independent subcommands`() {
        assertEquals("devrig install", command("install").commandPath)
        assertEquals("devrig install claude", command("install", "claude", "--check").commandPath)
        assertEquals("devrig install claude", command("install", "CLAUDE").commandPath)
        assertEquals("devrig install plugin", command("install", "plugin", "--check").commandPath)
        assertEquals("devrig install config", command("install", "config").commandPath)

        val bare = command("install", "devrig")
        for (args in listOf(
            arrayOf("install", "devrig", "--install-script=/opt/devrig/bin/devrig", "--jdk-home=/opt/jdk"),
            arrayOf("install", "devrig", "--install-script="),
            arrayOf("install", "devrig", "--jdk-home=/opt/jdk"),
        )) {
            assertEquals(bare.commandPath, command(*args).commandPath)
        }
    }

    private fun assertInvocation(
        expectedPath: String,
        expectedMode: DevrigCliMode,
        vararg args: String,
    ) {
        val invocation = command(*args)
        assertEquals(expectedPath, invocation.commandPath)
        assertEquals(expectedMode, invocation.mode)
    }

    private fun command(vararg args: String): DevrigCliInvocation = parseDevrigCommand(arrayOf(*args))
}
