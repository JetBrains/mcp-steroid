/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.IdeInfo

/**
 * Direct in-IDE `steroid_list_projects`. No top-level `ide`/`plugin`/`pid` header (the responding
 * server's identity lives in the MCP server info). Each [ListedProject] carries `project_name == name`
 * and `backend_name` pointing at this IDE's self-id.
 */
class ListProjectsToolHandlerIJ : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val self = describeSelfBackend()
        return ListProjectsResponse(
            projects = self.projects,
        )
    }
}

class SelfBackendDescription(
    /** This IDE's own `backend_name` ([backendNameForMarker] over its pid + build). */
    val backendName: String,
    /** Open projects, each with `project_name == name` and `backend_name == `[backendName]. */
    val projects: List<ListedProject>,
)

suspend fun describeSelfBackend(): SelfBackendDescription {
    val ide = IdeInfo.ofApplication()
    val pid = ProcessHandle.current().pid()
    val selfBackendName = backendNameForMarker(pid = pid, build = ide.build)

    val openProjects = readAction {
        ProjectManager.getInstance().openProjects.toList()
    }

    val listedProjects = openProjects.map { project ->
        val name = project.name
        ListedProject(
            projectName = name,
            name = name,
            path = project.basePath ?: "",
            backendName = selfBackendName,
        )
    }

    return SelfBackendDescription(
        backendName = selfBackendName,
        projects = listedProjects,
    )
}
