/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ExecuteFeedbackToolHandler.validate].
 *
 * Pins the aggregate-error contract introduced after the 2026-04-24
 * INFRA-REPORT — a call missing multiple required fields must surface ALL of
 * them in a single response rather than one at a time.
 */
class ExecuteFeedbackToolHandlerTest {
    private fun validate(args: JsonObject): String? =
        ExecuteFeedbackToolHandler.validate(args)

    @Test
    fun `valid args produce no error`() {
        val err = validate(
            buildJsonObject {
                put("project_name", "proj")
                put("task_id", "t-1")
                put("success_rating", 0.75)
                put("explanation", "worked end-to-end")
            }
        )
        assertNull(err)
    }

    @Test
    fun `missing project_name only — singular header`() {
        val err = validate(
            buildJsonObject {
                put("task_id", "t-1")
                put("success_rating", 0.5)
                put("explanation", "half worked")
            }
        )
        assertNotNull(err)
        assertTrue("mentions project_name: $err", err!!.contains("project_name"))
        assertTrue("singular 'problem': $err", err.contains("1 validation problem:"))
    }

    @Test
    fun `all four required missing — all reported together`() {
        // Caller sends an empty args object. Before the fix, this produced FOUR
        // sequential rejections; after the fix, one message names all four gaps.
        val err = validate(buildJsonObject { })
        assertNotNull(err)
        assertTrue("mentions project_name: $err", err!!.contains("project_name"))
        assertTrue("mentions task_id: $err", err.contains("task_id"))
        assertTrue("mentions success_rating: $err", err.contains("success_rating"))
        assertTrue("mentions explanation: $err", err.contains("explanation"))
        assertTrue("plural header (4 problems): $err", err.contains("4 validation problems:"))
    }

    @Test
    fun `rating instead of success_rating — helpful hint`() {
        // The INFRA-REPORT noted callers send `rating` instead of `success_rating`.
        // The error message should mention success_rating explicitly and point out
        // that `rating` is wrong.
        val err = validate(
            buildJsonObject {
                put("project_name", "proj")
                put("task_id", "t-1")
                put("rating", 0.5)
                put("explanation", "tried rating instead of success_rating")
            }
        )
        assertNotNull(err)
        assertTrue("mentions success_rating: $err", err!!.contains("success_rating"))
        assertTrue("hints against `rating`: $err", err.contains("rating`"))
    }

    @Test
    fun `out-of-range success_rating reports the actual value`() {
        val err = validate(
            buildJsonObject {
                put("project_name", "proj")
                put("task_id", "t-1")
                put("success_rating", 1.7)
                put("explanation", "tried out-of-range")
            }
        )
        assertNotNull(err)
        assertTrue("names the offending value: $err", err!!.contains("1.7"))
        assertTrue("gives the allowed range: $err", err.contains("0.00..1.00"))
    }

    @Test
    fun `blank explanation is rejected`() {
        val err = validate(
            buildJsonObject {
                put("project_name", "proj")
                put("task_id", "t-1")
                put("success_rating", 0.5)
                put("explanation", "   ")
            }
        )
        assertNotNull(err)
        assertTrue("mentions explanation: $err", err!!.contains("explanation"))
    }

    @Test
    fun `error footer lists the required parameter names so the caller can self-serve`() {
        // Even when only one field is missing, the footer lists ALL required fields
        // so the caller doesn't have to check the docs to know what's expected.
        val err = validate(
            buildJsonObject {
                put("project_name", "proj")
                put("task_id", "t-1")
                put("success_rating", 0.9)
                // explanation missing
            }
        )
        assertNotNull(err)
        assertEquals(
            "footer must list required + optional field names",
            true,
            err!!.contains("Required: project_name, task_id, success_rating, explanation"),
        )
    }
}
