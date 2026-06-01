/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapperDialog
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.*
import java.awt.Window

fun dialogWindowsLookup(): DialogWindowsLookup = service()

/**
 * Centralized lookup for modal dialog windows in the IDE.
 *
 * Two-phase detection:
 * 1. Fast negative path: try pumping EDT without modality context.
 *    If EDT responds → no modal blocking → no dialogs.
 * 2. Slow path: dispatch to EDT with [ModalityState.any] to enumerate
 *    actual [DialogWrapper] windows owned by the project frame.
 *
 * This service is the single source of truth for modal dialog detection,
 * used by [DialogKiller] (pre-execution cleanup) and [ModalityStateMonitor]
 * (runtime detection during execution).
 */
@Service(Service.Level.APP)
class DialogWindowsLookup {
    /**
     * Yuriy's modality check — the same logic exec_code uses (see
     * `ScriptExecutor.isModalEdt`): dispatch to the EDT under [ModalityState.any]
     * (which IS pumped even while a modal event loop runs, so it never hangs) and
     * ask whether the EDT is currently in an elevated modality state.
     *
     * This is the single, reliable modal detector shared by the gate
     * ([withModalityCheck]) and the killer ([withDialogWindows]) — replacing the
     * old `canPumpEdtNonModal` probe, whose 100ms `async(EDT){true}` round-trip
     * was a false-negative under a modal whose modality let the non-modal probe
     * slip through (it reported "EDT responsive, no modal" so the killer never
     * enumerated anything to close).
     *
     * TRUE for a modal [DialogWrapper] AND for modal progress/indexing; callers
     * that must tell those apart enumerate the actual dialog windows afterwards.
     */
    suspend fun isModalEdt(): Boolean {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) return false
        return withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            ModalityState.current() != ModalityState.nonModal()
        }
    }

    /**
     * Detect modal dialog windows for a project and process them.
     *
     * 1. [isModalEdt] — if the EDT is not in a modal state, calls [action] with empty list.
     * 2. Otherwise, dispatches to EDT with [ModalityState.any] to enumerate modal
     *    [DialogWrapper] instances (project-frame-owned, falling back to all showing modals).
     * 3. Calls [action] with the found dialogs sorted by depth, **deepest (top-most) first**,
     *    so the killer closes the front-most dialog before its parents.
     */
    suspend fun <T> withDialogWindows(
        project: Project,
        action: suspend (List<DialogWrapper>) -> T,
    ): T {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            return action(emptyList())
        }

        // Same modal detector as the gate (Yuriy's check): only enumerate when the EDT is
        // actually in a modal state. This replaces the old canPumpEdtNonModal probe whose
        // false-negative made the killer skip enumeration and never close the dialog.
        if (!isModalEdt()) {
            return action(emptyList())
        }

        val dialogs = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            // Prefer dialogs owned by the project frame, but fall back to ALL showing
            // modal dialogs when none are project-owned. This keeps the killer's view
            // consistent with the gate's [withModalityCheck] (which enumerates modal
            // DialogWrapper windows globally): a detached/ownerless modal — e.g. a real
            // user-opened dialog — would otherwise be detected by the gate but missed by
            // the killer, so the run hard-failed with "modal still showing" and nothing
            // ever closed it.
            val projectFrame = WindowManager.getInstance().getFrame(project)
            val owned = if (projectFrame != null) findDialogsOwnedBy(projectFrame) else emptyList()
            val candidates = owned.ifEmpty { findAllShowingModalDialogs() }
            candidates
                .map { it to dialogDepth(it) }
                .sortedByDescending { it.second }
                .map { it.first }
        }

        return action(dialogs)
    }

    /**
     * Check whether a modal **dialog window** is currently showing.
     * Used by [ListWindowsToolHandler][com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler]
     * where only the boolean flag is needed.
     *
     * Semantics: true iff there is at least one [DialogWrapperDialog] window
     * currently visible whose `DialogWrapper.isModal` is true. Background activities
     * that merely elevate `ModalityState.current()` (e.g. indexing / `Task.Modal`
     * progress / write-action tasks) do NOT count as a modal dialog — they are
     * progress indicators, not user-consent prompts. Conflating the two was a
     * real bug: the former version reported `isModalShowing=true` during
     * plain indexing, which made callers like
     * [`waitForIdeWindow`](../../integration/infra/intelliJ-container.kt)'s
     * fail-fast path abort every test as soon as indexing kicked in.
     *
     * 1. [isModalEdt] — if the EDT is not modal at all, calls [action] with `false`.
     * 2. Otherwise, dispatches to EDT with [ModalityState.any] and enumerates
     *    actual [DialogWrapperDialog] windows. If any is showing and modal → true.
     * 3. Calls [action] with the result.
     */
    //TODO: just reeturn, there is no need for action-style callback
    suspend fun <T> withModalityCheck(
        action: suspend (isModalShowing: Boolean) -> T,
    ): T {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            return action(false)
        }

        // Yuriy's check first (shared with the killer): if the EDT is not modal at all,
        // there is certainly no modal dialog. Otherwise enumerate to tell a real modal
        // DialogWrapper window apart from mere progress/indexing modality.
        if (!isModalEdt()) {
            return action(false)
        }

        val hasModalDialogWindow = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            findAllShowingModalDialogs().isNotEmpty()
        }

        return action(hasModalDialogWindow)
    }

    /**
     * Find all [DialogWrapper] instances that are owned (directly or transitively)
     * by the given window. Only returns dialogs that are currently showing and modal.
     *
     * Must be called on EDT.
     */
    /**
     * All currently showing modal [DialogWrapper] windows in the IDE, regardless of
     * owner. The single source of truth for "is a blocking modal up" — used by the
     * gate ([withModalityCheck]) and as the killer's fallback ([withDialogWindows]),
     * so the two never disagree about what counts as a modal.
     *
     * Must be called on EDT.
     */
    private fun findAllShowingModalDialogs(): List<DialogWrapper> =
        Window.getWindows().mapNotNull { w ->
            if (w.isShowing && w is DialogWrapperDialog) w.dialogWrapper?.takeIf { it.isModal } else null
        }

    private fun findDialogsOwnedBy(ownerWindow: Window): List<DialogWrapper> {
        val result = mutableListOf<DialogWrapper>()
        for (window in Window.getWindows()) {
            if (window !is DialogWrapperDialog) continue
            if (!window.isShowing) continue
            if (!isOwnedBy(window, ownerWindow)) continue
            val dialogWrapper = window.dialogWrapper ?: continue
            if (!dialogWrapper.isModal) continue
            result.add(dialogWrapper)
        }
        return result
    }

    /**
     * Calculate the depth of a dialog in the window ownership hierarchy.
     * Deeper dialogs (children) have higher depth values.
     */
    internal fun dialogDepth(dialog: DialogWrapper): Int {
        var depth = 0
        var current: Window? = dialog.window
        while (current?.owner != null) {
            depth++
            current = current.owner
        }
        return depth
    }

    /**
     * Check if a window is owned (directly or transitively) by another window.
     */
    private fun isOwnedBy(window: Window, potentialOwner: Window): Boolean {
        var current: Window? = window
        while (current != null) {
            if (current == potentialOwner) return true
            current = current.owner
        }
        return false
    }
}
