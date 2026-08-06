/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parse-time shapes not already covered elsewhere: `SchemaCliBindingTest` proves the generic bounds and
 * requiredness mechanisms directly against `BindingCommand`, and `SchemaToolCliCommandTest` proves the
 * curated-missing-hint MECHANISM once (via `execute_code`'s `task_id`). This file is where a few
 * tool-specific parse failures are checked through the real [parseDevrigCommand] entry point: an enum
 * rejection whose valid-values listing must stay in sync with [com.jonnyzzz.mcpSteroid.server.ModalMode],
 * a floating-point bound against values ordinary comparisons mishandle (`NaN`/`Infinity`), and `input`'s
 * two hand-written, multi-line missing hints (unlike `execute_code`'s single-line one, these embed an
 * example invocation and are worth checking survive Clikt's `MultiUsageError` aggregation intact).
 */
class McpAsCliParseTest {

    private fun parseError(vararg args: String): String {
        val invocation = parseDevrigCommand(args.toList().toTypedArray())
        assertEquals("parse-error", invocation.commandPath)
        return requireNotNull(invocation.informationalText)
    }

    @Test
    fun `execute_code rejects an unknown --modal value at parse`() {
        val error = parseError(
            "execute_code", "--project_name=demo", "--code=x", "--task_id=t", "--reason=r",
            "--modal=bogus",
        )

        assertTrue("--modal" in error, "got:\n$error")
        assertTrue("bogus" in error, "got:\n$error")
        for (valid in listOf("smart_non_modal", "non_modal", "unleashed")) {
            assertTrue(valid in error, "expected the valid-values listing to include $valid; got:\n$error")
        }
    }

    @Test
    fun `execute_feedback rejects NaN and Infinity success_rating at parse`() {
        for (badValue in listOf("NaN", "Infinity", "-Infinity")) {
            val error = parseError(
                "execute_feedback", "--project_name=demo", "--task_id=t", "--explanation=e",
                "--success_rating=$badValue",
            )
            assertTrue("--success_rating" in error, "got:\n$error")
        }
    }

    @Test
    fun `input without --window_id and --sequence reports both curated hints in one error`() {
        val error = parseError("input", "--project_name=demo", "--task_id=t", "--reason=r")

        assertTrue(
            "missing required --window_id (get it from" in error,
            "expected input's curated window_id hint; got:\n$error",
        )
        assertTrue(
            "missing --sequence" in error && "press:CTRL+P" in error,
            "expected input's curated multi-line sequence hint with its example; got:\n$error",
        )
        assertFalse(
            "Any string works" in error,
            "task_id/reason are supplied here, so their unrelated hint must not appear; got:\n$error",
        )
    }
}
