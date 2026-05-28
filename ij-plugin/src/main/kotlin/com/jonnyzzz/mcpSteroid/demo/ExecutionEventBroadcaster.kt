/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Application-level service that broadcasts execution events via the message bus.
 * MCP server code calls this service to notify about execution lifecycle.
 * Demo mode overlay subscribes to the message bus topic to receive events.
 */
@Service(Service.Level.APP)
class ExecutionEventBroadcaster {

    private val activeExecutions = ConcurrentHashMap<ExecutionId, ExecutionState>()

    private val publisher: ExecutionEventListener
        get() = ApplicationManager.getApplication().messageBus.syncPublisher(EXECUTION_EVENTS_TOPIC)

    /**
     * Get all currently active executions.
     */
    @Suppress("unused") // Public API for future use
    fun getActiveExecutions(): Map<ExecutionId, ExecutionState> = activeExecutions.toMap()

    /**
     * Called when an execution starts.
     */
    fun onExecutionStarted(
        executionId: ExecutionId,
        taskId: String,
        reason: String,
        project: Project
    ) {
        val now = Instant.now()
        val state = ExecutionState(
            executionId = executionId,
            taskId = taskId,
            reason = reason,
            project = project,
            startTime = now,
            status = ExecutionStatus.RUNNING,
            recentLines = emptyList()
        )
        activeExecutions[executionId] = state

        // Drop the entry if the project disposes before the natural
        // completion path runs. Without this, a project closed mid-execution
        // leaves a ProjectImpl reference dangling on this app-scoped service
        // (and JUnit 5's @TestApplication leak hunter fails the test).
        Disposer.register(project) {
            activeExecutions.remove(executionId)
        }

        publisher.onExecutionStarted(
            ExecutionEvent.Started(executionId, now, taskId, reason, project)
        )
    }

    /**
     * Called when progress is reported during execution.
     */
    fun onProgress(executionId: ExecutionId, message: String) {
        val now = Instant.now()
        activeExecutions.computeIfPresent(executionId) { _, state ->
            val newLines = (state.recentLines + message).takeLast(DemoModeSettings.maxLines)
            state.copy(recentLines = newLines)
        }

        publisher.onExecutionProgress(
            ExecutionEvent.Progress(executionId, now, message)
        )
    }

    /**
     * Called when output is logged during execution.
     */
    fun onOutput(executionId: ExecutionId, message: String) {
        val now = Instant.now()
        activeExecutions.computeIfPresent(executionId) { _, state ->
            val newLines = (state.recentLines + message).takeLast(DemoModeSettings.maxLines)
            state.copy(recentLines = newLines)
        }

        publisher.onExecutionOutput(
            ExecutionEvent.Output(executionId, now, message)
        )
    }

    /**
     * Called when an execution completes.
     */
    fun onCompleted(executionId: ExecutionId, success: Boolean, errorMessage: String? = null) {
        val now = Instant.now()
        val newStatus = if (success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED

        activeExecutions.computeIfPresent(executionId) { _, state ->
            state.copy(status = newStatus)
        }

        publisher.onExecutionCompleted(
            ExecutionEvent.Completed(executionId, now, success, errorMessage)
        )

        // Schedule removal after minimum display time (non-blocking)
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { activeExecutions.remove(executionId) },
            DemoModeSettings.minDisplayTimeMs.toLong(),
            TimeUnit.MILLISECONDS
        )
    }

    companion object {
        @JvmStatic
        fun getInstance(): ExecutionEventBroadcaster = service()
    }
}

/**
 * Extension property for convenient access to the broadcaster.
 */
inline val executionEventBroadcaster: ExecutionEventBroadcaster
    get() = ExecutionEventBroadcaster.getInstance()
