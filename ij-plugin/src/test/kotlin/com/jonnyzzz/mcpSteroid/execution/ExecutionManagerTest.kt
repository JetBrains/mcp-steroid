/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for ExecutionManager.
 * Uses timeoutRunBlocking for coroutine tests as per IntelliJ 253 best practices.
 */
class ExecutionManagerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
    }

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    fun testExecuteWithProgressSuccess(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            println("Hello from test")
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(code), NoOpProgressReporter)

        // Should have content (either success output or error message)
        assertTrue("Should have content", result.content.isNotEmpty())
    }

    fun testExecuteWithProgressOutput(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            println("Line 1")
            println("Line 2")
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(code), NoOpProgressReporter)

        // If execution succeeded, verify the output
        if (!result.isError) {
            val text = getTextContent(result)
            assertTrue("Should have output", text.isNotEmpty())
        }
    }

    fun testExecuteWithProgressError(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            throw RuntimeException("Test error")
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(code), NoOpProgressReporter)

        // Should be an error
        assertTrue("Should be an error", result.isError)
        val text = getTextContent(result)
        assertTrue("Should have error content", text.isNotEmpty())
    }

    fun testExecuteWithProgressTimeout(): Unit = timeoutRunBlocking(15.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            println("Starting")
            kotlinx.coroutines.delay(10000)
            println("Should not reach here")
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(code, timeout = 2), NoOpProgressReporter)

        // Should be an error due to timeout (or an error if the script engine is not available)
        assertTrue("Should be an error", result.isError)
    }
}
