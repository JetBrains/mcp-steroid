/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiDocumentManager
import com.jonnyzzz.mcpSteroid.koltinc.LineMapping
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

inline val Project.scriptExecutor: ScriptExecutor get() = service()

/**
 * Executes Kotlin scripts using IntelliJ's script engine.
 *
 * Execution flow:
 * 1. Script is compiled and evaluated to capture runnable script blocks
 * 2. Lambdas are executed in FIFO order inside a supervisorScope
 * 3. Any failure marks the whole execution as complete
 * 4. On timeout or cancellation, the Disposable is disposed and coroutine canceled
 *
 * Editing-guard pre/post-flight (former McpEditingGuard, inlined here as the
 * only caller):
 *
 *  - **Kill stuck modals.** Run [DialogKiller] to dismiss any modal dialogs
 *    left over from a previous step or background activity.
 *  - **Modality fail-fast.** Re-check via [DialogWindowsLookup]. If a modal
 *    is still up, abort with a clean tool error.
 *  - **Pre-flight commit + save + refresh.** Commit pending PSI edits, flush
 *    dirty documents, await VFS refresh. Guarantees the body sees disk-truth
 *    and any external write the body performs lands on a clean VFS.
 *  - **Run the body.** The actual script-blocks loop with the periodic dialog
 *    killer below.
 *  - **Post-flight refresh** in `finally` so the next agent step (compile,
 *    grep, follow-up edit) sees disk changes the body made (e.g. via Bash).
 *
 * Periodic dialog killing:
 * - A periodic [DialogKiller] coroutine polls during execution and dismisses
 *   any modal dialog that appears, surfacing a screenshot to the agent log.
 * - If the script intentionally shows a dialog (e.g. refactoring confirmation),
 *   call `doNotCancelOnModalityStateChange()` on the script context BEFORE
 *   the action — that cancels the killer's poll job for the rest of the run.
 *
 * Non-modal dialogs DO NOT block execution — they neither pin the EDT nor
 * count for the modality check; the script runs to completion against the
 * non-modal-dialog-visible IDE state.
 *
 * IMPORTANT: This executor runs the captured suspend block inside a supervisorScope.
 * The script code gets the coroutine context implicitly - no runBlocking needed.
 */
@Service(Service.Level.PROJECT)
class ScriptExecutor(
    private val project: Project
) : Disposable {
    private val log = Logger.getInstance(ScriptExecutor::class.java)
    override fun dispose() = Unit

    /**
     * Executes a script with progress reporting and returns its output.
     * It is a suspending function that runs inside the caller's coroutine context.
     *
     * Fast failure: If the script engine is not available or compilation fails,
     * it returns immediately with an error - no waiting.
     */
    suspend fun executeWithProgress(
        executionId: ExecutionId,
        exec: ExecCodeParams,
        resultBuilder: ExecutionResultBuilder,
    ) {
        val evalResult = project
            .codeEvalManager
            .evalCode(executionId, exec.code, resultBuilder) ?: return

        val lineMapping = evalResult.lineMapping

        log.info("Starting execution $executionId")

        // Single Disposable governs the entire execution lifecycle. Disposing
        // it cancels every coroutine launched against [coroutineScope] below
        // (the dialog killer poll, the IDE-exception collector). No manual
        // job.cancel() calls anywhere — disposal IS cancellation.
        val executionDisposable = Disposer.newDisposable(this, "mcp-execution-$executionId")

        val timeout = exec.timeout ?: Registry.intValue("mcp.steroid.execution.timeout", 600)

        try {
            val capturedBlocks = evalResult.result
            log.info("Running ${capturedBlocks.size} script block(s) for $executionId with timeout ${timeout}s")

            // Pre-flight editing guard, step 1+2: kill stuck modals, then
            // fail-fast if one is still showing. Skipped when the per-call
            // dialog_killer override is explicitly false — the caller opted
            // out of dialog killing and accepts modal dialogs may be present.
            val checkModality = exec.dialogKiller != false
            if (checkModality) {
                dialogKiller().killProjectDialogs(
                    project = project,
                    executionId = executionId,
                    logMessage = { resultBuilder.logMessage(it) },
                    forceEnabled = exec.dialogKiller,
                )
                val isModalShowing = dialogWindowsLookup().withModalityCheck { it }
                if (isModalShowing) {
                    resultBuilder.reportFailed(
                        "Modal dialog still showing after dialog killer ran — refusing to run the script. " +
                                "See IDE log + execution screenshot under execution id '${executionId.executionId}' for details."
                    )
                    return
                }
            }

            // Pre-flight editing guard, step 3: commit PSI, save dirty docs, await VFS refresh.
            commitAndSaveAllDocuments(project)
            project.vfsRefreshService.awaitRefresh()

            try {
                coroutineScope {
                    withContext(Dispatchers.IO) {
                        // Periodic dialog killer — dismisses any modal that
                        // appears during execution, logs a screenshot to the
                        // result builder, and lets the script continue against
                        // the post-dismiss IDE state. If the script
                        // intentionally shows a dialog (e.g. refactoring
                        // confirmation), it calls
                        // McpScriptContext.doNotCancelOnModalityStateChange() —
                        // which cancels [killerJob] for the rest of the run.
                        val killerJob: Job? = if (exec.cancelOnModal) {
                            launch(CoroutineName("execution-dialog-killer-$executionId")) {
                                while (isActive) {
                                    delay(KILLER_POLL_INTERVAL_MS.milliseconds)
                                    try {
                                        dialogKiller().killProjectDialogs(
                                            project = project,
                                            executionId = executionId,
                                            logMessage = { resultBuilder.logMessage(it) },
                                            forceEnabled = null, // honour registry toggle
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (t: Throwable) {
                                        log.warn("Periodic dialog killer failed for $executionId: ${t.message}", t)
                                    }
                                }
                            }
                        } else null

                        val context = McpScriptContextImpl(
                            project = project,
                            executionId = executionId,
                            disposable = executionDisposable,
                            resultBuilder = resultBuilder,
                            // Script can opt out of the periodic killer for this execution.
                            onDoNotCancelOnModalityStateChange = { killerJob?.cancel() },
                        )

                        val exceptionJob = launch {
                            service<ExceptionCaptureService>().exceptions.collect { ex ->
                                context.println(buildString {
                                    appendLine("=== IDE Exception Captured ===")
                                    appendLine("Time: ${ex.timestamp}")
                                    ex.pluginId?.let { appendLine("Plugin: $it") }
                                    appendLine("Message: ${ex.message}")
                                    appendLine("Stacktrace:")
                                    append(ex.stacktrace)
                                    appendLine("=== END ===")
                                })
                            }
                        }

                        try {
                            withTimeout(timeout.seconds) {
                                context.waitForSmartMode()
                                for ((index, block) in capturedBlocks.withIndex()) {
                                    yield()
                                    if (capturedBlocks.size > 1) {
                                        log.info("Executing block #${index + 1}/${capturedBlocks.size} for $executionId")
                                        context.progress("Executing block ${index + 1} of ${capturedBlocks.size}...")
                                    }
                                    block(context)
                                }
                                log.info("Execution $executionId completed normally")
                            }
                        } finally {
                            exceptionJob.cancel()
                            killerJob?.cancel()
                        }
                    }
                }
            } finally {
                // Post-flight editing guard, step 5: refresh so the next agent
                // step sees disk changes the body made (e.g. files written via
                // Bash from the script context).
                project.vfsRefreshService.awaitRefresh()
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout - report as error (must be caught before CancellationException since it's a subclass)
            log.warn("Execution $executionId timed out: ${e.message}")
            resultBuilder.logRemappedException("Execution timed out", e, lineMapping)
            resultBuilder.reportFailed("Execution timed out after $timeout seconds")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Unexpected error during execution $executionId: ${t.message}", t)
            val remappedMessage = lineMapping.remapStackTrace(t.message ?: "")
            resultBuilder.logRemappedException("Unexpected error during execution: $remappedMessage", t, lineMapping)
            resultBuilder.reportFailed("Unexpected error during execution: $remappedMessage")
        } finally {
            Disposer.dispose(executionDisposable)
        }
    }

    /**
     * Commit pending PSI edits and flush all dirty documents to disk before
     * the script body runs. EDT-only platform calls — dispatch when needed.
     *
     * When the caller is already on the EDT (BasePlatformTestCase with
     * `runInDispatchThread()=true`, or any tool-handler that landed on the
     * EDT), call inline — wrapping in `withContext(Dispatchers.EDT + …)`
     * would force a dispatch and deadlock against `runBlocking` waiting for
     * the current coroutine.
     */
    private suspend fun commitAndSaveAllDocuments(project: Project) {
        if (ApplicationManager.getApplication().isDispatchThread) {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveAllDocuments()
        } else {
            withContext(Dispatchers.EDT) {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()
            }
        }
    }

    private companion object {
        // Dialog killer poll cadence. 1 s is short enough to dismiss a dialog
        // before agent timeouts kick in, long enough that we don't burn CPU.
        const val KILLER_POLL_INTERVAL_MS = 1_000L
    }

    /**
     * Logs an exception with stack trace line numbers remapped from wrapped-file coordinates
     * to user-code coordinates, so agents see meaningful line references.
     */
    private fun ExecutionResultBuilder.logRemappedException(
        message: String,
        throwable: Throwable,
        lineMapping: LineMapping,
    ) {
        val cleanTrace = lineMapping.cleanStackTrace(throwable.stackTraceToString())
        val text = "ERROR: $message\n$cleanTrace"
        logMessage(text)
    }

}
