/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExecuteFeedbackToolSpec.handle] argument validation.
 *
 * The handler reports the first missing/invalid field via early return; each
 * test exercises one validation branch and prints the actual error text on
 * failure to make regressions self-explaining.
 */
class ExecuteFeedbackToolHandlerTest {
    private fun validate(args: JsonObject): String? {
        val result = runBlocking {
            ExecuteFeedbackToolSpec {
                object : ExecuteFeedbackToolHandler {
                    override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult {
                        return ToolCallResult.successTextResult("Success")
                    }
                }
            }.handle(args)
        }

        return if (result.isError) {
            result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        } else {
            null
        }
    }

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
    fun `missing project_name is reported`() {
        val err = validate(
            buildJsonObject {
                put("task_id", "t-1")
                put("success_rating", 0.5)
                put("explanation", "half worked")
            }
        )
        assertNotNull(err)
        assertTrue(err!!.contains("project_name"), "mentions project_name: $err")
    }

    @Test
    fun `empty args is rejected with the first missing field`() {
        // Handler short-circuits on the first missing required field, so an
        // empty args object surfaces project_name (the first check).
        val err = validate(buildJsonObject { })
        assertNotNull(err)
        assertTrue(err!!.contains("project_name"), "mentions project_name: $err")
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
        assertTrue(err!!.contains("success_rating"), "mentions success_rating: $err")
        assertTrue(err.contains("rating`"), "hints against `rating`: $err")
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
        assertTrue(err!!.contains("1.7"), "names the offending value: $err")
        assertTrue(err.contains("0.00..1.00"), "gives the allowed range: $err")
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
        assertTrue(err!!.contains("explanation"), "mentions explanation: $err")
    }

    @Test
    fun `missing explanation is reported`() {
        val err = validate(
            buildJsonObject {
                put("project_name", "proj")
                put("task_id", "t-1")
                put("success_rating", 0.9)
                // explanation missing
            }
        )
        assertNotNull(err)
        assertTrue(err!!.contains("explanation"), "mentions explanation: $err")
    }
}
