/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArenaProjectResolutionDetectorTest {

    // Real captured Claude shape (…/run-20260708-…-petclinic-27-claude-mcp/agent-claude-code-1-raw.ndjson:5):
    // an ERROR:-wrapped resolution failure whose tool_result references the steroid_execute_code call id.
    private val claudeCall =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_01Woi","name":"mcp__mcp-steroid__steroid_execute_code","input":{"code":"println(1)"}}]}}"""
    private val claudeErrorResult =
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"ERROR: Project not found: \"project-home\". Available project_name values: demo-project-ki3y1pmc","is_error":true,"tool_use_id":"toolu_01Woi"}]}}"""

    @Test
    fun `flags a Project not found error attributed to steroid_execute_code (claude)`() {
        val ndjson = "$claudeCall\n$claudeErrorResult\n"
        assertTrue(detectProjectResolutionFailure(ndjson))
    }

    @Test
    fun `ignores the phrase when it comes from a steroid_fetch_resource article body`() {
        val fetchCall =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_FETCH","name":"mcp__mcp-steroid__steroid_fetch_resource","input":{"uri":"mcp-steroid://x"}}]}}"""
        // Article body quotes the phrase but is NOT an error and maps to fetch_resource, not execute_code.
        val fetchResult =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"the error reads: Project not found: \"foo\"","is_error":false,"tool_use_id":"toolu_FETCH"}]}}"""
        val ndjson = "$fetchCall\n$fetchResult\n"
        assertFalse(detectProjectResolutionFailure(ndjson))
    }

    @Test
    fun `does not flag an is_error fetch_resource result that matches the prefix (attribution)`() {
        // A steroid_fetch_resource CALL followed by an is_error:true result that DOES match the
        // "Project not found" prefix. This exercises the id->tool-name attribution directly: the
        // result passes the is_error gate and the prefix match, so it is only rejected because the
        // tool_use_id maps to fetch_resource, not execute_code. Deleting the endsWith(execute_code)
        // check makes THIS test fail.
        val fetchCall =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_FETCH2","name":"mcp__mcp-steroid__steroid_fetch_resource","input":{"uri":"mcp-steroid://x"}}]}}"""
        val fetchErrorResult =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"ERROR: Project not found: \"x\". Available project_name values: foo-abc","is_error":true,"tool_use_id":"toolu_FETCH2"}]}}"""
        assertFalse(detectProjectResolutionFailure("$fetchCall\n$fetchErrorResult\n"))
    }

    @Test
    fun `flags a doubled Error prefix attributed to steroid_execute_code`() {
        // The plugin can surface the payload with a doubled wrapper (Error: ERROR: ...);
        // stripErrorPrefixes must peel both before the exact-prefix match.
        val doubledResult =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Error: ERROR: Project not found: \"project-home\". Available project_name values: demo-project-ki3y1pmc","is_error":true,"tool_use_id":"toolu_01Woi"}]}}"""
        assertTrue(detectProjectResolutionFailure("$claudeCall\n$doubledResult\n"))
    }

    @Test
    fun `flags a content-block-array error attributed to steroid_execute_code`() {
        // Anthropic tool_result.content may arrive as a content-block ARRAY, not a bare string.
        // textOf() must dig the text out of the array so the resolution failure is not silently missed.
        val arrayErrorResult =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":[{"type":"text","text":"ERROR: Project not found: \"project-home\". Available project_name values: demo-project-ki3y1pmc"}],"is_error":true,"tool_use_id":"toolu_01Woi"}]}}"""
        assertTrue(detectProjectResolutionFailure("$claudeCall\n$arrayErrorResult\n"))
    }

    @Test
    fun `does not flag a clean run`() {
        val okResult =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Project: demo, base: /home/agent/project-home","is_error":false,"tool_use_id":"toolu_01Woi"}]}}"""
        assertFalse(detectProjectResolutionFailure("$claudeCall\n$okResult\n"))
    }

    @Test
    fun `flags gemini-shaped resolution failure`() {
        val call =
            """{"type":"tool_use","id":"g1","tool_name":"mcp_mcp-steroid_steroid_execute_code","parameters":{"code":"x"}}"""
        val err =
            """{"type":"tool_result","tool_id":"g1","is_error":true,"content":"ERROR: Project not found: \"project-home\". Available project_name values: foo-abc"}"""
        assertTrue(detectProjectResolutionFailure("$call\n$err\n"))
    }

    @Test
    fun `flags codex-shaped resolution failure`() {
        val line =
            """{"type":"item.completed","item":{"id":"item_5","type":"mcp_tool_call","server":"mcp-steroid","tool":"steroid_execute_code","result":{"content":[{"type":"text","text":"ERROR: Project not found: \"project-home\". Available project_name values: foo-abc"}]}}}"""
        assertTrue(detectProjectResolutionFailure("$line\n"))
    }
}
