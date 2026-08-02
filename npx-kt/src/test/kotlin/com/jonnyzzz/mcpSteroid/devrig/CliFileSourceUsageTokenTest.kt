/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The generated usage line brackets a token when the invocation is legal without it and parenthesizes an
 * alternation when one of its two spellings is mandatory. Nothing but a convention makes those two claims
 * true — so these tests check the *rendering* against the *real parser* rather than against a restatement
 * of the rule, for the one shape where the two could plausibly disagree.
 *
 * That shape is a parameter which is schema-`required`, `cliOptional`, and declares a
 * [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] — `execute_code`'s `code`. It is the case where the two
 * branches of the renderer test requiredness differently (`required && !cliOptional` without a file source,
 * plain `required` with one), and reading the renderer alone cannot tell you which is right. The parser
 * settles it: `SchemaCliBinding.parsed()` raises `MissingCliValue` on `required` alone, because a required
 * parameter offering a file source is `cliOptional` only so Clikt stops demanding the direct flag — not
 * because the value became optional.
 *
 * If the binding ever relaxes that to `cliRequired`, the second test here fails and the help stops
 * promising a mandatory alternation the CLI no longer enforces.
 */
class CliFileSourceUsageTokenTest {

    @TempDir
    lateinit var home: Path

    private fun section(): String = renderMcpToolsCliSection(devrigCliTools())

    private fun toolsWithRequiredFileSource() = devrigCliTools()
        .filterNot { it.cli.hidden }
        .flatMap { tool -> tool.schema.asCliParams().filter { it.cliFileSource != null && it.required }.map { tool to it } }

    @Test
    fun `a required parameter with a file source renders as a mandatory alternation`() {
        val cases = toolsWithRequiredFileSource()
        assertTrue(cases.isNotEmpty(), "no tool declares a required parameter with a file source")

        for ((tool, param) in cases) {
            val expected = "(${param.cliFlag}=<${param.name}> | ${param.cliFileSource?.flag}=<path>)"
            assertTrue(
                expected in section(),
                "${tool.cli.name}.${param.name} must render as the mandatory alternation $expected:\n${section()}",
            )
        }
    }

    @Test
    fun `and the parser really does reject an invocation that supplies neither spelling`() {
        // The claim the parenthesis makes, driven through the real command tree. `execute_code` is invoked
        // with every OTHER required parameter present, so the only thing missing is the alternation itself.
        for ((tool, param) in toolsWithRequiredFileSource()) {
            val others = tool.schema.asCliParams()
                .filterNot { it.cliHidden || it.name == param.name || !it.required || it.cliOptional }
                .map { "${it.cliFlag}=x" }
            val command = parseDevrigCommand((listOf(tool.cli.name) + others).toTypedArray())

            val error = assertIs<DevrigCommand.DevrigCommandParseError>(
                command,
                "${tool.cli.name} must reject an invocation supplying neither ${param.cliFlag} nor " +
                    "${param.cliFileSource?.flag}; the help renders that pair as mandatory",
            )
            assertTrue(
                param.name in error.text || param.cliFlag in error.text,
                "the failure must name '${param.name}'; got:\n${error.text}",
            )
        }
    }

    @Test
    fun `a required parameter the parser does not demand still renders as demanded`() {
        // The contrast case, and the one the brackets are easiest to get wrong on. `project_name` is
        // schema-`required` but `cliOptional` and file-source-free: Clikt does not demand it, so the token
        // is neither parenthesized (there is no alternation) nor bracketed (the invocation is NOT legal
        // without it — the TOOL refuses, and `GeneratedToolRuntime` reports that refusal as the same exit
        // 64 the parser would have). Bracketing it would promise a working invocation, the notation twin
        // of the footer sentence, since deleted, that promised a cwd inference nothing performs.
        //
        // Bare, not parenthesized, is also what keeps the first test here honest: a renderer that simply
        // wrapped everything `required` in parentheses would fail this one.
        assertTrue(
            " --project_name=<project_name> " in section(),
            "a required parameter must render as demanded, bare:\n${section()}",
        )
        assertTrue(
            "[--project_name" !in section(),
            "the CLI cannot supply project_name, so no usage line may bracket it:\n${section()}",
        )
        assertTrue(
            "(--project_name" !in section(),
            "project_name declares no file source, so it is not an alternation:\n${section()}",
        )
    }

    @Test
    fun `and the tool really does reject an invocation that omits it, at the same exit code`() {
        // The claim the un-bracketed token makes, driven through the real command tree and the real
        // runtime: the parse SUCCEEDS (Clikt does not demand a cliOptional parameter) and the call then
        // fails 64. Both halves matter — the first is why the token cannot be parenthesized, the second is
        // why it cannot be bracketed. `CliErrorEnvelopeTest` pins the message.
        val parsed = parseRunTool("execute_code", "--code=x", "--task_id=t", "--reason=r")
        assertTrue("project_name" !in parsed.arguments, "got: ${parsed.arguments}")

        val run = runCliForToolTest(home, parsed)

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
    }
}
