/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test for DialogKiller functionality.
 *
 * This test runs the IDE in a Docker container with full GUI (via Xvfb) and verifies
 * that the dialog killer can detect and close modal dialogs.
 *
 * Two modes are tested:
 * 1. Explicit: call dialogKiller().killProjectDialogs() from script code
 * 2. Automatic: the pre-execution dialog killer (dialog_killer=true) closes dialogs before code runs
 *
 * Uses direct MCP HTTP calls (bypassing AI agents) for reliable testing.
 */
class DialogKillerIntegrationTest {
    private val testDialogTitle = "MCP Steroid Test Modal Dialog"

    companion object {
        val lifetime by lazy { CloseableStackHost(this::class.java.simpleName) }
        // `waitForProjectReady()` is required: `IntelliJContainer.create` only starts the
        // IDE process, it does NOT wait for `ProjectManager.openProjects` to populate.
        // Test bodies here issue `mcpExecuteCode` straight away, and without an explicit
        // wait they race the project open and fail with `No project found`. Previously
        // the Corretto-consent modal (fixed in `mcpResolveUnknownSdks`) happened to
        // block long enough to mask the race; no such accidental delay now.
        val session by lazy {
            IntelliJContainer.create(lifetime, "ide-agent", consoleTitle = "Dialog Killer")
                .waitForProjectReady()
        }
        val console get() = session.console

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    private fun doTest(modeName: String, closeAction: (IntelliJContainer) -> Unit) {
        console.writeInfo("Mode: $modeName")

        // Step 1: Open a custom modal DialogWrapper and leave it open (dialog killer disabled)
        console.writeStep(1, "Opening test modal dialog")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                // Disable modal cancellation so the dialog stays open after this execution
                doNotCancelOnModalityStateChange()

                // Open test modal dialog asynchronously so it doesn't block this execution
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
                                panel.add(javax.swing.JLabel("Dialog killer integration test"))
                                return panel
                            }
                        }
                        dialog.show()
                    }, com.intellij.openapi.application.ModalityState.nonModal())
                }

                kotlinx.coroutines.delay(1000)
                println("Test modal dialog opened")
            """.trimIndent(),
            taskId = "open-test-modal-dialog",
            reason = "Open test modal dialog",
        ).assertExitCode(0)

        // Step 2: Verify test modal dialog appeared via xcvb window list
        console.writeStep(2, "Verifying test modal dialog is visible")
        val dialogWindow = {
            val idePid = session.pid
            val listWindows = session.windows.listWindows()
            console.writeInfo("[TEST] Windows after opening test modal dialog:")
            listWindows.filter { it.pid == idePid }.forEach { println("  $it") }

            listWindows.find { it.title == testDialogTitle && it.pid == idePid }
        }


        Assertions.assertNotNull(dialogWindow(), "Test modal dialog should be visible")
        console.writeSuccess("Test modal dialog visible")

        // Step 3: Execute the close action
        console.writeStep(3, "Running dialog killer ($modeName)")
        closeAction(session)

        // Step 4: Verify test modal dialog is gone via xcvb window list
        console.writeStep(4, "Verifying test modal dialog is gone")
        console.writeInfo("[TEST] Windows after dialog killer:")
        Assertions.assertNull(dialogWindow(), "Test modal dialog should have been closed by dialog killer")
        console.writeSuccess("Test modal dialog closed")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `explicit dialog killer via script API`() = doTest("explicit") { session ->
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.jonnyzzz.mcpSteroid.execution.dialogKiller
                import com.jonnyzzz.mcpSteroid.storage.ExecutionId

                dialogKiller().killProjectDialogs(
                    project = project,
                    executionId = ExecutionId("dialog-killer-explicit-test"),
                    logMessage = { println(it) },
                    forceEnabled = true,
                )
                println("Explicit dialog killer completed")
            """.trimIndent(),
            taskId = "explicit-dialog-killer",
            reason = "Explicitly call dialog killer from script",
        ).assertExitCode(0)
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `automatic dialog killer closes test modal dialog`() = doTest("automatic") { session ->
        session.mcpSteroid.mcpExecuteCode(
            dialogKiller = true,
            code = """
                println("Dialog killer should have closed the test modal dialog before this runs")
            """.trimIndent(),
            taskId = "automatic-dialog-killer",
            reason = "Trigger automatic dialog killer via dialog_killer=true",
        ).assertExitCode(0)
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `dialog killer captures screenshot before closing`() = doTest("screenshot") { session ->
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.jonnyzzz.mcpSteroid.execution.dialogKiller
                import com.jonnyzzz.mcpSteroid.storage.ExecutionId

                dialogKiller().killProjectDialogs(
                    project = project,
                    executionId = ExecutionId("dialog-killer-screenshot-test"),
                    logMessage = { println(it) },
                    forceEnabled = true,
                )
                println("Dialog killer with screenshot completed")
            """.trimIndent(),
            taskId = "dialog-killer-screenshot",
            reason = "Dialog killer with screenshot verification",
        ).assertExitCode(0)
            .assertOutputContains("Screenshot saved to", message = "Dialog killer must capture screenshot before closing dialog")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `screenshot tool works in IDE`() {
        console.writeStep(1, "Taking screenshot of IDE via execute_code")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = $$"""
                import com.jonnyzzz.mcpSteroid.vision.VisionService
                import com.jonnyzzz.mcpSteroid.storage.ExecutionId

                val executionId = ExecutionId("screenshot-tool-test")
                val artifacts = VisionService.capture(project, executionId)
                println("Screenshot captured: ${artifacts.imagePath}")
                println("Image size: ${artifacts.meta.imageSize.width}x${artifacts.meta.imageSize.height}")
                println("Component tree: ${artifacts.treePath}")
                println("Screenshot metadata: ${artifacts.metaPath}")
                println("Screenshot bytes: ${artifacts.imageBytes.size}")
            """.trimIndent(),
            taskId = "screenshot-tool-test",
            reason = "Verify VisionService.capture works in IDE",
        )

        result.assertExitCode(0)
        result.assertOutputContains("Screenshot captured:", message = "VisionService.capture must produce image")
        result.assertOutputContains("Image size:", message = "Screenshot must have dimensions")
        result.assertOutputContains("Screenshot bytes:", message = "Screenshot must have non-empty bytes")

        console.writeSuccess("Screenshot tool works")
    }
}
