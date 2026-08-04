/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.ExecutionEventRecord
import com.jonnyzzz.mcpSteroid.storage.ExecutionEventWriteQueue
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.demo.executionEventBroadcaster
import kotlinx.coroutines.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException

interface ExecutionResultBuilder {
    val isFailed: Boolean
    /**
     * Number of user-script `println(...)` / `printJson(...)` invocations.
     * Framework messages (execution_id, dialog killer, hints) are NOT counted.
     * Used to detect "script ran but printed nothing" — the most common reason
     * agents see an empty MCP result and assume the call was broken.
     */
    val userOutputCount: Int
    fun logMessage(message: String)
    /**
     * Report in-flight progress. Progress is diagnostic, not payload (#154): implementations
     * must NOT add it to the tool result content — deliver via MCP progress notifications,
     * logs, and/or the execution event storage instead.
     */
    fun logProgress(message: String)
    fun logImage(mimeType: String, data: String, fileName: String)
    fun logException(message: String, throwable: Throwable)
    fun reportFailed(message: String)
    /** Called from McpScriptContextImpl.println/printJson to mark genuine user output. */
    fun noteUserOutput()
}

/**
 * Manages script executions for a project.
 * Executions run sequentially in a dedicated coroutine scope.
 */
@Service(Service.Level.PROJECT)
class ExecutionManager(
    private val project: Project,
) : Disposable {
    private val log = thisLogger()

    override fun dispose() = Unit

    suspend fun executeWithProgress(
        exec: ExecCodeParams,
        mcpProgressReporter: McpProgressReporter,
    ): ToolCallResult {
        // The execution runs as a VISIBLE, cancellable IDE background task
        // (com.intellij.platform.ide.progress.withBackgroundProgress — the coroutine-native
        // progress API). Cancellation is fully platform-handled: the Cancel button cancels
        // this coroutine (PlatformTaskSupport subscribes to the task entity and calls
        // context.cancel()), and when the coroutine ends for ANY reason — completion,
        // failure, script timeout, or the HTTP request coroutine being cancelled on client
        // disconnect (this suspend fun is a child of the Ktor request coroutine; structured
        // concurrency propagates) — the task disappears from the status bar automatically.
        // No manual wiring is needed or wanted.
        return withBackgroundProgress(project, "devrig -- MCP Steroid task", cancellable = true) { coroutineScope {
            val executionId = project.executionStorage.writeNewExecution(exec)
            withContext(CoroutineName("mcp-steroid-$executionId")) {
                log.info("Starting execution $executionId-${exec.taskId}-${exec.reason}...")

                // Broadcast execution started event for Demo Mode
                executionEventBroadcaster.onExecutionStarted(
                    executionId = executionId,
                    taskId = exec.taskId,
                    reason = exec.reason,
                    project = project
                )

                val builder = responseBuilder(this, executionId, mcpProgressReporter)
                try {
                    builder.logMessage("execution_id: ${executionId.executionId}")

                    // Run the script. ScriptExecutor wraps the user-script
                    // body in the editing-guard steps (dialog killer,
                    // modality fail-fast, BEFORE/AFTER awaitRefresh) so they
                    // surround only the run-blocks phase — kotlinc itself
                    // runs outside that wrapping because it doesn't touch the
                    // project tree and would otherwise pin a write-intent
                    // across compile wall-time.
                    project.scriptExecutor.executeWithProgress(
                        executionId,
                        exec,
                        builder
                    )
                    log.info("Execution $executionId completed")
                } catch (e: CancellationException) {
                    // Coroutine cancellation must propagate — never log, never wrap.
                    // The boundary catch-all in McpHttpTransport converts it to a
                    // structured tool result via `JsonRpcErrorCodes.INTERNAL_ERROR`.
                    throw e
                } catch (e: ToolCallErrorException) {
                    log.warn("ToolCallResultException during execution $executionId: ${e.message}", e)
                    builder.reportFailed(e.message)
                } catch (t: Throwable) {
                    log.warn("Unexpected error: ${t.message}", t)
                    builder.logException("Unexpected error", t)
                    builder.reportFailed("Unexpected error")
                }

                if (!builder.isFailed) {
                    project.executionStorage.writeCodeExecutionData(executionId, "success.txt", "Execution successful")
                }

                // Generate suggestions based on execution result
                val suggestions = project.executionSuggestionService
                    .generateSuggestions(
                        isFailed = builder.isFailed,
                        errorMessages = builder.errorMessages,
                        userOutputCount = builder.userOutputCount,
                    )
                for (suggestion in suggestions) {
                    builder.logMessage("HINT: $suggestion")
                }

                // Broadcast execution completed event for Demo Mode
                executionEventBroadcaster.onCompleted(
                    executionId = executionId,
                    success = !builder.isFailed,
                    errorMessage = if (builder.isFailed) "Execution failed" else null
                )

                builder.build()
            }
        } }
    }

    private fun responseBuilder(parentScope: CoroutineScope, executionId: ExecutionId, mcpProgress: McpProgressReporter) = object : ExecutionResultBuilder {
        private val responseBuilder = ToolCallResult.builder()
        // Storage writes go through a single-worker queue so output.jsonl lines land in the
        // exact order they were emitted (fan-out onto Dispatchers.IO used to scramble them,
        // #284) and a genuine write failure surfaces from build() instead of being logged and
        // ACKed as success (#433 follow-up). The queue buffers plain data records — never
        // lambdas, so no Project capture rides in the buffer — and its worker is a child of
        // this call's scope, torn down with the call.
        private val storageQueue = ExecutionEventWriteQueue(
            CoroutineScope(
                parentScope.coroutineContext +
                Dispatchers.IO +
                CoroutineName("storage-$executionId") +
                ModalityState.any().asContextElement()
            ),
            project.executionStorage,
        )
        private var failed = false
        private val _errorMessages = mutableListOf<String>()
        private var _userOutputCount = 0

        override val isFailed: Boolean
            get() = failed

        override val userOutputCount: Int
            get() = _userOutputCount

        val errorMessages: List<String>
            get() = _errorMessages

        override fun noteUserOutput() {
            _userOutputCount++
        }

        suspend fun build(): ToolCallResult {
            // Drain every queued storage write before returning the result — this both prevents
            // data loss if the parent scope is cancelled immediately AND re-raises any write
            // failure (a genuine IO error must fail the tool call, never be ACKed as success).
            storageQueue.awaitCompletion()
            return responseBuilder.build()
        }

        override fun logMessage(message: String) {
            responseBuilder.addTextContent(message)
            mcpProgress.report(message)
            // Broadcast output event for Demo Mode
            executionEventBroadcaster.onOutput(executionId, message)
            storageQueue.submit(ExecutionEventRecord.Append(executionId, message))
        }

        override fun logProgress(message: String) {
            // #154: progress is diagnostic, NOT payload — it must never enter the tool result
            // content (a script printing one JSON document must stay machine-parseable after
            // stripping the execution_id header). Delivery channels: MCP progress notifications
            // (sent when the client passed a progressToken), the Demo Mode broadcaster, and the
            // execution storage event log. Every producer also logs the same line to idea.log
            // at its call site.
            mcpProgress.report(message)
            // Broadcast progress event for Demo Mode
            executionEventBroadcaster.onProgress(executionId, message)
            storageQueue.submit(ExecutionEventRecord.Append(executionId, message))
        }

        override fun logImage(mimeType: String, data: String, fileName: String) {
            responseBuilder.addContent(ContentItem.Image(data = data, mimeType = mimeType))
            storageQueue.submit(ExecutionEventRecord.Append(executionId, "IMAGE: $fileName ($mimeType)"))
        }

        override fun logException(message: String, throwable: Throwable) {
            val text = "ERROR: $message: ${throwable.message}\n${throwable.stackTraceToString()}"
            responseBuilder.addTextContent(text)
            mcpProgress.report(text)
            _errorMessages.add(throwable.message ?: message)

            storageQueue.submit(ExecutionEventRecord.Append(executionId, text))
        }

        override fun reportFailed(message: String) {
            val text = "FAILED: $message"
            responseBuilder.addTextContent(text)
            mcpProgress.report(text)
            responseBuilder.markAsError()
            failed = true
            _errorMessages.add(message)
            storageQueue.submit(ExecutionEventRecord.Append(executionId, text))
            storageQueue.submit(ExecutionEventRecord.CodeError(executionId, text))
        }
    }
}
