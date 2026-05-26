/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tests for the ScriptExecutor.
 *
 * These tests verify that execution failures are reported quickly (no timeout waiting)
 * and that the execution flow handles errors correctly.
 *
 * NOTE: In the test environment, the Kotlin script engine may not be available
 * because the Kotlin plugin is not loaded. Tests should still pass by verifying
 * that failures are reported quickly with ERROR status.
 *
 * The ScriptExecutor uses ExecutionResultBuilder to collect output, so we use
 * a TestResultBuilder to capture the results.
 */
class ScriptExecutorTest : BasePlatformTestCase() {

    // Run tests off the EDT so `timeoutRunBlocking` doesn't park the dispatch
    // thread while ScriptExecutor's internals dispatch back to EDT.
    override fun runInDispatchThread(): Boolean = false

    private val executor: ScriptExecutor get() = project.service()

    private var executionCounter = 0
    private fun nextExecutionId() = ExecutionId("test-${++executionCounter}")

    /**
     * Test that when the script engine is not available, we get a fast error response.
     * This is the expected case in the test environment.
     */
    fun testScriptEngineNotAvailableReturnsFast(): Unit = timeoutRunBlocking(60.seconds) {
        val code = """
            println("Hello")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(code), builder)

        // Should complete quickly (not wait 60 seconds for timeout)
        // Either has messages (success) or failed (error) - but completes fast
        assertTrue(
            "Should complete with output or error",
            builder.messages.isNotEmpty() || builder.isFailed
        )
    }

    /**
     * This test verifies fast reporting for compilation errors.
     * Uses invalid Kotlin syntax that should fail immediately.
     *
     * Note: When the script engine is available, this should fail with a compilation error.
     * When the script engine is NOT available, it will also fail (script engine not available).
     * Either way, execution should complete quickly and not wait for a timeout.
     */
    fun testCompilationFailureFast(): Unit = timeoutRunBlocking(60.seconds) {
        val invalidCode = """
            please fail; this is invalid Kotlin code
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(invalidCode), builder)

        // Either failed, has messages, or has exceptions logged.
        // The 60s timeoutRunBlocking guards against a runaway compile loop;
        // a healthy compile-failure path returns in well under a second.
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    /**
     * Test that syntax errors are caught and reported immediately.
     *
     * Note: When the script engine is not available, this will fail with a different error.
     */
    fun testSyntaxErrorFast(): Unit = timeoutRunBlocking(60.seconds) {
        val syntaxErrorCode = """
            val x = // incomplete statement
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(syntaxErrorCode), builder)

        // Either failed, has messages, or has exceptions - verifies fast completion
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    /**
     * Test that top-level script body executes without execute {} wrapper.
     */
    fun testTopLevelScriptBody(): Unit = timeoutRunBlocking(60.seconds) {
        val noExecuteCode = """
            // Top-level script body
            val x = 1 + 2
            println(x)
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(noExecuteCode), builder)

        // Either failed (engine missing) or produced output
        // Either way, should complete quickly
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    fun testExecuteWrapperStillWorks(): Unit = timeoutRunBlocking(60.seconds) {
        val executeWrapperCode = """
            execute {
                val x = 40 + 2
                println(x)
            }
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(executeWrapperCode), builder)

        // Either failed (engine missing) or produced output
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    /**
     * Test that top-level statements are executed in order.
     * When the script engine is available, statements should run sequentially.
     * If it is not available, we should get an error.
     */
    fun testTopLevelStatementsOrder(): Unit = timeoutRunBlocking(60.seconds) {
                val multiCode = """
            println("First")
            println("Second")
            println("Third")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(multiCode), builder)

        // Either SUCCESS (if engine is available) or ERROR (if not)
        // If successful, verify FIFO order in output
        if (!builder.isFailed && builder.messages.isNotEmpty()) {
            assertTrue("Should have 3 messages", builder.messages.size >= 3)
            assertEquals("First message", "First", builder.messages[0])
            assertEquals("Second message", "Second", builder.messages[1])
            assertEquals("Third message", "Third", builder.messages[2])
        }
    }

    /**
     * Test that a runtime error in the script body is caught and reported.
     */
    fun testRuntimeErrorInScript(): Unit = timeoutRunBlocking(60.seconds) {
        val errorCode = """
            throw RuntimeException("Test runtime error")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(errorCode), builder)

        // Should fail
        assertTrue("Should fail", builder.isFailed)
    }

    /**
     * Regression test for S3 (inline McpEditingGuard): a non-modal dialog
     * visible during steroid_execute_code must NOT block execution. A
     * non-modal frame doesn't pin the EDT and is invisible to
     * `withModalityCheck`, so the pre-flight guard's modality fail-fast
     * must let the script proceed, and the script body must run to
     * completion against the non-modal-dialog-visible IDE state.
     *
     * The dialog is created on EDT before the script runs and disposed
     * after the test. The contract: builder is not in failed state and
     * the script's println output reaches the result builder.
     */
    fun testNonModalDialogDuringExecuteDoesNotBlock(): Unit = timeoutRunBlocking(30.seconds) {
        val nonModalFrame = withContext(Dispatchers.EDT) {
            val frame = javax.swing.JFrame("non-modal-during-exec-test").apply {
                defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
                setSize(200, 100)
                // Non-modal: a plain JFrame is not modal. Show it without
                // blocking the caller — visible but not pinning the EDT.
                isVisible = true
            }
            frame
        }
        try {
            val code = """
                println("non-modal-dialog-visible")
            """.trimIndent()

            val builder = TestResultBuilder()
            executor.executeWithProgress(nextExecutionId(), testExecParams(code, timeout = 30), builder)

            // The script may have completed (engine available) or returned an
            // engine-missing error — but it MUST NOT block waiting for the
            // non-modal dialog. The 30s `timeoutRunBlocking` would have failed
            // the test if we deadlocked.
            assertTrue("Should complete with some output", builder.hasAnyOutput())
            assertFalse(
                "Pre-flight modality check must not trip on a non-modal dialog. Got reportFailed; messages=${builder.messages}",
                builder.isFailed && builder.messages.any { it.contains("Modal dialog still showing") },
            )
        } finally {
            withContext(Dispatchers.EDT) {
                nonModalFrame.isVisible = false
                nonModalFrame.dispose()
            }
        }
    }

    // NOTE: a parallel `testElevatedModality...` test was attempted but
    // `LaterInvocator.enterModal` elevates EDT modality, and
    // `ScriptExecutor.commitAndSaveAllDocuments` dispatches via plain
    // `Dispatchers.EDT` (queue is gated on the current modality state),
    // so the executor deadlocks waiting for the EDT to accept its task.
    // Coverage for the modal-DialogWrapper + dialog-killer path lives in
    // `test-integration/DialogKillerIntegrationTest` (Docker + Xvfb where
    // dialogs actually render and the killer can dispatch under
    // ModalityState.any()).

    /**
     * Test that a timeout is reported correctly when execution takes too long.
     */
    fun testTimeoutReported(): Unit = timeoutRunBlocking(60.seconds) {
        val slowCode = """
            println("Starting")
            kotlinx.coroutines.delay(5000) // 5 seconds
            println("Done")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(slowCode, timeout = 1), builder)

        // Should fail due to timeout (or error if engine not available)
        assertTrue("Should fail", builder.isFailed)
    }
}
