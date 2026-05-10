/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.testExecParams
import org.junit.Assert.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Tests for ExecutionStorage.
 *
 * Note: The storage API has been simplified to append-only logging.
 * Tests cover the current API: writeNewExecution, appendExecutionEvent,
 * writeCodeExecutionData, and findExecutionId.
 */
class ExecutionStorageTest : BasePlatformTestCase() {

    private lateinit var storage: ExecutionStorage

    override fun setUp() {
        super.setUp()
        storage = project.service()
    }

    fun testWriteNewExecution(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "println(\"Hello\")"
        val params = testExecParams(code)

        val executionId = storage.writeNewExecution(params)

        // ID should have the format: eid_{timestamp}-{task-id}
        assertTrue("ID should start with eid_", executionId.executionId.startsWith("eid_"))
        assertTrue("ID should contain task ID", executionId.executionId.contains("test"))
    }

    fun testWriteNewExecutionWritesToolMetadata(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "println(\"Hello\")"
        val params = testExecParams(code)

        val executionId = storage.writeNewExecution(params)

        val toolPath = project.storagePaths.getGetMcpRunDir().resolve(executionId.executionId).resolve("tool.json")
        assertTrue("tool.json should exist", Files.exists(toolPath))

        val metadata = storage.json.decodeFromString(ToolCallMetadata.serializer(), Files.readString(toolPath))
        assertEquals("Tool name should match", "steroid_execute_code", metadata.toolName)
        assertEquals("Project name should match", project.name, metadata.projectName)
        assertEquals("Task ID should match", params.taskId, metadata.taskId)
    }

    fun testWriteToolCallWritesMetadata(): Unit = timeoutRunBlocking(10.seconds) {
        val args = buildJsonObject {
            put("example", "value")
        }

        val executionId = storage.writeToolCall(
            toolName = "steroid_list_projects",
            arguments = args
        )

        val baseDir = project.storagePaths.getGetMcpRunDir().resolve(executionId.executionId)
        val toolPath = baseDir.resolve("tool.json")
        val paramsPath = baseDir.resolve("params.json")

        assertTrue("tool.json should exist", Files.exists(toolPath))
        assertTrue("params.json should exist", Files.exists(paramsPath))

        val metadata = storage.json.decodeFromString(ToolCallMetadata.serializer(), Files.readString(toolPath))
        assertEquals("Tool name should match", "steroid_list_projects", metadata.toolName)
        assertEquals("Project name should match", project.name, metadata.projectName)
        assertEquals("Arguments should include example", "value", metadata.arguments["example"]?.jsonPrimitive?.content)
    }

    fun testDifferentTaskIdsProduceDifferentExecutionIds(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "println(\"Hello\")"

        val id1 = storage.writeNewExecution(testExecParams(code, taskId = "task-1"))
        val id2 = storage.writeNewExecution(testExecParams(code, taskId = "task-2"))

        assertNotEquals("Different task IDs should produce different execution IDs",
            id1.executionId, id2.executionId)
    }

    fun testFindExecutionId(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "println(\"Hello\")"
        val executionId = storage.writeNewExecution(testExecParams(code))

        // Should find the execution
        val found = storage.findExecutionId(executionId.executionId)
        assertNotNull("Should find execution", found)
        assertEquals("Execution ID should match", executionId.executionId, found?.executionId)

        // Should not find non-existent execution
        val notFound = storage.findExecutionId("non-existent-id")
        assertNull("Should not find non-existent execution", notFound)
    }

    fun testFindExecutionIdRejectsInvalidPaths(): Unit = timeoutRunBlocking(10.seconds) {
        // Should reject paths with parent traversal markers or separators
        assertNull(storage.findExecutionId("../etc/passwd"))
        assertNull(storage.findExecutionId("foo/bar"))
        assertNull(storage.findExecutionId(".."))
    }

    /**
     * Pins the universal-sentinel contract: findExecutionId must locate executions
     * written through every public write path. Regressed once when writeNewExecution
     * stopped writing params.json (the previous sentinel) — switching to tool.json
     * (always written by writeToolMetadata) restored coverage across all three.
     */
    fun testFindExecutionIdLocatesAllWritePaths(): Unit = timeoutRunBlocking(10.seconds) {
        val execId = storage.writeNewExecution(testExecParams("println(1)", taskId = "find-exec"))
        val toolCallId = storage.writeToolCall(
            toolName = "steroid_list_projects",
            arguments = buildJsonObject { put("k", "v") },
            taskId = "find-tool",
        )
        val feedbackId = storage.writeExecutionFeedback(
            taskId = "find-feedback",
            element = FeedbackParams(
                taskId = "find-feedback",
                successRating = 1.0,
                explanation = "ok",
                code = null,
            ),
        )

        assertEquals("writeNewExecution must be findable",
            execId.executionId, storage.findExecutionId(execId.executionId)?.executionId)
        assertEquals("writeToolCall must be findable",
            toolCallId.executionId, storage.findExecutionId(toolCallId.executionId)?.executionId)
        assertEquals("writeExecutionFeedback must be findable",
            feedbackId.executionId, storage.findExecutionId(feedbackId.executionId)?.executionId)
    }

    fun testAppendExecutionEvent(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "test"
        val executionId = storage.writeNewExecution(testExecParams(code))

        // Should not throw
        storage.appendExecutionEvent(executionId, "Hello from test")
        storage.appendExecutionEvent(executionId, "Second message")
    }

    fun testWriteCodeExecutionData(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "test"
        val executionId = storage.writeNewExecution(testExecParams(code))

        // Write a custom data file
        val path = storage.writeCodeExecutionData(executionId, "custom.txt", "Custom content")
        assertTrue("File should exist", Files.exists(path))

        val content = Files.readString(path)
        assertEquals("Content should match", "Custom content", content)
    }

    fun testWriteCodeReviewFile(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "println(\"Review me\")"
        val params = testExecParams(code)
        val executionId = storage.writeNewExecution(params)

        val reviewPath = storage.writeCodeReviewFile(executionId, code)
        assertTrue("Review file should exist", Files.exists(reviewPath))

        val savedCode = Files.readString(reviewPath)
        assertEquals("Code should match", code, savedCode)
    }

    fun testRemoveCodeReviewFile(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "println(\"Review me\")"
        val params = testExecParams(code)
        val executionId = storage.writeNewExecution(params)

        val reviewPath = storage.writeCodeReviewFile(executionId, code)
        assertTrue("Review file should exist", Files.exists(reviewPath))

        storage.removeCodeReviewFile(executionId)
        assertFalse("Review file should be removed", Files.exists(reviewPath))
    }
}
