/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
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
 * Modal dialog handling:
 * - A periodic [DialogKiller] coroutine polls during execution and dismisses
 *   any modal dialog that appears, surfacing a screenshot to the agent log.
 * - If the script intentionally shows a dialog (e.g. refactoring confirmation),
 *   call `doNotCancelOnModalityStateChange()` on the script context BEFORE
 *   the action — that cancels the killer's poll job for the rest of the run.
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

            coroutineScope {
                withContext(Dispatchers.IO) {
                    // Periodic dialog killer — replaces the old detect-and-cancel
                    // ModalityStateMonitor. The killer dismisses any modal that
                    // appears during execution, logs a screenshot to the result
                    // builder, and lets the script continue against the post-
                    // dismiss IDE state. If the script intentionally shows a
                    // dialog (e.g. refactoring confirmation), it calls
                    // McpScriptContext.doNotCancelOnModalityStateChange() — which
                    // cancels [killerJob] for the rest of the run.
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
