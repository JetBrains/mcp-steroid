/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
     * Fast negative path: ask the platform whether the current modality
     * differs from `ModalityState.nonModal()`. If it does not, no modal is
     * elevating the current modality — we can short-circuit the enumeration.
     *
     * Replaces the previous "dispatch to EDT with a 100 ms timeout" probe,
     * which produced false positives under IDE load (a busy EDT looked the
     * same as a real modal) and added 100 ms to every list_windows call when
     * the EDT was actually parked. The modality-state read is synchronous and
     * cannot lie.
     *
     * Note: a non-modal `ModalityState.current()` only proves there is no
     * modality elevating the EDT — progress modals, write-action queues, etc.
     * also elevate modality and would return `true` here. The callers that
     * need the stricter "is there a real DialogWrapperDialog showing"
     * predicate fall through to enumeration, which filters by
     * [DialogWrapperDialog] + `isModal`.
     *
     * See https://youtrack.jetbrains.com/issue/IJPL-243343
     *
     * /// Kudos Yuriy Artamonov for the suggestion
     */
    private suspend fun isModalDialogShown(): Boolean {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) return false
        return withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            val current = ModalityState.current()
            val nonModal = ModalityState.nonModal()
            current != nonModal
        }
    }

    /**
     * Detect modal dialog windows for a project and process them.
     *
     * 1. Tries [isModalDialogShown] — if no modality is elevated, calls
     *    [action] with empty list.
     * 2. Otherwise, dispatches to EDT with [ModalityState.any] to enumerate all
     *    modal [DialogWrapper] instances owned by the project frame.
     * 3. Calls [action] with the found dialogs sorted by depth (deepest first).
     */
    suspend fun <T> withDialogWindows(
        project: Project,
        action: suspend (List<DialogWrapper>) -> T,
    ): T {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            return action(emptyList())
        }

        if (!isModalDialogShown()) {
            return action(emptyList())
        }

        val dialogs = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            val projectFrame = WindowManager.getInstance().getFrame(project) ?: return@withContext emptyList()
            findDialogsOwnedBy(projectFrame)
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
     * 1. Tries [isModalDialogShown] — if no modality is elevated, calls
     *    [action] with `false`.
     * 2. Otherwise, dispatches to EDT with [ModalityState.any] and enumerates
     *    actual [DialogWrapperDialog] windows. If any is showing and modal → true.
     * 3. Calls [action] with the result.
     */
    suspend fun <T> withModalityCheck(
        action: suspend (isModalShowing: Boolean) -> T,
    ): T {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) {
            return action(false)
        }

        if (!isModalDialogShown()) {
            return action(false)
        }

        val hasModalDialogWindow = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            Window.getWindows().any { w ->
                w.isShowing &&
                        w is DialogWrapperDialog &&
                        (w.dialogWrapper?.isModal == true)
            }
        }

        return action(hasModalDialogWindow)
    }

    /**
     * Find all [DialogWrapper] instances that are owned (directly or transitively)
     * by the given window. Only returns dialogs that are currently showing and modal.
     *
     * Must be called on EDT.
     */
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
