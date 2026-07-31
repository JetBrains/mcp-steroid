/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `devrig <x> --help` answers with one of two texts, and which one is a deliberate split:
 *
 *  - a **generated tool command** answers with Clikt's own help for THAT command, because its grammar is
 *    generated from the tool's declaration and nothing hand-written could stay in step with it;
 *  - every **lifecycle verb** (`mcp`, `backend`, `project`, `install`, `help`) and the bare `devrig` answer
 *    with devrig's curated banner, which is the document that explains those verbs.
 *
 * The predecessor test asserted only that `--help` selected [DevrigCommand.DevrigCommandHelp], and that is
 * exactly why `devrig execute_code --help` printing a banner that named neither `execute_code` nor any of
 * its flags went unnoticed. These tests assert the CONTENT.
 */
class CommandHelpRoutingTest {

    private fun help(vararg args: String): DevrigCommand.DevrigCommandHelp =
        assertIs<DevrigCommand.DevrigCommandHelp>(parseDevrigCommand(arrayOf(*args)), "expected help for ${args.toList()}")

    @Test
    fun `execute_code answers --help with help naming itself and its own flags`() {
        val text = help("execute_code", "--help").generatedHelp
        assertNotNull(text, "a generated tool command must answer with its own generated help")

        for (token in listOf("execute_code", "--code", "--code-file", "--task_id", "--reason")) {
            assertTrue(token in text, "execute_code's help must name '$token'; got:\n$text")
        }
    }

    @Test
    fun `the short -h spelling reaches the same generated help`() {
        assertEquals(help("execute_code", "-h").generatedHelp, help("execute_code", "--help").generatedHelp)
    }

    @Test
    fun `every generated tool command answers with help that names itself and every flag it declares`() {
        for (tool in devrigCliTools().filterNot { it.cli.hidden }) {
            val text = help(tool.cli.name, "--help").generatedHelp
            assertNotNull(text, "${tool.cli.name} must answer --help with its own generated help")
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
        assertEquals(help("fetch_resource", "--help").generatedHelp, help("prompt", "--help").generatedHelp)
    }

    @Test
    fun `the lifecycle verbs and the bare devrig keep the curated banner`() {
        for (args in listOf(
            arrayOf(),
            arrayOf("--help"),
            arrayOf("-h"),
            arrayOf("help"),
            arrayOf("mcp", "--help"),
            arrayOf("project", "--help"),
            arrayOf("backend", "--help"),
            arrayOf("backend", "download", "--help"),
            arrayOf("install", "--help"),
        )) {
            assertNull(
                help(*args).generatedHelp,
                "${args.toList()} must keep devrig's curated banner, which is what documents these verbs",
            )
        }
    }
}
