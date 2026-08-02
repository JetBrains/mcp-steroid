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
        for (tool in visibleTools()) {
            // Looked up inside the tool's OWN block: a section-wide search would confirm a synopsis
            // rendered under the wrong tool, and would pick the wrong line if two tools ever shared one.
            val block = blockOf(tool.cli.name)
            assertEquals(
                "      ${tool.cli.synopsis}",
                block.lines().firstOrNull { it.trim() == tool.cli.synopsis },
                "the synopsis line of ${tool.cli.name} must be its declared cliSynopsis, indented six:\n$block",
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
        // execute_code covers every shape at once: a `cliOptional` parameter (project_name — required, so
        // un-bracketed, because the tool refuses the call without it), a required value reachable two ways
        // (code / --code-file), plain required values, an optional number, an enum.
        val expected =
            "  devrig execute_code --project_name=<project_name> (--code=<code> | --code-file=<path>)\n" +
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
            "  devrig fetch_resource --uri=<uri> --project_name=<project_name> (alias: prompt)\n" in section(),
            "fetch_resource must advertise its declared `prompt` alias:\n${section()}",
        )
    }

    @Test
    fun `the footer is exactly the framework-level facts that belong to no parameter`() {
        val expected =
            "  Common CLI flags (devrig's own; accepted by every command, tool and lifecycle alike):\n" +
                "    --debug       $DEVRIG_DEBUG_FLAG_HELP\n" +
                "    --json        $DEVRIG_JSON_FLAG_HELP\n" +
                "    --out=<path>  $DEVRIG_OUT_FLAG_HELP\n" +
                "    Run `devrig <command> --help` for one command's full option list.\n"

        assertEquals(expected, section().substring(section().indexOf("  Common CLI flags")))
    }

    @Test
    fun `the footer promises no cwd inference, because nothing infers project_name`() {
        // The footer used to state "--project_name is inferred from the current directory when omitted."
        // Nothing performs that inference: `resolveProjectFromCwd` has no production caller, so
        // `devrig execute_code` run from inside an open project without `--project_name` reaches the tool
        // with the parameter absent and the tool refuses it ("Parameter project_name of type string is
        // required") — an honest exit 64, which is why the usage line renders the flag un-bracketed.
        //
        // Both halves are asserted together on purpose. The first alone would be a wording pin; the
        // second pins the BEHAVIOUR the sentence described, so whoever implements the inference breaks
        // this test and has to restore the sentence in the same commit — which is exactly the coupling
        // whose absence let the help and the runtime drift apart.
        assertFalse(
            "inferred from the current directory" in section(),
            "the footer must not promise an inference the CLI does not perform:\n${section()}",
        )

        val parsed = parseRunTool("execute_code", "--code=x", "--task_id=t", "--reason=r")
        assertFalse(
            "project_name" in parsed.arguments,
            "nothing fills project_name from the cwd today; if that changed, restore the footer line " +
                "documenting it. Parsed arguments were: ${parsed.arguments}",
        )
    }

    @Test
    fun `each shared framework-flag help string is pinned literally`() {
        // The footer pin above interpolates these constants, so it stays green through any rewording of
        // them. That is the right call for the layout assertion — but it leaves the WORDING unpinned, and
        // the wording is what carried the defect: the banner used to name a `DEBUG` env var that has never
        // existed, while the only variable the code reads (`System.getenv("DEVRIG_DEBUG")`) is DEVRIG_DEBUG.
        assertEquals(
            "enable verbose stderr logging (also enabled by the DEVRIG_DEBUG env var)",
            DEVRIG_DEBUG_FLAG_HELP,
        )
        assertEquals("emit JSON output where supported", DEVRIG_JSON_FLAG_HELP)
        assertEquals(
            "write the image the command returns to this path instead of the devrig temp dir",
            DEVRIG_OUT_FLAG_HELP,
        )
    }

    @Test
    fun `the framework flags are documented under exactly one heading`() {
        val help = globalHelp()
        for (flag in listOf("--debug", "--json", "--out")) {
            // A line that *documents* the flag opens with it; the flag may carry a metavar (`--out=<path>`).
            val documented = help.lines().filter { line ->
                val text = line.trimStart()
                text.startsWith(flag) && (text.length == flag.length || text[flag.length] in " =")
            }
            assertEquals(1, documented.size, "$flag must be documented once, not split across headings:\n$help")
        }
        assertFalse(
            "Options applicable to every mode:" in help,
            "that heading listed only --debug while --json and --out apply just as widely; the footer " +
                "now documents all three:\n$help",
        )
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
            "Environment variables:",
            "DEVRIG_JVM_OPTS",
        )) {
            assertTrue(marker in help, "curated banner lost '$marker':\n$help")
        }
        assertFalse("devrig mpc" in help, "the hidden mpc alias must stay unadvertised:\n$help")
    }

    @Test
    fun `the banner is assembled from a literally-pinned head, the generated section, and a literal tail`() {
        // The curated halves are spelled out here rather than recomputed from printHelp: an assertion that
        // calls the thing it is checking pins the assembly but no text, and would pass if the banner
        // emitted garbage. This diff re-indented both halves, which is exactly when that matters.
        val head =
            """
            Usage:

              devrig mcp                     run as an MCP stdio server,
                                             register that setup in your coding agent

              devrig backend [--json]        list discovered backends (with versions) and the
                                             projects each one has open. `--json` emits a
                                             single machine-readable object on stdout
                                             (pipe through `jq`); default is human text.

              devrig project [--json]        list open projects across discovered backends.
                                             `--json` emits a single machine-readable
                                             object on stdout; default is human text.

              devrig install claude|codex|gemini [--check]
                                             register this devrig binary as the
                                             mcp-steroid stdio MCP server in the
                                             selected coding agent. `--check` is a
                                             read-only dry-run: it reports the current
                                             registration, the changes install would
                                             apply, and how many IDE backends with the
                                             MCP Steroid plugin are reachable; exits 1
                                             when install would change anything.

              devrig backend download [<id>] [--version <v>] [--json]
                                             no id → list IDEs available for download.
                                             With id, download and install a managed
                                             backend under the devrig home. Accepts
                                             <product>, <product>:<version>, or
                                             <product>-<version>.

              devrig backend start    [<id>] [--version <v>] [--json]
                                             no id → list installed backends. With id,
                                             start an installed managed backend in
                                             detached mode and print its pid/log/config
                                             paths. Product-only id prefers the
                                             highest locally installed backend.

              devrig backend stop     [<id>] [--version <v>] [--json]
                                             no id → list currently running backends.
                                             With id, stop a managed backend by pid file.
                                             Product-only id prefers the highest
                                             locally installed backend.

              devrig backend provision [<id>] [--json]
                                             no id → list port-discovered IDEs that can be
                                             provisioned. With id (for example port-63342),
                                             print manual MCP Steroid plugin install
                                             instructions for that IDE.
              devrig --version | -v          print the devrig version and exit
              devrig --help    | -h          print this help and exit

            """.trimIndent() + "\n"
        val tail =
            """
            Environment variables:
              DEVRIG_JVM_OPTS                extra JVM options for the devrig launch (for example "-Xmx512m").


            """.trimIndent() + "\n"

        assertEquals(head + section() + "\n" + tail, globalHelp())
    }
}
