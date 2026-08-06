/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(
            "quote --code or prefer --code-file" in text,
            "execute_code help must put the shell rule in its leading synopsis; got:\n$text",
        )
        assertTrue(
            "--code='println(\"hello\")'" in text,
            "execute_code help must show shell-safe quoting for inline Kotlin; got:\n$text",
        )
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
                .flatMap {
                    listOfNotNull(
                        if (it.cliPositional) it.name else it.cliFlag,
                        it.negativeCliFlag,
                        it.cliFileSource?.flag,
                    )
                } +
                tool.cli.extraOptions.map { it.flag }
            for (name in declared) {
                assertTrue(name in text, "${tool.cli.name}'s help must name '$name'; got:\n$text")
            }
            for (param in tool.schema.asCliParams().filterNot { it.cliHidden }) {
                val renderedSynopsis = param.cliSynopsis.replace("`", "").replace(Regex("\\s+"), " ")
                val renderedHelp = text.replace(Regex("\\s+"), " ")
                assertTrue(
                    renderedSynopsis in renderedHelp,
                    "${tool.cli.name}'s help must explain ${param.name} with '${param.cliSynopsis}'; got:\n$text",
                )
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
        for (alias in listOf("projects", "project")) {
            assertEquals(listProjects, help(alias, "--help"))
        }
        assertTrue("Usage: devrig list_projects" in listProjects, listProjects)
    }

    @Test
    fun `help command routes to canonical focused help for every command alias and nested action`() {
        for (tool in devrigCliTools().filterNot { it.cli.hidden }) {
            val canonical = help(tool.cli.name, "--help")
            assertEquals(canonical, help("help", tool.cli.name), "help route for ${tool.cli.name}")
            for (alias in tool.cli.aliases) {
                assertEquals(canonical, help("help", alias), "help route for alias $alias")
            }
        }
        for (path in listOf(
            listOf("mcp"),
            listOf("version"),
            listOf("backend"),
            listOf("backend", "download"),
            listOf("backend", "start"),
            listOf("backend", "stop"),
            listOf("backend", "provision"),
            listOf("install"),
            listOf("install", "claude"),
            listOf("install", "codex"),
            listOf("install", "gemini"),
            listOf("install", "config"),
            listOf("install", "devrig"),
            listOf("install", "plugin"),
        )) {
            assertEquals(
                help(*(path + "--help").toTypedArray()),
                help(*(listOf("help") + path).toTypedArray()),
                "help route for ${path.joinToString(" ")}",
            )
        }
    }

    @Test
    fun `help remains a real subcommand when a root option precedes it`() {
        assertEquals(
            help("execute_code", "--help"),
            help("--debug", "help", "execute_code"),
        )
    }

    @Test
    fun `help reports that a leaf has no subcommands without an empty choices list`() {
        val invocation = parseDevrigCommand(arrayOf("help", "execute_code", "bogus"))
        assertEquals("parse-error", invocation.commandPath)
        val text = assertNotNull(invocation.informationalText)

        assertTrue("'execute_code' has no subcommands" in text, text)
        assertFalse("Choose one of:" in text, text)
    }

    @Test
    fun `every visible lifecycle command renders focused help from its real command node`() {
        val cases = listOf(
            Triple(arrayOf("--help"), "Usage: devrig ", listOf("--version", "--json", "--debug")),
            Triple(arrayOf("-h"), "Usage: devrig ", listOf("--version", "--json", "--debug")),
            Triple(arrayOf("help"), "Usage: devrig ", listOf("backend", "install", "mcp")),
            Triple(arrayOf("mcp", "--help"), "Usage: devrig mcp", listOf("--help", "--debug")),
            Triple(arrayOf("version", "--help"), "Usage: devrig version", listOf("--json", "--debug")),
            Triple(arrayOf("help", "--help"), "Usage: devrig help", listOf("<command>", "--debug")),
            Triple(arrayOf("backend", "--help"), "Usage: devrig backend", listOf("--json", "download", "start", "stop")),
            Triple(arrayOf("backend", "download", "--help"), "Usage: devrig backend download", listOf("<id>", "--version", "--json", "--debug")),
            Triple(arrayOf("backend", "start", "--help"), "Usage: devrig backend start", listOf("<id>", "--version", "--json", "--debug")),
            Triple(arrayOf("backend", "stop", "--help"), "Usage: devrig backend stop", listOf("<id>", "--version", "--json", "--debug")),
            Triple(arrayOf("backend", "provision", "--help"), "Usage: devrig backend provision", listOf("<id>", "--json", "--debug")),
            Triple(arrayOf("install", "--help"), "Usage: devrig install", listOf("--json", "claude", "codex", "gemini", "plugin")),
            Triple(arrayOf("install", "claude", "--help"), "Usage: devrig install claude", listOf("--check", "--debug")),
            Triple(arrayOf("install", "codex", "--help"), "Usage: devrig install codex", listOf("--check", "--debug")),
            Triple(arrayOf("install", "gemini", "--help"), "Usage: devrig install gemini", listOf("--check", "--debug")),
            Triple(arrayOf("install", "config", "--help"), "Usage: devrig install config", listOf("--json", "--debug")),
            Triple(arrayOf("install", "devrig", "--help"), "Usage: devrig install devrig", listOf("--help", "--debug")),
            Triple(arrayOf("install", "plugin", "--help"), "Usage: devrig install plugin", listOf("--check", "--debug")),
        )
        for ((args, usage, tokens) in cases) {
            val text = help(*args)
            assertTrue(usage in text, "${args.toList()} must render focused help; got:\n$text")
            for (token in tokens) {
                assertTrue(token in text, "${args.toList()} must explain '$token'; got:\n$text")
            }
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

    @Test
    fun `missing execute code guidance uses the shell safe inline form`() {
        val invocation = parseDevrigCommand(arrayOf("execute_code"))
        val text = assertNotNull(invocation.informationalText)

        assertTrue("--code='println(\"hello\")'" in text, text)
    }
}
