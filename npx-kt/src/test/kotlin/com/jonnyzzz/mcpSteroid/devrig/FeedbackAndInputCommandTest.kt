/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * `devrig execute_feedback` and `devrig input` end to end: the generated command reaches the real specs
 * ([com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolSpec.call],
 * [com.jonnyzzz.mcpSteroid.server.VisionInputToolSpec.call]), which build [FeedbackParams] / [InputParams]
 * from the parsed arguments. Bounds and missing-hint SHAPE are already proven in `SchemaCliBindingTest`;
 * this file is where the built params reach a handler double.
 */
class FeedbackAndInputCommandTest {

    @TempDir
    lateinit var home: Path

    private class RecordingFeedback : ExecuteFeedbackToolHandler {
        var projectName: String? = null
        var params: FeedbackParams? = null

        override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult {
            this.projectName = projectName
            this.params = params
            return ToolCallResult(content = listOf(ContentItem.Text("recorded")))
        }
    }

    private class RecordingInput : VisionInputToolHandler {
        var projectName: String? = null
        var params: InputParams? = null

        override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult {
            this.projectName = projectName
            this.params = inputParams
            return ToolCallResult(content = listOf(ContentItem.Text("done")))
        }
    }

    @Test
    fun `feedback maps rating, explanation and inline code to FeedbackParams end to end`() {
        val rec = RecordingFeedback()
        val tools = FakeMcpSteroidTools().with(ExecuteFeedbackToolHandler::class.java, rec)
        val command = parseRunTool(
            "execute_feedback", "--json",
            "--project_name=demo", "--task_id=t1", "--execution_id=eid_original", "--success_rating=0.75",
            "--explanation=worked", "--code=val x = 1",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("demo", rec.projectName)
        val params = rec.params!!
        assertEquals("t1", params.taskId)
        assertEquals("eid_original", params.executionId)
        assertEquals(0.75, params.successRating)
        assertEquals("worked", params.explanation)
        assertEquals("val x = 1", params.code)
    }

    @Test
    fun `input forwards the raw sequence verbatim to InputParams for plugin version skew`() {
        val rec = RecordingInput()
        val tools = FakeMcpSteroidTools().with(VisionInputToolHandler::class.java, rec)
        val sequenceText = "press:CTRL+P, type:Main, delay:200, press:ENTER"
        val command = parseRunTool(
            "input", "--json",
            "--project_name=demo", "--window_id=win-1", "--task_id=t", "--reason=r",
            "--sequence=$sequenceText",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("demo", rec.projectName)
        val params = rec.params!!
        assertEquals("win-1", params.windowId)
        assertEquals(
            sequenceText, params.rawSequence,
            "the plugin parses rawSequence using its own version, so the raw string must reach it unchanged",
        )
    }
}
