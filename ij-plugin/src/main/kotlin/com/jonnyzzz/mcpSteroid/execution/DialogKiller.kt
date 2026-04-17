/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ExitActionType
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Utility for closing pending modal dialogs before code execution.
 *
 * This helps avoid execution failures when modal dialogs are blocking the IDE.
 * The utility:
 * 1. Uses [DialogWindowsLookup] to detect modal dialogs (fast negative path + EDT enumeration)
 * 2. Sorts dialogs by hierarchy depth (closes child dialogs first)
 * 3. Closes dialogs one at a time with message pump yielding
 * 4. Re-checks for new dialogs after each close (handles cascading dialogs)
 * 5. Captures a screenshot of the dialogs before closing (saved to execution folder)
 * 6. Logs all actions to the IDE log
 */

fun dialogKiller(): DialogKiller = service()

@Service(Service.Level.APP)
class DialogKiller {
    private val log = Logger.getInstance(DialogKiller::class.java)

    // Allow only 1 process at a time
    private val mutex = Semaphore(1)

    /**
     * Kill all modal dialogs owned by the project frame.
     *
     * Captures a screenshot before closing dialogs.
     *
     * @param project The project whose frame dialogs should be closed
     * @param executionId Execution ID for logging and screenshot naming
     */
    suspend fun killProjectDialogs(
        project: Project,
        executionId: ExecutionId,
        logMessage: (String) -> Unit,
        forceEnabled: Boolean? = null,
    ) {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            return
        }

        // forceEnabled == false → skip entirely
        // forceEnabled == true → skip registry check, force enable
        // forceEnabled == null → use registry setting (default behavior)
        if (forceEnabled == false) {
            return
        }

        if (forceEnabled == null && !Registry.`is`("mcp.steroid.dialog.killer.enabled")) {
            return
        }

        return mutex.withPermit {
            withContext(Dispatchers.IO + CoroutineName("DialogKiller")) {
                coroutineScope {
                    try {
                        doLookupDialogs(executionId, project, logMessage)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Failed to kill dialogs. ${e.message}", e)
                    }
                }
            }
        }
    }

    private suspend fun doLookupDialogs(
        executionId: ExecutionId,
        project: Project,
        logMessage: (String) -> Unit,
        iteration: Int = 0,
    ) {
        if (iteration > 5) return

        val lookup = dialogWindowsLookup()
        val dialogToClose = lookup.withDialogWindows(project) { dialogs ->
            if (dialogs.isEmpty()) {
                null
            } else {
                log.info("Modal state detected, starting dialog killer (execution: $executionId, iteration: $iteration, dialogs: ${dialogs.size})")
                // Pick the deepest dialog (already sorted deepest-first by withDialogWindows)
                dialogs.firstOrNull()
            }
        } ?: return

        // Yield to allow other coroutines to run
        yield()

        try {
            withTimeout(5_000) {
                VisionService.capture(project, executionId).logMessages().forEach { logMessage(it) }
            }
        } catch (e: TimeoutCancellationException) {
            log.warn("Screenshot capture timed out, proceeding to close dialog: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to capture screenshot before closing dialog: ${e.message}", e)
        }

        // Close the dialog on EDT with ModalityState.any()
        closeDialog(dialogToClose, 1, 1, executionId)

        yield()
        doLookupDialogs(executionId, project, logMessage, iteration + 1)
    }

    /**
     * Close a single dialog and verify closure.
     * Runs on EDT with ModalityState.any() to work even when dialogs are present.
     */
    private suspend fun closeDialog(
        dialog: DialogWrapper,
        index: Int,
        total: Int,
        executionId: ExecutionId,
    ) {
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            try {
                val window = dialog.window
                val title = (window as? java.awt.Frame)?.title
                    ?: (window as? java.awt.Dialog)?.title
                    ?: "Unknown"

                log.warn("Closing dialog $index/$total: '$title' (execution: $executionId)")

                // Check if dialog is still showing
                val wasShowing = window?.isShowing == true
                if (!wasShowing) {
                    log.info("Dialog already hidden: '$title'")
                    return@withContext
                }

                // Close the dialog
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE, ExitActionType.CANCEL)

                // Let it pump events!
                delay(10)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to close dialog: ${e.message}", e)
            }
        }
    }
}
