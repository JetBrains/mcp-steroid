/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.ModalMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * `devrig execute_code` end to end: the generated command reaches the real
 * [com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolSpec.call], which builds [ExecCodeParams] from the parsed
 * arguments. The parse-time shape of these flags (bounds, missing hints, code/code-file exclusivity) is
 * already proven in `SchemaToolCliCommandTest` and `SchemaCliBindingTest`; this file is where the built
 * [ExecCodeParams] itself — defaults included — is checked against a handler double, and where the
 * console-mode success path (no `--json`) is proven for a tool whose result is plain text, not a listing.
 */
class ExecuteCodeCommandTest {

    @TempDir
    lateinit var home: Path

    private class RecordingExecuteCode(private val result: ToolCallResult) : ExecuteCodeToolHandler {
        var projectName: String? = null
        var params: ExecCodeParams? = null

        override suspend fun executeCode(
            projectName: String,
            execCodeParams: ExecCodeParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult {
            this.projectName = projectName
            this.params = execCodeParams
            return result
        }
    }

    private fun toolsWith(rec: RecordingExecuteCode) =
        FakeMcpSteroidTools().with(ExecuteCodeToolHandler::class.java, rec)

    @Test
    fun `inline code maps to ExecCodeParams with the spec's own defaults, end to end`() {
        val rec = RecordingExecuteCode(ToolCallResult(content = listOf(ContentItem.Text("42"))))
        val command = parseRunTool(
            "execute_code", "--json",
            "--project_name=demo", "--code=1+1", "--task_id=t1", "--reason=testing",
        )

        val run = runGeneratedToolForTest(home, command, toolsWith(rec))

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("demo", rec.projectName)
        val params = rec.params!!
        assertEquals("t1", params.taskId)
        assertEquals("1+1", params.code)
        assertEquals("testing", params.reason)
        assertEquals(600, params.timeout, "the spec's own default timeout must reach the handler unchanged")
        assertEquals(ModalMode.SMART_NON_MODAL, params.modal, "the spec's own default modal must reach the handler unchanged")
    }

    @Test
    fun `all CLI flags reach the handler as the exact ExecCodeParams the spec builds`() {
        val rec = RecordingExecuteCode(ToolCallResult(content = listOf(ContentItem.Text("ok"))))
        val command = parseRunTool(
            "execute_code", "--json",
            "--project_name=demo", "--code=x", "--task_id=t2", "--reason=r2",
            "--timeout=30", "--modal=unleashed",
        )

        val run = runGeneratedToolForTest(home, command, toolsWith(rec))

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        val params = rec.params!!
        assertEquals("t2", params.taskId)
        assertEquals("x", params.code)
        assertEquals("r2", params.reason)
        assertEquals(30, params.timeout)
        assertEquals(ModalMode.UNLEASHED, params.modal)
    }

    @Test
    fun `a successful result in console mode prints the tool's own text on stdout, with no envelope`() {
        val rec = RecordingExecuteCode(ToolCallResult(content = listOf(ContentItem.Text("compilation ok"))))
        val command = parseRunTool(
            "execute_code", "--project_name=demo", "--code=x", "--task_id=t", "--reason=r",
        )

        val run = runGeneratedToolForTest(home, command, toolsWith(rec))

        assertEquals(CliExit.OK, run.exit)
        assertEquals("compilation ok", run.stdout.trim(), "console mode prints the tool's own text and nothing else")
    }
}
