/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * True iff a parsed `steroid_list_windows` result ([listWindowsJson]) contains a window for
 * [projectPath] that has finished opening: initialized, not indexing, and with no modal dialog
 * blocking it. This is the pure decision `open_project --wait` polls on; [awaitProjectReady] is the
 * loop around it.
 *
 * Field names match `ListedWindow` in `mcp-steroid-server`'s `ListWindowsTool.kt` verbatim, confirmed
 * from that class rather than assumed: `McpJson` sets no naming strategy, so a property serializes
 * under its own name unless annotated. `projectPath` carries an explicit `@SerialName("project_path")`
 * (issue #381 — the same snake_case key `steroid_list_projects` uses), while `projectInitialized`,
 * `indexingInProgress` and `modalDialogShowing` carry no annotation and so stay camelCase on the wire.
 * `project_path` is matched against `open_project`'s OWN normalized value: that tool resolves its
 * `project_path` input through `toRealPath()` before opening, so the caller must pass the same
 * resolved path here for the two to line up.
 */
fun isProjectReady(listWindowsJson: JsonObject, projectPath: String): Boolean {
    val windows = listWindowsJson["windows"]?.jsonArray ?: return false
    return windows.any { window ->
        val entry = window.jsonObject
        entry["project_path"]?.jsonPrimitive?.content == projectPath &&
            entry["projectInitialized"]?.jsonPrimitive?.boolean == true &&
            entry["indexingInProgress"]?.jsonPrimitive?.boolean != true &&
            entry["modalDialogShowing"]?.jsonPrimitive?.boolean != true
    }
}

/**
 * Polls [pollListWindows] for [projectPath] to become [isProjectReady], returning `true` as soon as it
 * does and `false` once [timeoutMs] has elapsed without that happening. [now] and [sleep] are injected
 * rather than reading the wall clock or calling `kotlinx.coroutines.delay` directly, so a test drives the
 * whole loop deterministically, with no real waiting: production passes `System::currentTimeMillis` and
 * `kotlinx.coroutines.delay`.
 *
 * Each iteration polls FIRST and only then checks the deadline, so a caller always gets at least one
 * poll even when [timeoutMs] is small — matching "ask the IDE, then decide whether to give up", not
 * "give up before ever asking".
 */
suspend fun awaitProjectReady(
    pollListWindows: suspend () -> JsonObject,
    projectPath: String,
    timeoutMs: Long,
    intervalMs: Long,
    now: () -> Long,
    sleep: suspend (Long) -> Unit,
): Boolean {
    val deadline = now() + timeoutMs
    while (true) {
        if (isProjectReady(pollListWindows(), projectPath)) return true
        if (now() >= deadline) return false
        sleep(intervalMs)
    }
}
