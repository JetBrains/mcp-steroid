/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Pins progress behavior at the generated-command boundary where agents invoke devrig. */
class GeneratedToolProgressTest {

    @TempDir
    lateinit var home: Path

    private class ProgressingExecuteCode : ExecuteCodeToolHandler {
        override suspend fun executeCode(
            projectName: String,
            execCodeParams: ExecCodeParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult {
            callProgress.report("Tool call started: steroid_execute_code")
            callProgress.report("Compiler output: compiling")
            return ToolCallResult(content = listOf(ContentItem.Text("EXECUTE_OK")))
        }
    }

    private fun run(json: Boolean): GeneratedToolRun {
        val args = mutableListOf(
            "execute_code",
            "--project_name=demo-key",
            "--code=println(1)",
            "--task_id=progress-test",
            "--reason=verify progress routing",
        )
        if (json) args += "--json"
        val tools = FakeMcpSteroidTools().with(ExecuteCodeToolHandler::class.java, ProgressingExecuteCode())
        return runGeneratedToolForTest(home, parseRunTool(*args.toTypedArray()), tools)
    }

    @Test
    fun `json mode suppresses live progress so agent output is one document`() {
        val run = run(json = true)

        assertEquals(CliExit.OK, run.exit)
        run.envelope()
        assertEquals("", run.stderr, "agent shells commonly merge stderr into their command result")
        assertTrue("Tool call started" !in run.stdout, run.stdout)
        assertTrue("Compiler output" !in run.stdout, run.stdout)
    }

    @Test
    fun `human mode keeps translated live progress on stderr`() {
        val run = run(json = false)

        assertEquals(CliExit.OK, run.exit)
        assertEquals("EXECUTE_OK\n", run.stdout)
        assertEquals(
            "Tool call started: devrig execute_code\nCompiler output: compiling\n",
            run.stderr,
        )
    }
}
