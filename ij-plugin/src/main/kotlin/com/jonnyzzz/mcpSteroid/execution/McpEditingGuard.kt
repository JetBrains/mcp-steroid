/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl
import com.intellij.openapi.fileEditor.impl.MemoryDiskConflictResolver
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
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
 *    refresh of the project content roots. This guarantees three invariants
 *    before the body runs:
 *      - Every open document's modification stamp matches its file on disk —
 *        so the [MemoryDiskConflictResolver] decision-point at line 50 of
 *        the platform's source short-circuits (no `isDocumentUnsaved`,
 *        therefore no conflict, therefore no dialog) when the body later
 *        writes those files.
 *      - VFS is in lock-step with the on-disk filesystem, so any read the
 *        body performs sees the same bytes that an external `Bash` call
 *        would read.
 *      - PSI is fresh — index queries from inside the body return current
 *        results.
 * 4. **Replace the memory-vs-disk conflict resolver for the body's lifetime
 *    with one that always prefers the disk version.** Belt-and-braces against
 *    the rare race where the user manually edits a file in the editor
 *    mid-flight while the agent writes the same file: without this, the
 *    confirmation dialog blocks the EDT and freezes the agent. Replacing the
 *    resolver via [FileDocumentManagerImpl.setAskReloadFromDisk] with a
 *    subclass whose [MemoryDiskConflictResolver.askReloadFromDisk] returns
 *    `true` makes the platform silently reload from disk instead — implementing
 *    the user-requested "prefer disk" semantics. Uses internal API
 *    (`FileDocumentManagerImpl` + `MemoryDiskConflictResolver` are in
 *    `com.intellij.openapi.fileEditor.impl`); there is no public equivalent
 *    in IU-261. The override is scoped to a [Disposable] so it disposes on
 *    every exit path including exception propagation.
 * 5. **Run the body.** The actual edit / script / patch.
 * 6. **Post-flight refresh.** Synchronously await a second VFS refresh so
 *    that the next agent step (compile, run, grep, follow-up edit) sees a
 *    consistent VFS view of every file the body touched — including files
 *    written by external processes the body invoked.
 * 7. **Restore the resolver.** Disposer fires; the conflict resolver
 *    re-enables for normal interactive use after the call returns.
 *
 * Each step is documented at its call site below for cross-reference with
 * the platform source. Threading rules:
 *
 * - Step 1, 2, 4–7 run on the calling coroutine's dispatcher.
 * - Step 3 (commit + saveAll) dispatches to the EDT with `nonModal()` so
 *   `WriteCommandAction`s in `commitAllDocuments` / `saveAllDocuments` land
 *   under a deterministic modality.
 *
 * "Prefer files from the disk" — natural consequence of saving every
 * document at step 3 and refreshing at step 6: open documents that the body
 * wrote externally are reloaded from disk by the platform's normal
 * VFile-event chain (the conflict path that would otherwise prompt is
 * disabled at step 4).
 */
@Service(Service.Level.APP)
class McpEditingGuard {
    private val log = Logger.getInstance(McpEditingGuard::class.java)

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

        // 4. Replace the memory-disk conflict resolver with a prefer-disk one
        //    for the body's lifetime. See class KDoc for the rationale + the
        //    internal-API trade-off.
        val resolverOverride = Disposer.newDisposable("mcp-editing-guard:${executionId.executionId}")
        installPreferDiskResolver(resolverOverride)

        try {
            // 5. Run the user-supplied edit body.
            val result = body()

            // 6. Post-flight VFS refresh — picks up disk changes the body
            //    made via external commands (Bash from script context, etc.).
            project.vfsRefreshService.awaitRefresh()

            return result
        } finally {
            // 7. Restore the conflict resolver. Always runs, even on exception
            //    propagation, so a failed body cannot leave the IDE with the
            //    resolver permanently disabled.
            Disposer.dispose(resolverOverride)
        }
    }

    private fun installPreferDiskResolver(parent: com.intellij.openapi.Disposable) {
        val fdm = FileDocumentManager.getInstance() as? FileDocumentManagerImpl
        if (fdm == null) {
            log.warn(
                "FileDocumentManager is not a FileDocumentManagerImpl (got ${FileDocumentManager.getInstance().javaClass.name}); " +
                        "cannot install prefer-disk resolver — the memory-vs-disk dialog may surface during this MCP call",
            )
            return
        }
        fdm.setAskReloadFromDisk(parent, PreferDiskResolver)
    }

    /**
     * [MemoryDiskConflictResolver] subclass that always returns `true` from
     * [askReloadFromDisk] — i.e. when the in-memory document and disk content
     * have diverged, take the disk version. No dialog is shown; the platform
     * silently reloads.
     *
     * Stateless and idempotent — a single instance is shared across calls.
     */
    private object PreferDiskResolver : MemoryDiskConflictResolver() {
        override fun askReloadFromDisk(file: VirtualFile, document: Document): Boolean = true
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
