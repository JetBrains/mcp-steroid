/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRouteNotFoundException
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The frozen exit-code table and envelope shape, asserted through the ONE runtime error-mapping pipeline
 * every generated command shares. Each case drives a real tool spec whose handler double fails in a
 * specific way, so the mapping is proven where it actually happens — around
 * [com.jonnyzzz.mcpSteroid.devrig.server.callToolViaSpec], which deliberately lets a tool's exception
 * propagate rather than collapsing it into a generic `isError` result.
 *
 * A parse-time usage failure is NOT in this table: it never reaches the runtime, because
 * [parseDevrigCommand] turns it into an informational parse-error invocation that answers 64 there
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

    private class CountingExecuteCode : ExecuteCodeToolHandler {
        var called: Boolean = false

        override suspend fun executeCode(
            projectName: String,
            execCodeParams: ExecCodeParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult {
            called = true
            return ToolCallResult(content = listOf(ContentItem.Text("ran")))
        }
    }

    private class CountingListWindows : ListWindowsToolHandler {
        var called: Boolean = false

        override suspend fun collectListWindowsResponse(): ListWindowsResponse {
            called = true
            return ListWindowsResponse(windows = emptyList(), backgroundTasks = emptyList())
        }
    }

    private class CountingListProjects : ListProjectsToolHandler {
        var called: Boolean = false

        override suspend fun collectListProjectsResponse(): ListProjectsResponse {
            called = true
            return ListProjectsResponse(projects = emptyList())
        }
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
        // Whole string, so the `devrig <command>: ` prefix this arm adds is pinned HERE and not only
        // incidentally by the --wait test. That exact prefix is the one that shipped doubled.
        assertEquals("devrig list_windows: window_id must be positive", run.errorMessage())
    }

    @Test
    fun `an unhandled extra option fails before tool and out filesystem side effects`() {
        // A synthetic extra option no runtime name-keyed handler (like the `--wait` poll) ever consumes.
        // `execute_code` declares no extra options at all, so `SchemaCliBinding` could never produce this
        // on a real parse — this hand-builds the invocation to prove the RUNTIME'S OWN guard rejects it
        // before either the image-producing tool or --out preflight can have an observable side effect.
        val outParent = home.resolve("unsupported-extra-output")
        val command = GeneratedToolInvocation(
            toolName = "steroid_execute_code",
            commandName = "execute_code",
            extraOptions = mapOf("phantom" to true),
            out = outParent.resolve("result.png"),
            json = true,
        )
        val handler = CountingExecuteCode()
        val tools = FakeMcpSteroidTools().with(ExecuteCodeToolHandler::class.java, handler)

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("execute_code")
        assertTrue("phantom" in run.errorMessage(), run.errorMessage())
        assertFalse(handler.called, "an unsupported orchestration flag must fail before the tool can have side effects")
        assertFalse(Files.exists(outParent), "unsupported options must fail before --out creates its parent directory")
    }

    @Test
    fun `an argument the tool itself rejects exits 64 and never blames the backend`() {
        // The rejection the SCHEMA layer raises — `McpSchema`'s `required()` parser for an absent required
        // parameter, and its enum parser for an unknown value. Both throw ToolCallErrorException, which
        // extends RuntimeException and NOT IllegalArgumentException, so before it had its own arm every
        // tool-side rejection fell through to the catch-all and was reported as an unreachable IDE at
        // exit 69.
        //
        // The command-line parser now demands every required scalar, `project_name` included, so a real
        // parse can never reach the schema layer with one absent. This test therefore hand-builds a RunTool
        // that omits `project_name` — the parser cannot produce it, but the schema layer's own `required()`
        // rejection must still map to this arm. That is defense in depth: it keeps this arm proven for any
        // required parameter a future parser might not enforce itself.
        //
        // Driven through the REAL spec rather than a throwing double: what is under test is that the
        // schema layer's own rejection reaches this arm, and a double would prove only the arm exists.
        val command = GeneratedToolInvocation(
            toolName = "steroid_execute_code",
            commandName = "execute_code",
            arguments = buildJsonObject {
                put("code", "1")
                put("task_id", "t")
                put("reason", "r")
            },
            json = true,
        )

        val run = runGeneratedToolForTest(
            home,
            command,
            FakeMcpSteroidTools().with(
                ExecuteCodeToolHandler::class.java,
                FixedExecuteCode(ToolCallResult(content = listOf(ContentItem.Text("must never run")))),
            ),
        )

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("execute_code")
        assertEquals(
            "devrig execute_code: Parameter project_name of type string is required — run " +
                "`devrig execute_code --help` for the flags this command accepts",
            run.errorMessage(),
        )
        assertTrue(
            "no IDE backend is reachable" !in run.errorMessage(),
            "an argument the tool refused says nothing about the backend; got: ${run.errorMessage()}",
        )
    }

    @Test
    fun `an unknown enum value the tool rejects is the same 64`() {
        // The schema layer's second ToolCallErrorException site. `--modal` is validated by Clikt's choice
        // conversion first, so this drives the tool's own parser directly with an argument the CLI cannot
        // produce — the arm must classify the exception, not the flag that happened to raise it.
        val run = failing { throw ToolCallErrorException("Unknown value 'x' for modal. Expected one of: a, b") }

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        assertEquals(
            "devrig list_windows: Unknown value 'x' for modal. Expected one of: a, b — run " +
                "`devrig list_windows --help` for the flags this command accepts",
            run.errorMessage(),
        )
    }

    // ------------------------------------- 65 DATA_ERROR -------------------------------------

    @Test
    fun `unusable data from the backend exits 65`() {
        // A SerializationException IS an IllegalArgumentException, so ordering the catches wrongly would
        // silently report a malformed backend payload as the caller's usage mistake.
        val run = failing { throw SerializationException("Unexpected JSON token at offset 0") }

        assertEquals(CliExit.DATA_ERROR, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("list_windows")
        assertEquals(
            "devrig list_windows could not read the backend's response: Unexpected JSON token at offset 0",
            run.errorMessage(),
        )
    }

    // ------------------------------------- 69 UNAVAILABLE -------------------------------------

    @Test
    fun `an unreachable backend exits 69`() {
        // A refused bridge connection surfaces as an IOException — the one failure the frozen table maps to
        // UNAVAILABLE. The catch arm is scoped to IOException precisely so this stays the ONLY thing reported
        // as an unreachable IDE; a non-IO fault propagates instead (see the propagation test below).
        val run = failing { throw IOException("Connection refused: no IDE is running") }

        assertEquals(CliExit.UNAVAILABLE, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("list_windows")
        assertEquals(
            "devrig list_windows did not complete: no IDE backend is reachable " +
                "(IOException: Connection refused: no IDE is running) — check `devrig list_projects`.",
            run.errorMessage(),
        )
    }

    @Test
    fun `an IO failure from the bridge is a bridge failure, not a filesystem failure`() {
        // Ktor reports a refused connection as an IOException. Mapping every IOException to IO_ERROR 74
        // would therefore report "no IDE running" as a disk problem, so the tool call maps it to 69 and
        // only the CLI's own file reading maps to 74 (see CliFileSourceRuntimeTest).
        val run = failing { throw IOException("Connect timeout has expired") }

        assertEquals(CliExit.UNAVAILABLE, run.exit, "stdout was:\n${run.stdout}")
        assertEquals(
            "devrig list_windows did not complete: no IDE backend is reachable " +
                "(IOException: Connect timeout has expired) — check `devrig list_projects`.",
            run.errorMessage(),
        )
    }

    @Test
    fun `an invalid --out parent fails before the stateful tool is called`() {
        val parentFile = home.resolve("not-a-directory")
        Files.writeString(parentFile, "blocker")
        val target = parentFile.resolve("shot.png")
        val handler = CountingExecuteCode()
        val command = parseRunTool(
            "execute_code", "--json", "--project_name=demo", "--code=1", "--task_id=t", "--reason=r",
            "--out=$target",
        )

        val run = runGeneratedToolForTest(
            home,
            command,
            FakeMcpSteroidTools().with(ExecuteCodeToolHandler::class.java, handler),
        )

        assertEquals(CliExit.IO_ERROR, run.exit, "stdout was:\n${run.stdout}")
        assertFalse(handler.called, "a deterministic --out path failure must be detected before execute_code runs")
        assertTrue("failed to prepare --out" in run.errorMessage(), run.errorMessage())
    }

    @Test
    fun `--out on a successful result writes the image and reports savedOut under --json`() {
        // The headline `--out shot.png && open shot.png` scenario, driven through the whole dispatcher
        // (parse → runGeneratedToolCommand → renderWithOut) — not the renderer unit alone, so a
        // regression in the preparedOut wiring cannot stay green.
        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        val target = home.resolve("captures/shot.png")
        val tools = FakeMcpSteroidTools().with(
            ExecuteCodeToolHandler::class.java,
            FixedExecuteCode(ToolCallResult(content = listOf(ContentItem.Image(data = base64, mimeType = "image/png")))),
        )
        val command = parseRunTool(
            "execute_code", "--json", "--project_name=demo", "--code=1", "--task_id=t", "--reason=r",
            "--out=$target",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertContentEquals(imageBytes, Files.readAllBytes(target), "the decoded image bytes must land at --out")
        val data = run.envelope().getValue("data").jsonObject
        assertEquals(
            target.toAbsolutePath().normalize().toString(),
            data.getValue("savedOut").jsonPrimitive.content,
            "savedOut must report the one normalized path the bytes were written to",
        )
        assertTrue(
            base64 !in run.stdout,
            "the redirected image must not ALSO travel in the envelope; stdout was:\n${run.stdout}",
        )
    }

    @Test
    fun `--out on a successful result prints the saved path on the console`() {
        val imageBytes = byteArrayOf(9, 8, 7)
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        val target = home.resolve("captures/console-shot.png")
        val tools = FakeMcpSteroidTools().with(
            ExecuteCodeToolHandler::class.java,
            FixedExecuteCode(ToolCallResult(content = listOf(ContentItem.Image(data = base64, mimeType = "image/png")))),
        )
        val command = parseRunTool(
            "execute_code", "--project_name=demo", "--code=1", "--task_id=t", "--reason=r", "--out=$target",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertContentEquals(imageBytes, Files.readAllBytes(target), "the decoded image bytes must land at --out")
        assertTrue(
            "Saved --out: ${target.toAbsolutePath().normalize()}" in run.stdout,
            "the console must name the saved path; stdout was:\n${run.stdout}",
        )
        // The redirected image must not ALSO be materialized under the home tmp dir the console
        // presentation uses for images it renders itself.
        val tmp = home.resolve("tmp")
        if (Files.isDirectory(tmp)) {
            Files.list(tmp).use { entries ->
                assertEquals(0, entries.count(), "no image copy may land under tmpDir when --out redirects it")
            }
        }
    }

    // ------------------- internal faults propagate, they are not mapped to 69 -------------------

    @Test
    fun `an internal fault propagates instead of being reported as an unreachable IDE`() {
        // The catch arm used to be `catch (e: Exception)`, so a genuine devrig/handler bug — an NPE, a
        // broken invariant — was mapped to UNAVAILABLE: the user was told to check their IDE for a devrig
        // bug, and the stack trace was discarded. The arm is now scoped to IOException; every other
        // throwable propagates out of the dispatcher to `runCliWithLastResortHandling` (Main.kt), which
        // prints the trace and returns the last-resort code. This proves it leaves the dispatcher rather
        // than being swallowed; the last-resort mapping itself is pinned by LastResortCrashHandlerTest.
        assertFailsWith<NullPointerException> { failing { throw NullPointerException("windows was null") } }
        assertFailsWith<IllegalStateException> { failing { throw IllegalStateException("broken invariant") } }
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

    @Test
    fun `--wait never polls when open_project itself returns isError`() {
        // OpenProjectToolSpec.call() rejects a non-existent path with its own ToolCallResult.errorResult(...)
        // BEFORE ever reaching an OpenProjectToolHandler — so no handler double is registered here at all.
        // If the runtime's `--wait` handling ever regressed to reach the handler anyway, FakeMcpSteroidTools
        // would fail loudly ("no test double is registered") rather than silently passing.
        val listProjects = CountingListProjects()
        val tools = FakeMcpSteroidTools().with(ListProjectsToolHandler::class.java, listProjects)
        val missingPath = home.resolve("does-not-exist")
        val command = parseRunTool(
            "open_project", "--json", "--project_path=$missingPath", "--task_id=t", "--reason=r", "--wait",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.TOOL_ERROR, run.exit, "stdout was:\n${run.stdout}")
        run.assertIsErrorEnvelope("open_project")
        assertEquals("ERROR: Project path is not a directory: $missingPath", run.errorMessage())
        assertFalse(listProjects.called, "a failed open_project must never trigger the --wait poll")
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

    // ------------------------------------- console mode -------------------------------------

    @Test
    fun `a failure in console mode keeps stdout clean and still exits with its code`() {
        val run = runGeneratedToolForTest(
            home,
            parseRunTool("list_windows"),
            listWindowsFailing { throw IOException("Connection refused") },
        )

        assertEquals(CliExit.UNAVAILABLE, run.exit)
        assertEquals("", run.stdout, "a CLI-level failure must never write to stdout; got:\n${run.stdout}")
    }
}
