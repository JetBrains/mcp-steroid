/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.CliCommandSpec
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.ToolSchema
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The registration contract: every visible tool in the canonical `devrigToolSpecs(...)` list is a
 * `devrig` subcommand generated from its own metadata, and parsing one produces an inert
 * [DevrigCommand.RunTool] — no handler, service or backend is touched.
 *
 * These tests deliberately drive the REAL root command ([parseDevrigCommand]) wherever the claim is about
 * registration, because the point of the task is that adding a tool to `devrigToolSpecs(...)` adds its
 * command with no other edit — a test against a hand-built command list could not see that.
 */
class SchemaToolCliCommandTest {

    private fun parse(vararg args: String): DevrigCommand = parseDevrigCommand(args.toList().toTypedArray())

    private fun visibleToolNames(): List<String> = devrigCliTools().filterNot { it.cli.hidden }.map { it.cli.name }

    /** Records every handler resolution so a test can assert parsing asked for none. */
    private class RecordingToolSource : McpSteroidTools() {
        val resolved = mutableListOf<String>()

        override fun <T> handler(type: Class<T>): T {
            resolved += type.name
            error("no handler may be resolved while parsing: ${type.name}")
        }
    }

    /** A metadata-only spec double: enough of [CliToolSpec] to be registered, and nothing callable. */
    private class FakeToolSpec(
        override val name: String,
        override val cli: CliCommandSpec,
    ) : CliToolSpec {
        override val description: String? = null
        override val schema = ToolSchema()
        override val inputSchema: JsonObject get() = schema.asMcpJson()
        override suspend fun call(context: ToolCallContext): ToolCallResult =
            error("a generated command must never call the tool while parsing")
    }

    @Test
    fun `a parameter colliding with a devrig framework flag fails while the command is built`() {
        // --json / --debug reach every tool command and --out reaches the image-producing ones, so a
        // parameter claiming one of those spellings would give Clikt two options of the same name and let one
        // silently shadow the other. Constructing the real root (every test above) is what proves no tool
        // does this today. producesImage = true so --out is registered and the clash is reachable.
        val spec = FakeToolSpec("steroid_clash", CliCommandSpec(name = "clash", synopsis = "s", producesImage = true))
        spec.schema.register(
            InputSchemaElement.param("out").description("d").cliSynopsis("collides with --out").string(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SchemaToolCliCommand(spec, SelectedDevrigCommand(), parent = null)
        }

        assertTrue("--out" in error.message!!, error.message!!)
    }

    /** Collapses runs of whitespace so an assertion survives the help formatter's line wrapping. */
    private fun String.unwrapped(): String = replace(Regex("\\s+"), " ")

    // ------------------------------- registration -------------------------------

    @Test
    fun `every visible tool spec becomes exactly one generated command, in factory order`() {
        val generated = schemaToolCliCommands(SelectedDevrigCommand(), parent = null).map { it.commandName }

        assertEquals(visibleToolNames(), generated)
    }

    @Test
    fun `a hidden spec contributes no command`() {
        val hidden = FakeToolSpec("steroid_secret", CliCommandSpec(name = "secret", synopsis = "s", hidden = true))
        val visible = FakeToolSpec("steroid_shown", CliCommandSpec(name = "shown", synopsis = "s"))

        val generated = schemaToolCliCommands(SelectedDevrigCommand(), parent = null, tools = listOf(hidden, visible))

        assertEquals(listOf("shown"), generated.map { it.commandName })
    }

    @Test
    fun `the root registers every visible tool command`() {
        val tokens = DevrigRootCommand(SelectedDevrigCommand()).registeredSubcommandNames()

        for (name in visibleToolNames()) {
            assertTrue(name in tokens, "'$name' must be a devrig subcommand; got $tokens")
        }
    }

    @Test
    fun `every root token, aliases included, is claimed exactly once`() {
        val root = DevrigRootCommand(SelectedDevrigCommand())

        val tokens = root.registeredSubcommandNames() + root.aliases().keys
        val duplicates = tokens.groupBy { it }.filterValues { it.size > 1 }.keys

        assertTrue(duplicates.isEmpty(), "these devrig tokens are declared more than once: $duplicates")
    }

    @Test
    fun `the alias token declared in the metadata selects the canonical tool`() {
        val aliased = devrigCliTools().filterNot { it.cli.hidden }.filter { it.cli.aliases.isNotEmpty() }
        assertTrue(aliased.isNotEmpty(), "expected at least one tool to declare a CLI alias")

        for (spec in aliased) {
            for (alias in spec.cli.aliases) {
                assertTrue(alias in DevrigRootCommand(SelectedDevrigCommand()).aliases().keys, "'$alias' is not registered")
            }
        }

        // fetch_resource's `prompt` alias shares the canonical grammar: the alias token expands to the
        // canonical command, so there is exactly one grammar to keep working.
        val run = assertIs<DevrigCommand.RunTool>(
            parse("prompt", "--project_name=key", "--uri=mcp-steroid://prompt/skill"),
        )
        assertEquals("steroid_fetch_resource", run.toolName)
        assertEquals("mcp-steroid://prompt/skill", run.arguments["uri"]?.jsonPrimitive?.content)
    }

    // ------------------------------- parsing is inert -------------------------------

    @Test
    fun `list_windows --json parses to an inert RunTool and resolves no handler`() {
        val source = RecordingToolSource()
        val selected = SelectedDevrigCommand()
        val command = schemaToolCliCommands(selected, parent = null, tools = source.devrigToolSpecs())
            .single { it.commandName == "list_windows" }

        command.parse(listOf("--json"))

        val run = assertIs<DevrigCommand.RunTool>(selected.command)
        assertEquals("steroid_list_windows", run.toolName)
        assertEquals("list_windows", run.commandName)
        assertTrue(run.json)
        assertTrue(run.arguments.isEmpty(), "list_windows declares no parameters; got ${run.arguments}")
        assertTrue(run.fileSources.isEmpty(), "got ${run.fileSources}")
        assertTrue(source.resolved.isEmpty(), "parsing resolved handlers: ${source.resolved}")
    }

    @Test
    fun `a generated command carries the three parsed payloads through unchanged`() {
        val run = assertIs<DevrigCommand.RunTool>(
            parse("open_project", "--project_path=/tmp/p", "--task_id=t1", "--reason=open", "--wait"),
        )

        assertEquals("steroid_open_project", run.toolName)
        assertEquals(listOf("project_path", "task_id", "reason"), run.arguments.keys.toList())
        assertEquals(mapOf("wait" to true), run.extraOptions)
        assertTrue(run.fileSources.isEmpty(), "got ${run.fileSources}")
    }

    @Test
    fun `a file source contributes a deferred path, never a tool argument`() {
        val run = assertIs<DevrigCommand.RunTool>(
            parse("execute_code", "--code-file=repro.kts", "--task_id=t1", "--reason=repro", "--project_name=key"),
        )

        assertEquals(mapOf("code" to "repro.kts"), run.fileSources)
        assertFalse(run.arguments.containsKey("code"), "the path must not reach the tool call; got ${run.arguments}")
    }

    @Test
    fun `--out is accepted on a generated command and travels as plain data`() {
        val run = assertIs<DevrigCommand.RunTool>(
            parse("take_screenshot", "--task_id=t1", "--reason=look", "--out=/tmp/shot.png", "--project_name=key"),
        )

        assertEquals(Path.of("/tmp/shot.png"), run.out)
        assertFalse(run.arguments.containsKey("out"), "--out is a framework flag, not a tool parameter")
    }

    // ------------------------------- failures -------------------------------

    @Test
    fun `--help on a generated command prints help instead of a missing-option error`() {
        // Which help, and what it must name, is CommandHelpRoutingTest's subject; here the claim is only
        // that the eager --help wins over execute_code's required parameters instead of failing on them.
        for (spelling in listOf("--help", "-h")) {
            val help = assertIs<DevrigCommand.DevrigCommandHelp>(parse("execute_code", spelling))
            assertTrue("execute_code" in (help.generatedHelp ?: ""), "got: ${help.generatedHelp}")
        }
    }

    @Test
    fun `a missing required parameter reports the tool's own curated wording`() {
        val error = assertIs<DevrigCommand.DevrigCommandParseError>(
            parse("execute_code", "--code=x", "--reason=r", "--project_name=key"),
        )

        assertTrue(
            "missing --task_id. Any string works; reuse it across related calls.".unwrapped() in error.text.unwrapped(),
            "expected execute_code's curated task_id hint; got:\n${error.text}",
        )
    }

    @Test
    fun `a missing required parameter without a curated hint keeps Clikt's default wording`() {
        // take_screenshot reuses the shared task_id factory, which declares no cliMissingHint.
        val error = assertIs<DevrigCommand.DevrigCommandParseError>(
            parse("take_screenshot", "--reason=r", "--project_name=key"),
        )

        assertTrue("--task_id" in error.text, "expected Clikt's own missing-option wording; got:\n${error.text}")
        assertFalse("Any string works" in error.text, "no hint is declared here; got:\n${error.text}")
    }

    @Test
    fun `giving both a parameter and its file source is a usage error`() {
        val error = assertIs<DevrigCommand.DevrigCommandParseError>(
            parse("execute_code", "--code=x", "--code-file=f.kts", "--task_id=t", "--reason=r", "--project_name=key"),
        )

        assertTrue("--code-file" in error.text.unwrapped(), "got:\n${error.text}")
    }

    @Test
    fun `giving neither a required parameter nor its file source reports the curated wording`() {
        val error = assertIs<DevrigCommand.DevrigCommandParseError>(
            parse("execute_code", "--task_id=t", "--reason=r", "--project_name=key"),
        )

        assertTrue(
            "Pass --code-file=<path> (preferred)".unwrapped() in error.text.unwrapped(),
            "expected execute_code's curated code hint; got:\n${error.text}",
        )
    }

    @Test
    fun `a bare tool invocation reports every parser-demanded missing parameter in one error`() {
        // Before project_name was parse-time required it surfaced a step behind task_id and reason: the
        // parser demanded task_id/reason, then the tool refused the absent project_name on a second run.
        // Now every parameter the parser demands is missing at once, so one error names them together
        // (Clikt aggregates them into a MultiUsageError). The file-source `code` is checked a phase later,
        // once these are supplied, so it is deliberately not asserted here.
        val error = assertIs<DevrigCommand.DevrigCommandParseError>(parse("execute_code"))

        for (name in listOf("--project_name", "--task_id", "--reason")) {
            assertTrue(name in error.text, "the one error must name $name; got:\n${error.text}")
        }
        // project_name is now a demanded parameter like the others, so it carries the same curated wording
        // (from the shared CommonToolParams.projectName() factory) rather than Clikt's bald default.
        assertTrue(
            "Get the routing key from".unwrapped() in error.text.unwrapped(),
            "expected project_name's curated hint; got:\n${error.text}",
        )
    }

    @Test
    fun `an unknown flag on a generated command is a parse error`() {
        val error = assertIs<DevrigCommand.DevrigCommandParseError>(parse("list_windows", "--bogus"))

        assertTrue("--bogus" in error.text, "got:\n${error.text}")
    }

    // --------------------------- --out is scoped to the tool commands ---------------------------

    @Test
    fun `--out is rejected on a lifecycle command, which can never honour it`() {
        // `--out` redirects the image a tool RESULT carries, and no lifecycle verb returns a result at
        // all. It used to be declared on the shared base class, so `devrig project --out=/tmp/x.png`
        // parsed, exited 0, wrote nothing and said nothing. Declaring it only on the generated tool
        // commands turns that into Clikt's own parse-time refusal — the same answer `--wait` gets, one
        // phase earlier.
        for (lifecycle in listOf("project", "backend", "version", "help")) {
            val error = assertIs<DevrigCommand.DevrigCommandParseError>(
                parse(lifecycle, "--out=/tmp/x.png"),
                "'devrig $lifecycle --out' must be refused, not silently accepted",
            )
            assertTrue("--out" in error.text, "the refusal must name the flag; got:\n${error.text}")
        }
    }

    @Test
    fun `--out follows its tool command rather than preceding it`() {
        // The accepted cost of scoping the declaration: the root no longer accepts `--out`, so it must be
        // written after the command it applies to. Pinned so the narrowing is a decision and not a
        // surprise.
        assertEquals(
            Path.of("/tmp/shot.png"),
            assertIs<DevrigCommand.RunTool>(
                parse("take_screenshot", "--task_id=t1", "--reason=look", "--out=/tmp/shot.png", "--project_name=key"),
            ).out,
        )
        assertIs<DevrigCommand.DevrigCommandParseError>(
            parse("--out=/tmp/shot.png", "take_screenshot", "--task_id=t1", "--reason=look", "--project_name=key"),
        )
    }

    @Test
    fun `--out is rejected on a tool command whose result carries no image`() {
        // A2: --out is scoped by CliCommandSpec.producesImage. Only take_screenshot and execute_code
        // return an image, so every other tool command refuses --out as an unknown option at parse time,
        // rather than accepting it and failing 65 "result carries no image" after a pointless tool call.
        val nonImageTools =
            listOf("list_projects", "list_windows", "open_project", "input", "fetch_resource", "execute_feedback")
        for (tool in nonImageTools) {
            val error = assertIs<DevrigCommand.DevrigCommandParseError>(
                parse(tool, "--out=/tmp/x.png"),
                "'devrig $tool --out' must be refused: its result carries no image",
            )
            assertTrue("--out" in error.text, "the refusal must name the flag; got:\n${error.text}")
        }
    }

    @Test
    fun `--out is accepted on execute_code, the other command whose result can carry an image`() {
        // The complement of the take_screenshot case above: execute_code also marks producesImage, so its
        // command must declare --out too — a script's logImage or a dialog-failure screenshot fills it.
        assertEquals(
            Path.of("/tmp/x.png"),
            assertIs<DevrigCommand.RunTool>(
                parse("execute_code", "--project_name=key", "--code=x", "--task_id=t", "--reason=r", "--out=/tmp/x.png"),
            ).out,
        )
    }
}
