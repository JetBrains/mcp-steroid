/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Pins the property [callToolViaSpec] exists to guarantee: it calls [McpTool.call] directly and lets
 * whatever it throws propagate, unlike `McpToolRegistry.callTool`, which catches every exception into a
 * generic `isError=true` result. The CLI's exit-code table (USAGE, UNAVAILABLE, DATA_ERROR, ...) is built
 * by catching specific typed exceptions around this call site — that mapping is impossible if the
 * exception never reaches the caller.
 */
class CliToolDispatchTest {

    private class RecordingProgress : McpProgressReporter {
        val messages = mutableListOf<String>()
        override fun report(message: String) {
            messages += message
        }
    }

    private class FakeTool(
        override val name: String = "fake_tool",
        private val onCall: suspend (ToolCallContext) -> ToolCallResult,
    ) : McpTool {
        override val description: String? = null
        override val inputSchema: JsonObject = buildJsonObject { }
        override suspend fun call(context: ToolCallContext): ToolCallResult = onCall(context)
    }

    @Test
    fun `callToolViaSpec calls the tool directly and returns its result unmodified`() = runTest {
        val tool = FakeTool { context ->
            ToolCallResult(content = listOf(ContentItem.Text("saw ${context.params.name}")))
        }
        val progress = RecordingProgress()
        val arguments = buildJsonObject { put("project_name", "demo") }

        val result = callToolViaSpec(tool, arguments, progress)

        assertEquals(listOf(ContentItem.Text("saw fake_tool")), result.content)
    }

    @Test
    fun `callToolViaSpec passes the given arguments through to the tool call`() = runTest {
        var seenArguments: JsonObject? = null
        val tool = FakeTool { context ->
            seenArguments = context.params.arguments
            ToolCallResult(content = emptyList())
        }
        val arguments = buildJsonObject { put("path", "/tmp/x") }

        callToolViaSpec(tool, arguments, RecordingProgress())

        assertEquals(arguments, seenArguments)
    }

    @Test
    fun `callToolViaSpec passes the given progress reporter through to the tool call`() = runTest {
        var seenProgress: McpProgressReporter? = null
        val tool = FakeTool { context ->
            seenProgress = context.mcpProgressReporter
            ToolCallResult(content = emptyList())
        }
        val progress = RecordingProgress()

        callToolViaSpec(tool, buildJsonObject { }, progress)

        assertSame(progress, seenProgress)
    }

    @Test
    fun `callToolViaSpec propagates a thrown exception instead of collapsing it into an error result`() = runTest {
        class BridgeUnavailable : IllegalStateException("no IDE running")

        val tool = FakeTool { throw BridgeUnavailable() }

        assertFailsWith<BridgeUnavailable> {
            callToolViaSpec(tool, buildJsonObject { }, RecordingProgress())
        }
    }
}
