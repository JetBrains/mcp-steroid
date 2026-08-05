/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArenaProjectResolutionDetectorTest {

    @Test
    fun `claude first resolution failure remains fatal after a successful retry`() {
        val transcript = decode(
            claudeCall("c1"),
            claudeResult("c1", isError = true, projectNotFound("project-home")),
            claudeCall("c2"),
            claudeResult("c2", isError = false, "execution_id: eid_ok"),
        )

        assertEquals(ProjectResolutionStatus.INITIAL_FAILURE, transcript.projectResolutionStatus)
        assertTrue(transcript.usedMcpSteroid)
        assertTrue(transcript.successfulMcpExecution)
    }

    @Test
    fun `later resolution failure followed by success is recovered`() {
        val transcript = decode(
            claudeCall("c1"),
            claudeResult("c1", isError = false, "execution_id: eid_1"),
            claudeCall("c2"),
            claudeResult("c2", isError = true, projectNotFound("old-key")),
            claudeCall("c3"),
            claudeResult("c3", isError = false, "execution_id: eid_3"),
        )

        assertEquals(ProjectResolutionStatus.RECOVERED, transcript.projectResolutionStatus)
    }

    @Test
    fun `later resolution failure without a successful retry is fatal`() {
        val transcript = decode(
            claudeCall("c1"),
            claudeResult("c1", isError = false, "execution_id: eid_1"),
            claudeCall("c2"),
            claudeResult("c2", isError = true, projectNotFound("old-key")),
        )

        assertEquals(ProjectResolutionStatus.UNRECOVERED_FAILURE, transcript.projectResolutionStatus)
    }

    @Test
    fun `gemini uses real tool_id status output fields`() {
        val transcript = decode(
            geminiCall("g1"),
            geminiResult("g1", status = "error", output = projectNotFound("project-home")),
        )

        assertEquals(ProjectResolutionStatus.INITIAL_FAILURE, transcript.projectResolutionStatus)
        assertEquals(
            ExecuteCodeCall("g1", ExecuteCodeResult(isError = true, text = projectNotFound("project-home"))),
            transcript.executeCodeCalls.single(),
        )
    }

    @Test
    fun `codex failed result is normalized`() {
        val transcript = decode(
            codexResult("x1", status = "failed", text = projectNotFound("project-home")),
        )

        assertEquals(ProjectResolutionStatus.INITIAL_FAILURE, transcript.projectResolutionStatus)
        assertTrue(transcript.executeCodeCalls.single().result?.isError == true)
    }

    @Test
    fun `successful codex output quoting Project not found is not an error`() {
        val transcript = decode(
            codexResult("x1", status = "completed", text = projectNotFound("quoted-example")),
        )

        assertEquals(ProjectResolutionStatus.CLEAN, transcript.projectResolutionStatus)
        assertTrue(transcript.successfulMcpExecution)
    }

    @Test
    fun `fetch resource article body is not attributed to execute code`() {
        val transcript = decode(
            claudeCall("f1", toolName = "mcp__mcp-steroid__steroid_fetch_resource"),
            claudeResult("f1", isError = true, projectNotFound("article-example")),
        )

        assertFalse(transcript.usedMcpSteroid)
        assertTrue(transcript.executeCodeCalls.isEmpty())
        assertEquals(ProjectResolutionStatus.CLEAN, transcript.projectResolutionStatus)
    }

    @Test
    fun `claude content block array and doubled Error wrappers are normalized`() {
        val transcript = decode(
            claudeCall("c1"),
            claudeResult(
                callId = "c1",
                isError = true,
                text = "Error: ERROR: ${projectNotFound("project-home")}",
                contentAsArray = true,
            ),
        )

        assertEquals(ProjectResolutionStatus.INITIAL_FAILURE, transcript.projectResolutionStatus)
    }

    @Test
    fun `claude success without is_error is recorded as successful`() {
        val transcript = decode(
            claudeCall("c1"),
            claudeResult(
                callId = "c1",
                isError = null,
                text = "Project: task-project, base: /home/agent/project-home",
            ),
        )

        assertTrue(transcript.successfulMcpExecution)
        assertEquals(ProjectResolutionStatus.CLEAN, transcript.projectResolutionStatus)
    }

    @Test
    fun `first execute code result must report the expected project base path`() {
        val expectedProjectDir = "/home/agent/project-home"
        val correctProject = decode(
            claudeCall("correct"),
            claudeResult(
                callId = "correct",
                isError = null,
                text = "Project: task-project, base: $expectedProjectDir",
            ),
        )
        val wrongProject = decode(
            claudeCall("wrong"),
            claudeResult(
                callId = "wrong",
                isError = null,
                text = "Project: demo-project, base: /home/agent/demo-project",
            ),
        )

        assertTrue(correctProject.firstExecutionTargetsProject(expectedProjectDir))
        assertFalse(wrongProject.firstExecutionTargetsProject(expectedProjectDir))
    }

    @Test
    fun `a leading parameter-validation rejection is skipped before the mandatory first-call check`() {
        // Observed live (TC build 1022424067): codex omitted the required task_id on its very first
        // call; the schema layer rejected it before any project was resolved, and the corrected retry
        // confirmed the right project. The rejection carries no targeting information, so it must not
        // invalidate the run.
        val expectedProjectDir = "/home/agent/project-home"
        val transcript = decode(
            claudeCall("rejected"),
            claudeResult(
                callId = "rejected",
                isError = true,
                text = "ERROR: Parameter task_id of type string is required",
            ),
            claudeCall("retry"),
            claudeResult(
                callId = "retry",
                isError = null,
                text = "Project: task-project, base: $expectedProjectDir",
            ),
        )

        assertTrue(transcript.firstExecutionTargetsProject(expectedProjectDir))
    }

    @Test
    fun `a leading runtime error keeps the original strictness — the run stays invalid`() {
        // A runtime error means code EXECUTED against some project without confirming which one;
        // unlike a schema-layer rejection, that must still invalidate the run.
        val expectedProjectDir = "/home/agent/project-home"
        val transcript = decode(
            claudeCall("boom"),
            claudeResult(
                callId = "boom",
                isError = true,
                text = "ERROR: java.lang.IllegalStateException: script exploded",
            ),
            claudeCall("retry"),
            claudeResult(
                callId = "retry",
                isError = null,
                text = "Project: task-project, base: $expectedProjectDir",
            ),
        )

        assertFalse(transcript.firstExecutionTargetsProject(expectedProjectDir))
    }

    @Test
    fun `prose mention is not structural MCP usage`() {
        val prose = buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("content", "I should call steroid_execute_code next")
        }.toString()

        val transcript = decode(prose)
        assertFalse(transcript.usedMcpSteroid)
        assertFalse(transcript.successfulMcpExecution)
    }

    private fun decode(vararg lines: String): AgentTranscript =
        decodeAgentTranscript(lines.joinToString(separator = "\n", postfix = "\n"))

    private fun claudeCall(
        callId: String,
        toolName: String = "mcp__mcp-steroid__steroid_execute_code",
    ): String = buildJsonObject {
        put("type", "assistant")
        putJsonObject("message") {
            put("role", "assistant")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "tool_use")
                    put("id", callId)
                    put("name", toolName)
                    putJsonObject("input") { put("code", "println(1)") }
                }
            }
        }
    }.toString()

    private fun claudeResult(
        callId: String,
        isError: Boolean?,
        text: String,
        contentAsArray: Boolean = false,
    ): String = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", callId)
                    if (isError != null) put("is_error", isError)
                    if (contentAsArray) {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", text)
                            }
                        }
                    } else {
                        put("content", text)
                    }
                }
            }
        }
    }.toString()

    private fun codexResult(callId: String, status: String, text: String): String = buildJsonObject {
        put("type", "item.completed")
        putJsonObject("item") {
            put("id", callId)
            put("type", "mcp_tool_call")
            put("server", "mcp-steroid")
            put("tool", "steroid_execute_code")
            put("status", status)
            putJsonObject("arguments") { put("code", "println(1)") }
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            }
        }
    }.toString()

    private fun geminiCall(callId: String): String = buildJsonObject {
        put("type", "tool_use")
        put("tool_name", "mcp_mcp-steroid_steroid_execute_code")
        put("tool_id", callId)
        putJsonObject("parameters") { put("code", "println(1)") }
    }.toString()

    private fun geminiResult(callId: String, status: String, output: String): String = buildJsonObject {
        put("type", "tool_result")
        put("tool_id", callId)
        put("tool_name", "mcp_mcp-steroid_steroid_execute_code")
        put("status", status)
        put("output", output)
    }.toString()

    private fun projectNotFound(name: String): String =
        "ERROR: Project not found: \"$name\". Available project_name values: project-abc12345"
}
