/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    private fun parse(vararg args: String): DevrigCommand = parseDevrigCommand(args.toList().toTypedArray())

    @Test
    fun `execute_code rejects an unknown --modal value at parse`() {
        val error = assertIs<DevrigCommand.DevrigCommandParseError>(
            parse(
                "execute_code", "--project_name=demo", "--code=x", "--task_id=t", "--reason=r",
                "--modal=bogus",
            ),
        )

        assertTrue("--modal" in error.text, "got:\n${error.text}")
        assertTrue("bogus" in error.text, "got:\n${error.text}")
        for (valid in listOf("smart_non_modal", "non_modal", "unleashed")) {
            assertTrue(valid in error.text, "expected the valid-values listing to include $valid; got:\n${error.text}")
        }
    }

    @Test
    fun `execute_feedback rejects NaN and Infinity success_rating at parse`() {
        for (badValue in listOf("NaN", "Infinity", "-Infinity")) {
            val error = assertIs<DevrigCommand.DevrigCommandParseError>(
                parse(
                    "execute_feedback", "--project_name=demo", "--task_id=t", "--explanation=e",
                    "--success_rating=$badValue",
                ),
                "--success_rating=$badValue must be a parse error, not silently accepted",
            )
            assertTrue("--success_rating" in error.text, "got:\n${error.text}")
        }
    }

    @Test
    fun `input without --window_id and --sequence reports both curated hints in one error`() {
        val error = assertIs<DevrigCommand.DevrigCommandParseError>(
            parse("input", "--project_name=demo", "--task_id=t", "--reason=r"),
        )

        assertTrue(
            "missing required --window_id (get it from" in error.text,
            "expected input's curated window_id hint; got:\n${error.text}",
        )
        assertTrue(
            "missing --sequence" in error.text && "press:CTRL+P" in error.text,
            "expected input's curated multi-line sequence hint with its example; got:\n${error.text}",
        )
        assertFalse(
            "Any string works" in error.text,
            "task_id/reason are supplied here, so their unrelated hint must not appear; got:\n${error.text}",
        )
    }
}
