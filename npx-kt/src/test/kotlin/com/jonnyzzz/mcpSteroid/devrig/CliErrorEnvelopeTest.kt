/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRouteNotFoundException
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import java.io.IOException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The frozen exit-code table and envelope shape, asserted through the ONE runtime error-mapping pipeline
 * every generated command shares. Each case drives a real tool spec whose handler double fails in a
 * specific way, so the mapping is proven where it actually happens — around
 * [com.jonnyzzz.mcpSteroid.devrig.server.callToolViaSpec], which deliberately lets a tool's exception
 * propagate rather than collapsing it into a generic `isError` result.
 *
 * A parse-time usage failure is NOT in this table: it never reaches the runtime, because
 * [parseDevrigCommand] turns it into [DevrigCommand.DevrigCommandParseError] and [runCli] answers 64 there
 * (pinned by `DevrigCommandOutputTest`). That split is deliberate — the runtime pipeline must stay free of
 * Clikt, so it cannot and must not catch a `UsageError`.
 */
class CliErrorEnvelopeTest {

    @TempDir
    lateinit var home: Path

    private class ThrowingListWindows(private val failure: () -> Nothing) : ListWindowsToolHandler {
        override suspend fun collectListWindowsResponse(): ListWindowsResponse = failure()
    }

    private class FixedExecuteCode(private val result: ToolCallResult) : ExecuteCodeToolHandler {
        override suspend fun executeCode(
            projectName: String,
            execCodeParams: ExecCodeParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult = result
    }

    private fun listWindowsFailing(failure: () -> Nothing) =
        FakeMcpSteroidTools().with(ListWindowsToolHandler::class.java, ThrowingListWindows(failure))

    /** Runs `list_windows --json` against a handler that fails with [failure] and returns the outcome. */
    private fun failing(failure: () -> Nothing): GeneratedToolRun =
        runGeneratedToolForTest(home, parseRunTool("list_windows", "--json"), listWindowsFailing(failure))

    /** The single message text an error envelope carries. */
    private fun GeneratedToolRun.errorMessage(): String =
        envelope().getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("text").jsonPrimitive.content

    private fun GeneratedToolRun.assertIsErrorEnvelope(command: String) {
        assertEquals(command, envelope().getValue("command").jsonPrimitive.content)
        assertEquals(
            true,
            envelope().getValue("isError").jsonPrimitive.content.toBoolean(),
            "a failure must be reported as isError=true; stdout was:\n$stdout",
        )
    }

    // ------------------------------------- 64 USAGE -------------------------------------

    @Test
    fun `an unknown project_name exits 64 and points at list_projects`() {
        val run = failing { throw ProjectRouteNotFoundException("ghost-abc123") }

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("list_windows")
        // The whole message, because the CLI must REWORD the exception rather than append to it: the
        // exception's own text tells an MCP client to call `steroid_list_projects`, which a CLI user cannot
        // do, and carrying both instructions gave one action two conflicting spellings.
        assertEquals(
            "project_name 'ghost-abc123' is not open — run `devrig list_projects` to see the valid " +
                "project_name keys",
            run.errorMessage(),
        )
        assertTrue(
            "steroid_list_projects" !in run.errorMessage(),
            "a CLI failure must not advise calling an MCP tool; got: ${run.errorMessage()}",
        )
    }

    @Test
    fun `a rejected argument exits 64`() {
        val run = failing { throw IllegalArgumentException("window_id must be positive") }

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("list_windows")
        assertTrue(run.errorMessage().contains("window_id must be positive"), "got: ${run.errorMessage()}")
    }

    // ------------------------------------- 65 DATA_ERROR -------------------------------------

    @Test
    fun `unusable data from the backend exits 65`() {
        // A SerializationException IS an IllegalArgumentException, so ordering the catches wrongly would
        // silently report a malformed backend payload as the caller's usage mistake.
        val run = failing { throw SerializationException("Unexpected JSON token at offset 0") }

        assertEquals(CliExit.DATA_ERROR, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("list_windows")
        assertTrue(run.errorMessage().contains("Unexpected JSON token"), "got: ${run.errorMessage()}")
    }

    // ------------------------------------- 69 UNAVAILABLE -------------------------------------

    @Test
    fun `an unreachable backend exits 69`() {
        val run = failing { throw IllegalStateException("Connection refused: no IDE is running") }

        assertEquals(CliExit.UNAVAILABLE, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("list_windows")
        assertTrue(run.errorMessage().contains("Connection refused"), "got: ${run.errorMessage()}")
    }

    @Test
    fun `an IO failure from the bridge is a bridge failure, not a filesystem failure`() {
        // Ktor reports a refused connection as an IOException. Mapping every IOException to IO_ERROR 74
        // would therefore report "no IDE running" as a disk problem, so the tool call maps it to 69 and
        // only the CLI's own file reading maps to 74 (see CliFileSourceRuntimeTest).
        val run = failing { throw IOException("Connect timeout has expired") }

        assertEquals(CliExit.UNAVAILABLE, run.exit, "stdout was:\n${run.stdout}")
    }

    // ------------------------------------- 1 TOOL_ERROR -------------------------------------

    @Test
    fun `a tool result with isError exits 1 and keeps the tool's own content`() {
        val tools = FakeMcpSteroidTools().with(
            ExecuteCodeToolHandler::class.java,
            FixedExecuteCode(
                ToolCallResult(content = listOf(ContentItem.Text("compilation failed: line 3")), isError = true),
            ),
        )
        val command = parseRunTool(
            "execute_code", "--json", "--project_name=demo", "--code=1", "--task_id=t", "--reason=r",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.TOOL_ERROR, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("execute_code")
        assertEquals("compilation failed: line 3", run.errorMessage())
    }

    // ------------------------------------- 0 OK -------------------------------------

    @Test
    fun `a successful tool result exits 0`() {
        val tools = FakeMcpSteroidTools().with(
            ExecuteCodeToolHandler::class.java,
            FixedExecuteCode(ToolCallResult(content = listOf(ContentItem.Text("done")))),
        )
        val command = parseRunTool(
            "execute_code", "--json", "--project_name=demo", "--code=1", "--task_id=t", "--reason=r",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals(false, run.envelope().getValue("isError").jsonPrimitive.content.toBoolean())
    }

    // ------------------------------------- cancellation -------------------------------------

    @Test
    fun `a cancellation propagates and is never rendered as a failure`() {
        // Swallowing it would both report a shutdown as a tool failure and stop structured concurrency
        // from unwinding the surrounding scope.
        assertFailsWith<CancellationException> {
            failing { throw CancellationException("devrig is shutting down") }
        }
    }

    // --------------------------- an extra option nothing acts on yet ---------------------------

    @Test
    fun `an extra option no runtime acts on yet fails loudly instead of being ignored`() {
        // `--wait` is generated from open_project's declaration and parses today, but the polling that
        // gives it meaning does not exist yet. Ignoring it would be invisible: the caller asked devrig to
        // wait for the project, devrig would open it, return 0, and never wait. Derived from the
        // declaration, so it names the flag the user typed and needs no per-tool knowledge — and it stops
        // applying by itself once a runtime consumes the option.
        val command = parseRunTool(
            "open_project", "--json", "--project_path=/tmp/p", "--backend_name=b",
            "--task_id=t", "--reason=r", "--wait",
        )

        val run = runGeneratedToolForTest(home, command, FakeMcpSteroidTools())

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("open_project")
        assertEquals(
            "devrig open_project: --wait is accepted by the command line but no runtime acts on it yet — " +
                "drop it and the command runs",
            run.errorMessage(),
            "the whole message is asserted, not just the flag: the first version of this rule said " +
                "'devrig open_project:' twice, because the pipeline already prefixes it",
        )
    }

    // ------------------------------------- console mode -------------------------------------

    @Test
    fun `a failure in console mode keeps stdout clean and still exits with its code`() {
        val run = runGeneratedToolForTest(
            home,
            parseRunTool("list_windows"),
            listWindowsFailing { throw IllegalStateException("Connection refused") },
        )

        assertEquals(CliExit.UNAVAILABLE, run.exit)
        assertEquals("", run.stdout, "a CLI-level failure must never write to stdout; got:\n${run.stdout}")
    }
}
