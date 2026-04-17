/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityStateListener
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.Base64

/**
 * Information about a detected modal dialog.
 */
data class ModalDialogInfo(
    val modalEntity: Any,
    val screenshotBase64: String? = null,
    val screenshotError: String? = null,
    val dialogTitles: List<String> = emptyList(),
)

/**
 * Monitors modality state changes and cancels code execution when a modal dialog appears.
 *
 * Usage with select expression:
 * ```kotlin
 * val monitor = ModalityStateMonitor(project, executionId, parentDisposable)
 * monitor.start()
 *
 * val result = select<ModalDialogInfo?> {
 *     monitor.onModalDialog { dialogInfo -> dialogInfo }  // Modal detected
 *     onAwait(async { executeCode() }) { null }           // Code completed
 * }
 *
 * if (result != null) {
 *     // Handle modal dialog
 * }
 *
 * monitor.stop()
 * ```
 *
 * To disable cancellation (let code continue even with modal dialogs):
 * ```kotlin
 * monitor.doNotCancelOnModalityStateChange()
 * ```
 */
class ModalityStateMonitor(
    private val project: Project,
    private val executionId: ExecutionId,
    parentDisposable: Disposable,
) {
    private val log = Logger.getInstance(ModalityStateMonitor::class.java)
    private val disposable = Disposer.newDisposable(parentDisposable, "modality-monitor-$executionId")

    // Channel that receives modal dialog info when a dialog appears
    private val modalDialogChannel = Channel<ModalDialogInfo>(Channel.CONFLATED)

    // Supervised scope for screenshot captures - cancelled when disposable is disposed
    private val screenshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
        Disposer.register(disposable) { scope.cancel() }
    }

    private var isMonitoring = false
    private var cancelOnModality = true

    private val listener = object : ModalityStateListener {
        override fun beforeModalityStateChanged(entering: Boolean, modalEntity: Any) {
            if (!isMonitoring || !cancelOnModality) return
            if (!entering) return

            // Coroutine-based modal progress (runWithModalProgressBlocking) enters modal context
            // via a JobProvider entity, not a UI dialog. Maven's MavenShCommandLineState.startProcess()
            // uses this wrapper; Gradle does not. Cancelling execution on these would kill the
            // run-configuration's own startup coroutine before SMTRunnerEventsListener fires.
            // DialogWrapper does NOT implement JobProvider, so genuine UI dialogs still cancel.
            if (modalEntity is com.intellij.openapi.application.impl.JobProvider) {
                log.info("Skipping JobProvider modal entity (coroutine progress, not UI dialog) during execution $executionId: $modalEntity")
                return
            }

            run {
                log.info("Modal dialog detected during execution $executionId: $modalEntity")

                // Send info immediately without screenshot to ensure fast notification
                // Screenshot will be captured separately if needed
                val basicInfo = ModalDialogInfo(modalEntity = modalEntity)
                modalDialogChannel.trySend(basicInfo)

                // Try to capture screenshot asynchronously in supervised scope
                // This scope is cancelled in stop() to prevent resource leaks
                screenshotScope.launch {
                    if (!isMonitoring || project.isDisposed) return@launch
                    try {
                        val info = captureModalDialog(modalEntity)
                        // Only send if still monitoring
                        if (isMonitoring) {
                            modalDialogChannel.trySend(info)
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to capture screenshot: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Start monitoring for modality state changes.
     */
    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        log.info("Starting modality monitoring for execution $executionId")
        LaterInvocator.addModalityStateListener(listener, disposable)
    }

    /**
     * Stop monitoring. Call this when execution completes.
     * Note: The disposable is NOT disposed here - it will be disposed
     * by the parent disposable when the execution completes.
     */
    fun stop() {
        isMonitoring = false
        // Cancel pending screenshot captures first to prevent resource leaks
        screenshotScope.cancel()
        modalDialogChannel.close()
        // Don't dispose here - parent will handle it
    }

    /**
     * Disable cancellation on modality state change.
     * When called, modal dialogs will not cancel the execution.
     */
    fun doNotCancelOnModalityStateChange() {
        cancelOnModality = false
    }

    /**
     * Use in select expression to receive modal dialog events.
     *
     * Example:
     * ```kotlin
     * select {
     *     monitor.onModalDialog { info -> handleDialog(info) }
     *     otherChannel.onReceive { ... }
     * }
     * ```
     */
    val onModalDialog get() = modalDialogChannel.onReceive

    private suspend fun captureModalDialog(modalEntity: Any): ModalDialogInfo {
        return try {
            log.info("Capturing screenshot of modal dialog for execution $executionId")

            // Use DialogWindowsLookup to enumerate actual dialog windows
            val lookup = dialogWindowsLookup()
            val dialogTitles = lookup.withDialogWindows(project) { dialogs ->
                dialogs.mapNotNull { dialog ->
                    val window = dialog.window
                    (window as? java.awt.Frame)?.title
                        ?: (window as? java.awt.Dialog)?.title
                }
            }
            if (dialogTitles.isNotEmpty()) {
                log.info("Found ${dialogTitles.size} dialog(s) for execution $executionId: $dialogTitles")
            }

            val artifacts = VisionService.capture(project, executionId)
            val base64 = Base64.getEncoder().encodeToString(artifacts.imageBytes)
            log.info("Screenshot captured successfully for execution $executionId")
            ModalDialogInfo(
                modalEntity = modalEntity,
                screenshotBase64 = base64,
                dialogTitles = dialogTitles,
            )
        } catch (e: Exception) {
            log.warn("Failed to capture screenshot for execution $executionId: ${e.message}", e)
            ModalDialogInfo(
                modalEntity = modalEntity,
                screenshotError = e.message ?: "Unknown error",
            )
        }
    }
}

/**
 * Execute code with modality state monitoring.
 *
 * If a modal dialog appears during execution, the code is cancelled and
 * the modal dialog info (with screenshot) is returned.
 *
 * @param project The project context
 * @param executionId Execution ID for logging
 * @param parentDisposable Parent disposable for cleanup
 * @param cancelOnModality If false, modality state changes won't cancel execution
 * @param block The suspend block to execute
 * @return ModalDialogInfo if a modal dialog appeared, null if execution completed normally
 */
suspend fun <T> withModalityMonitoring(
    project: Project,
    executionId: ExecutionId,
    parentDisposable: Disposable,
    cancelOnModality: Boolean = true,
    block: suspend () -> T
): Pair<T?, ModalDialogInfo?> {
    val monitor = ModalityStateMonitor(project, executionId, parentDisposable)
    if (!cancelOnModality) {
        monitor.doNotCancelOnModalityStateChange()
    }
    monitor.start()

    return try {
        coroutineScope {
            val executionDeferred = async { block() }

            select {
                monitor.onModalDialog { dialogInfo ->
                    // Modal dialog detected - cancel execution
                    executionDeferred.cancel("Modal dialog detected: ${dialogInfo.modalEntity}")
                    Pair(null, dialogInfo)
                }
                executionDeferred.onAwait { result ->
                    // Execution completed normally
                    Pair(result, null)
                }
            }
        }
    } finally {
        monitor.stop()
    }
}
