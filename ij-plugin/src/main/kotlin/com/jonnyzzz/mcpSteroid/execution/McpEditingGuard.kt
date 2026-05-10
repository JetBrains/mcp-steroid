/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrap an MCP editing call ([steroid_execute_code], [steroid_apply_patch]) in
 * the canonical before/after flow that keeps the IDE in a deterministic state
 * while an agent drives it.
 *
 * The flow:
 *
 * 1. **Kill stuck modals.** Run [DialogKiller] to dismiss any modal dialogs
 *    that may have been left over from a previous agent step or from
 *    background IDE activity.
 * 2. **Modality fail-fast.** After the killer ran, re-check via
 *    [DialogWindowsLookup.withModalityCheck]. If a modal is still showing —
 *    the killer couldn't clear it — abort with [McpEditingGuardException]
 *    instead of running the body. The MCP tool handler converts this to a
 *    clean tool error so the agent can adapt; running anyway would have the
 *    body silently park on the EDT until the dialog dismisses.
 * 3. **Pre-flight commit + save + refresh.** Commit any pending PSI edits,
 *    flush every dirty document to disk, and synchronously await a VFS
 *    refresh of the project content roots. This guarantees:
 *      - Every open document's modification stamp matches its file on disk
 *        when the body later writes those files.
 *      - VFS is in lock-step with the on-disk filesystem, so any read the
 *        body performs sees the same bytes that an external `Bash` call
 *        would read.
 *      - PSI is fresh — index queries from inside the body return current
 *        results.
 * 4. **Run the body.** The actual edit / script / patch.
 * 5. **Post-flight refresh.** Synchronously await a second VFS refresh so
 *    that the next agent step (compile, run, grep, follow-up edit) sees a
 *    consistent VFS view of every file the body touched — including files
 *    written by external processes the body invoked.
 *
 * Threading: every step runs on the calling coroutine's dispatcher except
 * the EDT-only `commitAllDocuments` / `saveAllDocuments`, which dispatch
 * to `Dispatchers.EDT` when not already on the EDT.
 *
 * "Prefer files from the disk" — natural consequence of saving every
 * document at step 3 and refreshing at step 5: open documents that the body
 * wrote externally are reloaded from disk by the platform's normal
 * VFile-event chain.
 */
@Service(Service.Level.APP)
class McpEditingGuard {
    private val log = thisLogger()

    /**
     * @param dialogKillerForceEnabled passed straight through to
     *   [DialogKiller.killProjectDialogs] (`null` = follow the registry; `true`
     *   = always run; `false` = skip the killer entirely). When the killer is
     *   skipped the modality fail-fast at step 2 is skipped as well — a caller
     *   that opted out of dialog killing has explicitly accepted that modal
     *   dialogs may be present.
     */
    suspend fun <T> withEditingGuard(
        project: Project,
        executionId: ExecutionId,
        logMessage: (String) -> Unit = { log.info(it) },
        dialogKillerForceEnabled: Boolean? = null,
        body: suspend () -> T,
    ): T {
        val checkModality = dialogKillerForceEnabled != false

        if (checkModality) {
            // 1. Kill any stuck modals from a previous step / background activity.
            dialogKiller().killProjectDialogs(
                project = project,
                executionId = executionId,
                logMessage = logMessage,
                forceEnabled = dialogKillerForceEnabled,
            )

            // 2. Fail fast if a modal is still up. The killer logs / screenshots
            //    on its own; we just refuse to run.
            val isModalShowing = dialogWindowsLookup().withModalityCheck { it }
            if (isModalShowing) {
                throw McpEditingGuardException(
                    "Modal dialog still showing after dialog killer ran — refusing to run the script. " +
                            "See IDE log + execution screenshot under execution id '${executionId.executionId}' for details.",
                )
            }
        }

        // 3. Pre-flight: commit PSI, save dirty docs, await VFS refresh.
        commitAndSaveAllDocuments(project)
        project.vfsRefreshService.awaitRefresh()

        // 4. Run the user-supplied edit body.
        val result = body()

        // 5. Post-flight VFS refresh — picks up disk changes the body
        //    made via external commands (Bash from script context, etc.).
        project.vfsRefreshService.awaitRefresh()

        return result
    }

    private suspend fun commitAndSaveAllDocuments(project: Project) {
        // commitAllDocuments + saveAllDocuments are EDT-only. When the caller
        // is already on the EDT (BasePlatformTestCase with the default
        // `runInDispatchThread()=true`, or any tool-handler that landed on the
        // EDT for any reason), call them inline — wrapping in
        // `withContext(Dispatchers.EDT + nonModal())` would force a dispatch
        // (the modality element makes `isDispatchNeeded` return true), and
        // since the EDT is already parked in `runBlocking` waiting for the
        // current coroutine, the queued task never runs and we deadlock.
        // From a non-EDT coroutine, the explicit dispatch is required.
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
}

class McpEditingGuardException(message: String) : RuntimeException(message)

fun mcpEditingGuard(): McpEditingGuard = service()
