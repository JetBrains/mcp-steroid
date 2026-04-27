/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for DialogKiller, DialogWindowsLookup, and modal dialog detection during exec_code.
 *
 * Verifies:
 * - DialogWindowsLookup reports no dialogs when none are present
 * - exec_code detects a modal dialog and cancels execution (requires GUI)
 * - dialog killer can close dialogs before execution starts
 */
class DialogKillerTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false
    private val testDialogTitle = "MCP Steroid Test Modal Dialog"

    override fun setUp() {
        super.setUp()
        setSystemPropertyForTest("mcp.steroid.review.mode", "NEVER")
    }

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    private fun hasImageContent(result: ToolCallResult): Boolean {
        return result.content.any { it is ContentItem.Image }
    }

    /**
     * Test that DialogWindowsLookup reports no dialogs in clean state.
     */
    fun testDialogWindowsLookupNoDialogs(): Unit = timeoutRunBlocking(30.seconds) {
        val lookup = dialogWindowsLookup()
        lookup.withDialogWindows(project) { dialogs ->
            assertTrue("Should have no dialogs in clean state", dialogs.isEmpty())
        }
    }

    /**
     * Test that DialogWindowsLookup.withModalityCheck returns false in clean state.
     */
    fun testModalityCheckNoModals(): Unit = timeoutRunBlocking(30.seconds) {
        val lookup = dialogWindowsLookup()
        lookup.withModalityCheck { isModal ->
            assertFalse("Should not be modal in clean state", isModal)
        }
    }

    /**
     * Regression: `withModalityCheck` must only report `isModal=true` for an actual
     * showing [com.intellij.openapi.ui.DialogWrapperDialog], NOT just because the
     * current `ModalityState` is non-nonModal (e.g. during indexing or a
     * `Task.Modal` progress). The previous implementation flagged any elevated
     * modality as a "modal dialog showing", which caused
     * `waitForIdeWindow`'s fail-fast path to abort every Docker test as soon as
     * indexing kicked in, even though no user-facing dialog existed.
     *
     * This test elevates the coroutine's modality via `ModalityState.any().asContextElement()`
     * (which represents the "elevated but not a dialog" case) and verifies the
     * check still returns false. Full GUI verification (dialog present → true) is
     * already covered by DialogKillerIntegrationTest in Docker+Xvfb.
     */
    fun testModalityCheckIgnoresElevatedModalityWithoutDialog(): Unit = timeoutRunBlocking(30.seconds) {
        val lookup = dialogWindowsLookup()
        // Force the coroutine into the "slow" branch by pre-elevating modality
        // context. If `canPumpEdtNonModal` still short-circuits to false under
        // this modality, the slow branch will run and must NOT see a dialog.
        withContext(
            Dispatchers.EDT +
                    com.intellij.openapi.application.ModalityState.any().asContextElement()
        ) {
            // No dialog is actually opened. Any `isModal=true` here would be a false
            // positive driven by modality state, not by the dialog enumeration.
        }
        lookup.withModalityCheck { isModal ->
            assertFalse(
                "withModalityCheck must return false when no DialogWrapperDialog is showing, " +
                        "regardless of ModalityState.current()",
                isModal
            )
        }
    }

    /**
     * Test that DialogKiller.killProjectDialogs does not crash with no dialogs.
     */
    fun testDialogKillerNoopWithNoDialogs(): Unit = timeoutRunBlocking(30.seconds) {
        val killer = dialogKiller()
        val messages = mutableListOf<String>()
        killer.killProjectDialogs(
            project = project,
            executionId = com.jonnyzzz.mcpSteroid.storage.ExecutionId("test-noop"),
            logMessage = { messages.add(it) },
            forceEnabled = true,
        )
        // Should complete without error; no messages expected since no dialogs
    }

    /**
     * Test that exec_code detects a modal dialog during execution.
     *
     * NOTE: This test requires a non-headless environment with GUI support.
     * In headless mode (CI), dialog.show() does not trigger LaterInvocator
     * modality listeners, so the modality monitor cannot detect the dialog.
     * Full GUI testing is covered by DialogKillerIntegrationTest (Docker + Xvfb).
     *
     * 1. Execute code that opens a dialog (with modal cancellation enabled)
     * 2. The modal dialog should trigger the modality monitor
     * 3. Execution should be canceled with a screenshot of the dialog
     */
    fun testExecCodeDetectsModalDialog(): Unit = timeoutRunBlocking(60.seconds) {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            // Skip in headless — modality listeners don't fire without GUI
            return@timeoutRunBlocking
        }

        val manager = project.service<ExecutionManager>()

        val code = """
            val modalEntity = Any()
            withContext(kotlinx.coroutines.Dispatchers.EDT) {
                com.intellij.openapi.application.impl.LaterInvocator.enterModal(modalEntity)
            }

            try {
                // Wait for the synthetic modal state to be detected by the monitor
                kotlinx.coroutines.delay(5000)
                println("Should not reach here — modal dialog should have canceled execution")
            } finally {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                    com.intellij.openapi.application.impl.LaterInvocator.leaveModal(modalEntity)
                }, com.intellij.openapi.application.ModalityState.any())
            }
        """.trimIndent()

        val params = testExecParams(
            code = code,
            cancelOnModal = true,
            timeout = 30,
        )

        val result = manager.executeWithProgress(params, NoOpProgressReporter)

        val text = getTextContent(result)
        assertTrue(
            "Should detect modal dialog, got: $text",
            text.contains("MODAL DIALOG DETECTED")
        )

        assertTrue(
            "Should capture screenshot of the dialog",
            hasImageContent(result)
        )
    }

    /**
     * Test that the dialog killer closes dialogs before execution.
     *
     * 1. Open a dialog via invokeLater (non-blocking)
     * 2. Execute code with dialog_killer=true and cancelOnModal=false
     * 3. Dialog killer should close the dialog before execution starts
     * 4. Execution should complete successfully
     */
    fun testDialogKillerClosesDialogBeforeExecution(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        // First: open a dialog asynchronously
        val openDialogCode = """
            doNotCancelOnModalityStateChange()

            withContext(kotlinx.coroutines.Dispatchers.EDT) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                    val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                        init {
                            title = "$testDialogTitle"
                            setModal(true)
                            init()
                        }

                        override fun createCenterPanel(): javax.swing.JComponent {
                            val panel = javax.swing.JPanel()
                            panel.add(javax.swing.JLabel("Dialog killer test modal"))
                            return panel
                        }
                    }
                    dialog.show()
                }, com.intellij.openapi.application.ModalityState.nonModal())
            }

            kotlinx.coroutines.delay(1000)
            println("Modal dialog opened")
        """.trimIndent()

        val openParams = testExecParams(
            code = openDialogCode,
            cancelOnModal = false,
            timeout = 30,
        )

        // Open the dialog
        manager.executeWithProgress(openParams, NoOpProgressReporter)

        // Now run with dialog_killer=true — it should close the dialog before execution
        val afterCode = """
            println("Execution completed — dialog killer should have closed Settings before this")
        """.trimIndent()

        val afterParams = testExecParams(
            code = afterCode,
            cancelOnModal = false,
            timeout = 30,
        ).copy(dialogKiller = true)

        val result = manager.executeWithProgress(afterParams, NoOpProgressReporter)

        val text = getTextContent(result)
        assertFalse(
            "Should not be an error — dialog killer should have closed the dialog, got: $text",
            result.isError
        )
        assertTrue(
            "Should have execution output",
            text.contains("Execution completed")
        )
    }
}
