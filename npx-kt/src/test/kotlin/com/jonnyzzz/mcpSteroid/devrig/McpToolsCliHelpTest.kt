/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The "MCP tools as CLI" section of the global `devrig --help` banner is GENERATED from each tool's own
 * declaration — its [com.jonnyzzz.mcpSteroid.mcp.CliCommandSpec] and the parameters `asCliParams()`
 * exposes — and never hand-written (PR #272 review r3579479002: "re-use information from the MCP tools to
 * generate these texts").
 *
 * Two claims are under test and they pull in opposite directions, which is why both are needed:
 *  - the *set* of what is shown is derived (a ninth tool, or a ninth parameter, appears with no edit here),
 *    asserted by reading the same specs the renderer reads;
 *  - the *shape* it is rendered in is pinned whole, because a wording or an alignment nobody asserts is a
 *    wording that silently rots. Three defects on this branch survived substring assertions.
 */
class McpToolsCliHelpTest {

    private fun section(): String = renderMcpToolsCliSection(devrigCliTools())

    private fun globalHelp(): String {
        val buffer = ByteArrayOutputStream()
        printHelp(PrintStream(buffer, true, Charsets.UTF_8))
        return buffer.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun visibleTools() = devrigCliTools().filterNot { it.cli.hidden }

    /**
     * The block the section devotes to one command. Blocks are separated by a blank line, and looking one
     * up by command matters: `--code` belongs to two different tools with two different synopses, so a
     * section-wide search would happily confirm the wrong one.
     */
    private fun blockOf(command: String): String = section().split("\n\n")
        .first { it.startsWith("  devrig $command ") || it.startsWith("  devrig $command\n") }

    /** The detail line a block devotes to [label], or null; labels are padded, hence the trailing space. */
    private fun String.detailLineFor(label: String): String? =
        lines().firstOrNull { it.startsWith("        $label ") }

    @Test
    fun `the section shows exactly the visible tools of devrigToolSpecs, in factory order`() {
        val rendered = section().lines()
            .filter { it.startsWith("  devrig ") }
            .map { it.removePrefix("  devrig ").substringBefore(' ') }

        assertEquals(visibleTools().map { it.cli.name }, rendered, "the shown tool set must be the canonical one")
    }

    @Test
    fun `every tool block carries its own declared command synopsis`() {
        val section = section()
        for (tool in visibleTools()) {
            assertEquals(
                "      ${tool.cli.synopsis}",
                section.lines().firstOrNull { it.trim() == tool.cli.synopsis },
                "the synopsis line of ${tool.cli.name} must be its declared cliSynopsis, indented six:\n$section",
            )
        }
    }

    @Test
    fun `every visible parameter contributes one line carrying its own declared cliSynopsis`() {
        for (tool in visibleTools()) {
            val block = blockOf(tool.cli.name)
            for (param in tool.schema.asCliParams().filterNot { it.cliHidden }) {
                val label = if (param.cliPositional) "<${param.name}>" else param.cliFlag
                val line = block.detailLineFor(label)
                assertNotNull(line, "no help line for $label of ${tool.cli.name}:\n$block")
                assertEquals(
                    param.cliSynopsis,
                    line.substringAfter("        $label ").trim(),
                    "$label of ${tool.cli.name} must be described by its declared cliSynopsis",
                )
            }
        }
    }

    @Test
    fun `a declared file source renders from its own declaration, with no hand-written text`() {
        var seen = 0
        for (tool in visibleTools()) {
            val block = blockOf(tool.cli.name)
            for (source in tool.schema.asCliParams().mapNotNull { it.cliFileSource }) {
                seen++
                val line = block.detailLineFor(source.flag)
                assertNotNull(line, "no help line for the declared file source ${source.flag}:\n$block")
                assertEquals(source.synopsis, line.substringAfter("        ${source.flag} ").trim())
            }
        }
        assertTrue(seen > 0, "no tool declares a cliFileSource, so this test proves nothing")
    }

    @Test
    fun `a declared tool-level extra option renders from its own declaration, with no hand-written text`() {
        var seen = 0
        for (tool in visibleTools()) {
            val block = blockOf(tool.cli.name)
            for (extra in tool.cli.extraOptions) {
                seen++
                val line = block.detailLineFor(extra.flag)
                assertNotNull(line, "no help line for the declared extra option ${extra.flag}:\n$block")
                assertEquals(extra.synopsis, line.substringAfter("        ${extra.flag} ").trim())
            }
        }
        assertTrue(seen > 0, "no tool declares a CliExtraOption, so this test proves nothing")
    }

    @Test
    fun `a tool with no parameters renders its usage line alone`() {
        assertTrue(
            "  devrig list_projects\n      list open projects and their routing keys\n" in section(),
            "list_projects declares no parameter, so its block is the usage line plus the synopsis:\n${section()}",
        )
    }

    @Test
    fun `the usage line spells each parameter by its declared shape, wrapped at the help width`() {
        // execute_code covers every shape at once: a cwd-inferred optional (project_name), a required value
        // reachable two ways (code / --code-file), plain required values, an optional number and an enum.
        val expected =
            "  devrig execute_code [--project_name=<project_name>] (--code=<code> | --code-file=<path>)\n" +
                "                      --task_id=<task_id> --reason=<reason> [--timeout=<timeout>]\n" +
                "                      [--modal=<smart_non_modal | non_modal | unleashed>]\n"

        assertTrue(expected in section(), "execute_code's usage line must render every declared shape:\n${section()}")
    }

    @Test
    fun `a boolean switch, an optional flag and a tool-level extra all reach the usage line`() {
        assertTrue(
            "  devrig open_project --project_path=<project_path> --task_id=<task_id> --reason=<reason>\n" +
                "                      [--trust_project] [--backend_name=<backend_name>] [--wait]\n" in section(),
            "open_project's usage line must render its boolean, its optional flag and its --wait extra:\n${section()}",
        )
    }

    @Test
    fun `a tool's declared aliases trail its usage line`() {
        assertTrue(
            "  devrig fetch_resource --uri=<uri> [--project_name=<project_name>] (alias: prompt)\n" in section(),
            "fetch_resource must advertise its declared `prompt` alias:\n${section()}",
        )
    }

    @Test
    fun `the footer is exactly the framework-level facts that belong to no parameter`() {
        val expected =
            "  Common CLI flags (devrig's own, accepted by every command above):\n" +
                "    --json        $DEVRIG_JSON_FLAG_HELP\n" +
                "    --out=<path>  $DEVRIG_OUT_FLAG_HELP\n" +
                "    --project_name is inferred from the current directory when omitted.\n" +
                "    Run `devrig <command> --help` for one command's full option list.\n"

        assertEquals(expected, section().substring(section().indexOf("  Common CLI flags")))
    }

    @Test
    fun `the footer documents no flag that a tool declares for itself`() {
        val footer = section().substringAfter("  Common CLI flags")
        for (hidden in listOf("--code-file", "--wait")) {
            assertFalse(
                hidden in footer,
                "$hidden is declared on its tool and must render from that declaration, not from the footer:\n$footer",
            )
        }
    }

    @Test
    fun `no rendered line exceeds the help width`() {
        val tooWide = section().lines().filter { it.length > 100 }
        assertEquals(emptyList(), tooWide, "generated help lines must stay within 100 columns")
    }

    @Test
    fun `printHelp embeds the generated section verbatim`() {
        assertTrue(section() in globalHelp(), "the global banner must embed the generated section:\n${globalHelp()}")
    }

    @Test
    fun `the curated lifecycle banner survives beside the generated section`() {
        val help = globalHelp()
        for (marker in listOf(
            "Usage:",
            "devrig mcp",
            "devrig backend provision [<id>] [--json]",
            "devrig install claude|codex|gemini [--check]",
            "devrig --version | -v",
            "Options applicable to every mode:",
            "Environment variables:",
            "DEVRIG_JVM_OPTS",
        )) {
            assertTrue(marker in help, "curated banner lost '$marker':\n$help")
        }
        assertFalse("devrig mpc" in help, "the hidden mpc alias must stay unadvertised:\n$help")
    }
}
