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
        }
    }

    @Test
    fun `an alias reaches the help of the command it expands to`() {
        val canonical = help("fetch_resource", "--help")
        assertEquals(canonical, help("prompt", "--help"))
    }

    @Test
    fun `lifecycle verbs render focused help from their real command nodes`() {
        val cases = listOf(
            arrayOf("--help") to "Usage: devrig ",
            arrayOf("-h") to "Usage: devrig ",
            arrayOf("help") to "Usage: devrig ",
            arrayOf("mcp", "--help") to "Usage: devrig mcp",
            arrayOf("project", "--help") to "Usage: devrig project",
            arrayOf("backend", "--help") to "Usage: devrig backend",
            arrayOf("backend", "download", "--help") to "Usage: devrig backend download",
            arrayOf("install", "--help") to "Usage: devrig install",
        )
        for ((args, usage) in cases) {
            val text = help(*args)
            assertTrue(usage in text, "${args.toList()} must render focused help; got:\n$text")
        }
    }
}
