/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.wm.WindowManager
import com.jonnyzzz.mcpSteroid.execution.Diff
import com.jonnyzzz.mcpSteroid.execution.ExecutionResultBuilder
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import kotlinx.coroutines.*
import java.awt.Frame
import kotlin.time.Duration.Companion.seconds

/**
 * Manages code review workflow.
 * Opens code in editor for human review before execution.
 * Users can edit the code to add comments or modifications.
 * On rejection, the edited code with diff is returned to the LLM.
 */
@Service(Service.Level.PROJECT)
class ReviewManager(private val project: Project) {
    private val log = thisLogger()

    companion object {
        private val REVIEW_EXECUTION_CONTEXT_KEY = Key<PendingReviewContext>("mcp-review-manager-key")
    }

    private data class PendingReviewContext(
        val executionId: ExecutionId,
        val deferred: CompletableDeferred<Notification>
    )

    fun isReviewPending(vFile: VirtualFile): Boolean {
        return vFile.getUserData(REVIEW_EXECUTION_CONTEXT_KEY) != null
    }

    /**
     * Request human review for code.
     * Opens code in editor and waits for approval/rejection.
     */
    suspend fun requestReview(
        executionId: ExecutionId,
        execCodeParams: ExecCodeParams,
        resultBuilder: ExecutionResultBuilder,
    ): Boolean = coroutineScope {
        if (!McpSteroidProjectSettings.getInstance(project).isReviewRequired()) {
            log.info("Auto-approving $executionId")
            return@coroutineScope true
        }

        log.info("Requesting review for $executionId")
        val codeForReview = execCodeParams.code
        val reviewFile = project.executionStorage.writeCodeReviewFile(executionId, codeForReview)
        val vFile = withContext(Dispatchers.EDT) {
            edtWriteAction {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(reviewFile)
            }
        }

        if (vFile == null) {
            log.warn("Could not open review file: $reviewFile in $executionId")
            resultBuilder.reportFailed("Could not open review file: $reviewFile in $executionId")
            return@coroutineScope false
        }

        val context = PendingReviewContext(
            executionId = executionId,
            deferred = CompletableDeferred()
        )

        withContext(Dispatchers.EDT) {
            vFile.refresh(true, false)
            vFile.putUserData(REVIEW_EXECUTION_CONTEXT_KEY, context)
            FileEditorManager.getInstance(project).openFile(vFile, true)
            // Bring IDE to the foreground (only in UI mode, not in headless/test mode)
            bringIdeToForeground()
        }

        try {
            // Get timeout
            val timeoutSeconds = try {
                Registry.intValue("mcp.steroid.review.timeout")
            } catch (_: Exception) {
                300
            }

            // Wait for approval/rejection with timeout
            try {
                withTimeout(timeoutSeconds.seconds) {
                    when (context.deferred.await()) {
                        is Notification.Approve -> {
                            project.executionStorage.removeCodeReviewFile(executionId)
                            true
                        }

                        is Notification.Reject -> {
                            resultBuilder.reportFailed("Code was rejected by user during review.")

                            withContext(Dispatchers.EDT) {
                                edtWriteAction {
                                    FileDocumentManager.getInstance().saveAllDocuments()
                                }
                            }

                            withContext(Dispatchers.IO) {
                                val originalCode = execCodeParams.code
                                val editedCode = readAction { vFile.readText() }
                                val codeWasModified = originalCode != editedCode
                                if (!codeWasModified) {
                                    project.executionStorage.removeCodeReviewFile(executionId)
                                }

                                // Generate diff if code was modified
                                if (codeWasModified) {
                                    val diff = Diff.generateUnifiedDiff(originalCode, editedCode)
                                    resultBuilder.logMessage("USER SUGGESTIONS DIFF: $diff")
                                }
                            }

                            false
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                log.info("Review timeout for $executionId")
                resultBuilder.reportFailed("Review failed by timeout")
                false
            }
        } finally {
            withContext(Dispatchers.EDT) {
                vFile.putUserData(REVIEW_EXECUTION_CONTEXT_KEY, null)
            }
        }
    }

    private sealed class Notification {
        object Approve : Notification()
        object Reject : Notification()
    }

    fun approve(file: VirtualFile) {
        val context = file.getUserData(REVIEW_EXECUTION_CONTEXT_KEY) ?: return
        log.info("Approving $context")
        closeReviewEditor(file)
        context.deferred.complete(Notification.Approve)
    }

    fun reject(file: VirtualFile) {
        val context = file.getUserData(REVIEW_EXECUTION_CONTEXT_KEY) ?: return
        log.info("Rejecting $context")
        closeReviewEditor(file)
        context.deferred.complete(Notification.Reject)
    }

    private fun closeReviewEditor(file: VirtualFile) {
        if (file.getUserData(REVIEW_EXECUTION_CONTEXT_KEY) == null) return
        FileEditorManager.getInstance(project).closeFile(file)
    }

    /**
     * Bring the IDE window to the foreground.
     * Only works in UI mode, safely ignored in headless/test mode.
     */
    private fun bringIdeToForeground() {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            return
        }
        try {
            val frame = WindowManager.getInstance().getFrame(project) ?: return
            // Request focus and bring to the front
            if (frame.state == Frame.ICONIFIED) {
                frame.state = Frame.NORMAL
            }
            frame.toFront()
            frame.requestFocus()
        } catch (e: Exception) {
            log.debug("Could not bring IDE to foreground: ${e.message}")
        }
    }
}
