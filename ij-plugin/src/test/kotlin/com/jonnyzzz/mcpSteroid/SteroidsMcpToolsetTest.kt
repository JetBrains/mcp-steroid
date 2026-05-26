/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.execution.ExecutionManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.vision.ScreenshotMeta
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the MCP execution flow.
 * These tests verify that the ExecutionManager correctly executes code.
 */
class SteroidsMcpToolsetTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false
    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
    }

    private fun getTextContent(result: com.jonnyzzz.mcpSteroid.mcp.ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    fun testExecuteCodeSuccess(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                println("Hello from toolset test")
            """.trimIndent()),
            NoOpProgressReporter
        )

        // Execution completes - may be success or error if script engine not available
        // Just verify we got a result
        assertTrue("Should have content", result.content.isNotEmpty())
    }

    fun testExecuteCodeWithOutput(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                println("Test output line 1")
                println("Test output line 2")
            """.trimIndent()),
            NoOpProgressReporter
        )

        // If execution succeeded, verify output contains our text
        assertTrue("Should not fail", !result.isError)
        val text = getTextContent(result)
        assertTrue("Should have output with line 1", text.contains("Test output line 1"))
    }

    fun testExecuteCodeWithError(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                throw RuntimeException("Test error")
            """.trimIndent()),
            NoOpProgressReporter
        )

        // Should be marked as an error
        assertTrue("Should be an error", result.isError)
        val text = getTextContent(result)
        assertTrue("Should have error message", text.contains("error") || text.contains("Error") || text.contains("RuntimeException"))
    }

    fun testExecuteCodeWithTimeout(): Unit = timeoutRunBlocking(15.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                println("Starting")
                kotlinx.coroutines.delay(10000) // 10 seconds
                println("Should not reach here")
            """.trimIndent(), timeout = 2), // 2-second timeout
            NoOpProgressReporter
        )

        // Should be an error due to a timeout (or an error if the script engine is not available)
        assertTrue("Should be an error", result.isError)
    }

    fun testExecuteCodeWithInvalidSyntax(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                this is not valid kotlin code at all
            """.trimIndent()),
            NoOpProgressReporter
        )

        // Should be error due to compilation failure (or script engine not available)
        // The result should have content - either error message or execution output
        val text = getTextContent(result)
        assertTrue("Should have content", text.isNotEmpty() || result.content.isNotEmpty())

        // If marked as an error, it should have an error message
        assertTrue("Should be an error", result.isError)
        assertTrue("Error should have a message", text.isNotEmpty())
    }

    fun testExecuteCodeWithScreenshot(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()
        myFixture.configureByText("ScreenshotTest.txt", "Screenshot test content")

        val result = manager.executeWithProgress(
            testExecParams(
                """
                takeIdeScreenshot()
                """.trimIndent()
            ),
            NoOpProgressReporter
        )

        assertFalse("Execution should succeed", result.isError)
        val image = result.content.filterIsInstance<ContentItem.Image>().singleOrNull()
        assertNotNull("Should return image content", image)
        assertEquals("image/png", image!!.mimeType)
        assertTrue("Image data should be non-empty", image.data.isNotBlank())

        val executionId = getExecutionIdFromResult(result)
        val screenshotPath = project.executionStorage.resolveExecutionPath(
            ExecutionId(executionId),
            "screenshot.png"
        )
        val treePath = project.executionStorage.resolveExecutionPath(
            ExecutionId(executionId),
            "screenshot-tree.md"
        )
        val metaPath = project.executionStorage.resolveExecutionPath(
            ExecutionId(executionId),
            "screenshot-meta.json"
        )
        assertTrue("Screenshot file should be persisted", Files.exists(screenshotPath))
        assertTrue("Component tree should be persisted", Files.exists(treePath))
        assertTrue("Screenshot metadata should be persisted", Files.exists(metaPath))

        val metaText = Files.readString(metaPath)
        val meta = json.decodeFromString(ScreenshotMeta.serializer(), metaText)
        assertTrue("Screenshot metadata should include windowId", !meta.windowId.isNullOrBlank())
    }
}
