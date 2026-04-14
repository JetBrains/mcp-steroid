/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [extractToolCallStats].
 *
 * Validates parsing of Claude NDJSON stream-json output to count steroid_execute_code calls,
 * total tool_use blocks, and tool results with is_error=true.
 */
class ExtractToolCallStatsTest {

    private val extract = ::extractToolCallStats

    @Test
    fun `returns null when output has no assistant events`() {
        assertNull(extract(""))
        assertNull(extract("no json here"))
        assertNull(extract("""{"type":"result","subtype":"success","cost_usd":0.01}"""))
    }

    @Test
    fun `returns null when assistant events have no tool_use`() {
        val output = """
            {"type":"assistant","message":{"content":[{"type":"text","text":"Hello"}]}}
        """.trimIndent()
        assertNull(extract(output))
    }

    @Test
    fun `counts single steroid_execute_code call with full MCP prefix`() {
        val output = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__mcp-steroid__steroid_execute_code","input":{}}]}}
        """.trimIndent()
        val stats = extract(output)
        assertNotNull(stats)
        assertEquals(1, stats!!.steroidCallCount)
        assertEquals(1, stats.totalToolCalls)
        assertEquals(0, stats.toolErrorCount)
    }

    @Test
    fun `counts bare steroid_execute_code name without MCP prefix`() {
        val output = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"steroid_execute_code","input":{}}]}}
        """.trimIndent()
        val stats = extract(output)
        assertNotNull(stats)
        assertEquals(1, stats!!.steroidCallCount)
        assertEquals(1, stats.totalToolCalls)
    }

    @Test
    fun `counts non-steroid tool calls in totalToolCalls but not steroidCallCount`() {
        val output = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/tmp/foo"}},{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}
        """.trimIndent()
        val stats = extract(output)
        assertNotNull(stats)
        assertEquals(0, stats!!.steroidCallCount)
        assertEquals(2, stats.totalToolCalls)
        assertEquals(0, stats.toolErrorCount)
    }

    @Test
    fun `counts tool errors from user events with is_error true`() {
        val output = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{}}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"1","is_error":"true","content":"error"}]}}
        """.trimIndent()
        val stats = extract(output)
        assertNotNull(stats)
        assertEquals(0, stats!!.steroidCallCount)
        assertEquals(1, stats.totalToolCalls)
        assertEquals(1, stats.toolErrorCount)
    }

    @Test
    fun `accumulates counts across multiple assistant turns`() {
        val output = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__mcp-steroid__steroid_execute_code","input":{}},{"type":"tool_use","name":"Read","input":{}}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"1","is_error":"false"},{"type":"tool_result","tool_use_id":"2","is_error":"true"}]}}
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__mcp-steroid__steroid_execute_code","input":{}},{"type":"tool_use","name":"Bash","input":{}}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"3","is_error":"false"},{"type":"tool_result","tool_use_id":"4","is_error":"false"}]}}
        """.trimIndent()
        val stats = extract(output)
        assertNotNull(stats)
        assertEquals(2, stats!!.steroidCallCount)
        assertEquals(4, stats.totalToolCalls)
        assertEquals(1, stats.toolErrorCount)
    }

    @Test
    fun `handles malformed json lines gracefully`() {
        val output = """
            not-json-at-all
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"steroid_execute_code","input":{}}]}}
            {broken json
        """.trimIndent()
        val stats = extract(output)
        assertNotNull(stats)
        assertEquals(1, stats!!.steroidCallCount)
        assertEquals(1, stats.totalToolCalls)
    }

    @Test
    fun `does not count steroid_execute_feedback as steroid_execute_code`() {
        val output = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__mcp-steroid__steroid_execute_feedback","input":{}},{"type":"tool_use","name":"mcp__mcp-steroid__steroid_execute_code","input":{}}]}}
        """.trimIndent()
        val stats = extract(output)
        assertNotNull(stats)
        // Only steroid_execute_code (ends with "steroid_execute_code"), not steroid_execute_feedback
        assertEquals(1, stats!!.steroidCallCount)
        assertEquals(2, stats.totalToolCalls)
    }

    @Test
    fun `none mode produces zero steroid calls when only bash and file tools used`() {
        val output = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"mvn test"}},{"type":"tool_use","name":"Read","input":{"file_path":"/src/Main.java"}},{"type":"tool_use","name":"Write","input":{"file_path":"/src/Main.java","content":"..."}}]}}
        """.trimIndent()
        val stats = extract(output)
        assertNotNull(stats)
        assertEquals(0, stats!!.steroidCallCount)
        assertEquals(3, stats.totalToolCalls)
        assertEquals(0, stats.toolErrorCount)
    }
}
