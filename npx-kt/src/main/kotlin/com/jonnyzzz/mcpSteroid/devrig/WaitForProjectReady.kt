/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListedProject

/**
 * Returns the project route whose canonical [ListedProject.path] matches [projectPath]. When
 * [backendName] is known, the match must also belong to that backend so an already-open copy in a
 * different IDE cannot make `open_project --wait` return early with the wrong routing key.
 *
 * Project routing is the readiness contract deliberately: a Remote Development backend normally has
 * no frontend window, while every project-scoped MCP call requires the opaque [ListedProject.projectName]
 * published by `steroid_list_projects`. Window state is useful for interactive dialog handling, but it
 * cannot be required for a project to become addressable.
 */
fun findProjectRoute(
    response: ListProjectsResponse,
    projectPath: String,
    backendName: String? = null,
): ListedProject? = response.projects.singleOrNull { project ->
    project.path == projectPath && (backendName == null || project.backendName == backendName)
}

/**
 * Polls [pollListProjects] until [findProjectRoute] returns the requested route, or returns `null` once
 * [timeoutMs] has elapsed. The matched [ListedProject] is returned rather than a boolean because its
 * opaque routing key is the primary value a caller needs after opening a project.
 *
 * [now] and [sleep] are injected so tests drive the loop deterministically. Each iteration polls first
 * and only then checks the deadline, so even a zero-duration wait asks the IDE once before giving up.
 */
suspend fun awaitProjectReady(
    pollListProjects: suspend () -> ListProjectsResponse,
    projectPath: String,
    backendName: String? = null,
    timeoutMs: Long,
    intervalMs: Long,
    now: () -> Long,
    sleep: suspend (Long) -> Unit,
): ListedProject? {
    val deadline = now() + timeoutMs
    while (true) {
        findProjectRoute(pollListProjects(), projectPath, backendName)?.let { return it }
        if (now() >= deadline) return null
        sleep(intervalMs)
    }
}
