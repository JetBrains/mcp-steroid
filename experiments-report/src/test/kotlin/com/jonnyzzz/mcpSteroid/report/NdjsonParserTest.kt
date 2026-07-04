package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NdjsonParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing $name" }
            .bufferedReader().readText()

    @Test
    fun `extracts model, agent version, token budget, usage, cost and tool calls from a real ndjson`() {
        val m = NdjsonParser.parse(fixture("agent-claude-ndjson-sample.ndjson"))

        assertEquals("claude-opus-4-6", m.model)
        assertEquals("2.1.119", m.agentVersion)
        assertEquals(200_000L, m.contextWindow, "token budget = context window")
        assertEquals(64_000L, m.maxOutputTokens)
        assertEquals(23L, m.inputTokens)
        assertEquals(21_064L, m.outputTokens)
        assertEquals(1.4679594999999999, m.costUsd!!, 1e-9)

        // tool_use blocks counted by name across assistant records present in the fixture
        assertEquals(2, m.toolCalls["Read"])
        assertEquals(1, m.toolCalls["ToolSearch"])
        assertEquals(1, m.toolCalls["mcp__mcp-steroid__steroid_execute_code"])
    }

    @Test
    fun `counts tool calls from the codex item-based stream format`() {
        val m = NdjsonParser.parse(fixture("agent-codex-ndjson-sample.ndjson"))
        // codex shell commands, file changes, and MCP tool calls all become tool-call counts
        assertEquals(2, m.toolCalls["shell"])
        assertEquals(1, m.toolCalls["file_change"])
        assertEquals(1, m.toolCalls["steroid_execute_code"], "mcp tool call counted by its tool name")
    }

    @Test
    fun `tolerates an empty or junk stream`() {
        val m = NdjsonParser.parse("\n  \nnot json\n")
        assertEquals(null, m.model)
        assertEquals(emptyMap(), m.toolCalls)
    }
}
