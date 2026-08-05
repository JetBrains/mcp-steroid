/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every `devrig <x> --help` response comes from the same Clikt command tree that performs the work.
 * Generated MCP-tool commands derive their grammar from metadata, while hand-written lifecycle commands
 * provide their own focused synopsis and nested subcommands. These tests assert the rendered CONTENT.
 */
class CommandHelpRoutingTest {

    private fun help(vararg args: String): String {
        val invocation = parseDevrigCommand(arrayOf(*args))
        assertEquals("help", invocation.commandPath, "expected help for ${args.toList()}")
        return assertNotNull(invocation.informationalText)
    }

    @Test
    fun `execute_code answers --help with help naming itself and its own flags`() {
        val text = help("execute_code", "--help")

        for (token in listOf("execute_code", "--code", "--code-file", "--task_id", "--reason")) {
            assertTrue(token in text, "execute_code's help must name '$token'; got:\n$text")
        }
    }

    @Test
    fun `the short -h spelling reaches the same generated help`() {
        val short = help("execute_code", "-h")
        assertEquals(short, help("execute_code", "--help"))
    }

    @Test
    fun `every generated tool command answers with help that names itself and every flag it declares`() {
        for (tool in devrigCliTools().filterNot { it.cli.hidden }) {
            val text = help(tool.cli.name, "--help")
            assertTrue(tool.cli.name in text, "${tool.cli.name}'s help must name the command; got:\n$text")
            assertTrue(tool.cli.synopsis in text, "${tool.cli.name}'s help must carry its synopsis; got:\n$text")

            val declared = tool.schema.asCliParams().filterNot { it.cliHidden }
                .flatMap { listOfNotNull(if (it.cliPositional) it.name else it.cliFlag, it.cliFileSource?.flag) } +
                tool.cli.extraOptions.map { it.flag }
            for (name in declared) {
                assertTrue(name in text, "${tool.cli.name}'s help must name '$name'; got:\n$text")
            }
            for (frameworkFlag in listOf("--help", "--debug", "--json")) {
                assertTrue(frameworkFlag in text, "${tool.cli.name}'s help must name '$frameworkFlag'; got:\n$text")
            }
            assertEquals(
                tool.cli.producesImage,
                "--out" in text,
                "${tool.cli.name}'s --out help must follow producesImage; got:\n$text",
            )
        }
    }

    @Test
    fun `an alias reaches the help of the command it expands to`() {
        val canonical = help("fetch_resource", "--help")
        assertEquals(canonical, help("prompt", "--help"))

        val listProjects = help("list_projects", "--help")
        assertEquals(listProjects, help("project", "--help"))
        assertTrue("Usage: devrig list_projects" in listProjects, listProjects)
    }

    @Test
    fun `help command routes to canonical focused help including nested actions`() {
        assertEquals(help("execute_code", "--help"), help("help", "execute_code"))
        assertEquals(help("list_projects", "--help"), help("help", "project"))
        assertEquals(help("backend", "download", "--help"), help("help", "backend", "download"))
    }

    @Test
    fun `every visible lifecycle command renders focused help from its real command node`() {
        val cases = listOf(
            arrayOf("--help") to "Usage: devrig ",
            arrayOf("-h") to "Usage: devrig ",
            arrayOf("help") to "Usage: devrig ",
            arrayOf("mcp", "--help") to "Usage: devrig mcp",
            arrayOf("version", "--help") to "Usage: devrig version",
            arrayOf("help", "--help") to "Usage: devrig ",
            arrayOf("backend", "--help") to "Usage: devrig backend",
            arrayOf("backend", "download", "--help") to "Usage: devrig backend download",
            arrayOf("backend", "start", "--help") to "Usage: devrig backend start",
            arrayOf("backend", "stop", "--help") to "Usage: devrig backend stop",
            arrayOf("backend", "provision", "--help") to "Usage: devrig backend provision",
            arrayOf("install", "--help") to "Usage: devrig install",
            arrayOf("install", "claude", "--help") to "Usage: devrig install claude",
            arrayOf("install", "codex", "--help") to "Usage: devrig install codex",
            arrayOf("install", "gemini", "--help") to "Usage: devrig install gemini",
            arrayOf("install", "config", "--help") to "Usage: devrig install config",
            arrayOf("install", "devrig", "--help") to "Usage: devrig install devrig",
            arrayOf("install", "plugin", "--help") to "Usage: devrig install plugin",
        )
        for ((args, usage) in cases) {
            val text = help(*args)
            assertTrue(usage in text, "${args.toList()} must render focused help; got:\n$text")
        }
    }

    @Test
    fun `bare generated commands report every missing value with command-scoped usage and hints`() {
        for (tool in devrigCliTools().filterNot { it.cli.hidden }) {
            val required = tool.schema.asCliParams().filter { it.required && !it.cliHidden }
            if (required.isEmpty()) continue

            val invocation = parseDevrigCommand(arrayOf(tool.cli.name))
            assertEquals("parse-error", invocation.commandPath, "${tool.cli.name} must reject missing values")
            val text = assertNotNull(invocation.informationalText)
            assertTrue("Usage: devrig ${tool.cli.name}" in text, "missing scoped usage for ${tool.cli.name}:\n$text")
            for (param in required) {
                val hint = assertNotNull(
                    param.cliMissingHint,
                    "${tool.cli.name}.${param.name} must explain which value to provide",
                )
                assertTrue(
                    hint.replace(Regex("\\s+"), " ") in text.replace(Regex("\\s+"), " "),
                    "${tool.cli.name} must report the missing ${param.name} value with its hint; got:\n$text",
                )
            }
        }
    }
}
