/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Pure-JVM tests for the file-storage core. Constructs an [ExecutionStorage]
 * against a JUnit-managed temp dir, exercises every public write path, and
 * verifies the on-disk layout — no IntelliJ test framework involved.
 *
 * The IDE wiring (Project → StoragePaths → IjExecutionStorage) is covered by
 * end-to-end tests in :ij-plugin; this module owns the storage contract.
 */
class ExecutionStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var storage: ExecutionStorage

    private val projectName = "test-project"
    private val projectPath = "/tmp/test-project"

    @BeforeEach
    fun setUp() {
        storage = ExecutionStorage(
            baseDirProvider = { tempDir },
            projectInfoProvider = { ExecutionProjectInfo(projectName, projectPath) },
        )
    }

    private fun testExecParams(
        code: String,
        taskId: String = "test-task",
        reason: String = "test",
        timeout: Int = 60,
    ) = ExecCodeParams(
        taskId = taskId,
        code = code,
        reason = reason,
        timeout = timeout,
    )

    private fun runStorageTest(block: suspend () -> Unit) = runBlocking {
        withTimeout(10.seconds) { block() }
    }

    @Test
    fun `writeNewExecution returns id with eid_ prefix and task id`() = runStorageTest {
        val executionId = storage.writeNewExecution(testExecParams("println(\"Hello\")"))

        assertTrue(executionId.executionId.startsWith("eid_"), "ID should start with eid_")
        assertTrue(executionId.executionId.contains("test"), "ID should contain task ID")
    }

    @Test
    fun `writeNewExecution writes tool metadata with project name and task id`() = runStorageTest {
        val params = testExecParams("println(\"Hello\")")
        val executionId = storage.writeNewExecution(params)

        val toolPath = tempDir.resolve(executionId.executionId).resolve("tool.json")
        assertTrue(Files.exists(toolPath), "tool.json should exist")

        val metadata = storage.json.decodeFromString(ToolCallMetadata.serializer(), Files.readString(toolPath))
        assertEquals("steroid_execute_code", metadata.toolName)
        assertEquals(projectName, metadata.projectName)
        assertEquals(params.taskId, metadata.taskId)
    }

    @Test
    fun `writeToolCall writes tool metadata and params payload`() = runStorageTest {
        val args = buildJsonObject { put("example", "value") }

        val executionId = storage.writeToolCall(
            toolName = "steroid_list_projects",
            arguments = args,
        )

        val baseDir = tempDir.resolve(executionId.executionId)
        assertTrue(Files.exists(baseDir.resolve("tool.json")), "tool.json should exist")
        assertTrue(Files.exists(baseDir.resolve("params.json")), "params.json should exist")

        val metadata = storage.json.decodeFromString(ToolCallMetadata.serializer(), Files.readString(baseDir.resolve("tool.json")))
        assertEquals("steroid_list_projects", metadata.toolName)
        assertEquals(projectName, metadata.projectName)
        assertEquals("value", metadata.arguments["example"]?.jsonPrimitive?.content)
    }

    @Test
    fun `different task ids produce different execution ids`() = runStorageTest {
        val id1 = storage.writeNewExecution(testExecParams("println(\"Hello\")", taskId = "task-1"))
        val id2 = storage.writeNewExecution(testExecParams("println(\"Hello\")", taskId = "task-2"))

        assertNotEquals(id1.executionId, id2.executionId,
            "Different task IDs should produce different execution IDs")
    }

    @Test
    fun `findExecutionId locates a freshly-written execution`() = runStorageTest {
        val executionId = storage.writeNewExecution(testExecParams("println(\"Hello\")"))

        val found = storage.findExecutionId(executionId.executionId)
        assertNotNull(found, "Should find execution")
        assertEquals(executionId.executionId, found?.executionId)

        assertNull(storage.findExecutionId("non-existent-id"),
            "Should not find non-existent execution")
    }

    @Test
    fun `findExecutionId rejects parent traversal and separators`() = runStorageTest {
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
    @Test
    fun `findExecutionId locates executions from every write path`() = runStorageTest {
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

        assertEquals(execId.executionId, storage.findExecutionId(execId.executionId)?.executionId,
            "writeNewExecution must be findable")
        assertEquals(toolCallId.executionId, storage.findExecutionId(toolCallId.executionId)?.executionId,
            "writeToolCall must be findable")
        assertEquals(feedbackId.executionId, storage.findExecutionId(feedbackId.executionId)?.executionId,
            "writeExecutionFeedback must be findable")
    }

    @Test
    fun `appendExecutionEvent writes successive lines without throwing`() = runStorageTest {
        val executionId = storage.writeNewExecution(testExecParams("test"))

        storage.appendExecutionEvent(executionId, "Hello from test")
        storage.appendExecutionEvent(executionId, "Second message")

        val outputJsonl = tempDir.resolve(executionId.executionId).resolve("output.jsonl")
        assertTrue(Files.exists(outputJsonl), "output.jsonl should exist")
        assertEquals(2, Files.readAllLines(outputJsonl).size, "two events should produce two lines")
    }

    @Test
    fun `writeCodeExecutionData writes a custom file at the right path`() = runStorageTest {
        val executionId = storage.writeNewExecution(testExecParams("test"))

        val path = storage.writeCodeExecutionData(executionId, "custom.txt", "Custom content")
        assertTrue(Files.exists(path), "File should exist")
        assertEquals("Custom content", Files.readString(path))
    }

    @Test
    fun `writeProjectInfo persists name and path in project txt`() = runStorageTest {
        val executionId = storage.writeNewExecution(testExecParams("test"))

        val projectTxt = tempDir.resolve(executionId.executionId).resolve("project.txt")
        assertTrue(Files.exists(projectTxt), "project.txt should exist")

        val lines = Files.readAllLines(projectTxt)
        assertEquals(projectName, lines[0])
        assertEquals(projectPath, lines[1])
    }

    @Test
    fun `resolveExecutionPath rejects path-traversal names`() = runStorageTest {
        val executionId = storage.writeNewExecution(testExecParams("test"))

        // These names contain illegal segments — `resolveExecutionPath` must refuse
        // to give a path back, regardless of whether the file exists.
        val illegal = listOf("../escape.txt", "sub/dir.txt", "windows\\path.txt")
        for (name in illegal) {
            val ex = runCatching { storage.resolveExecutionPath(executionId, name) }
            assertTrue(ex.exceptionOrNull() is IllegalArgumentException,
                "Expected IllegalArgumentException for name '$name', got ${ex.exceptionOrNull()}")
        }
    }
}
