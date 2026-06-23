/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException

/**
 * Shared base for `*ToolHandlerIJ` implementations that resolve a
 * `projectName: String` argument to an open IDE [Project].
 *
 * Centralizes the read-action lookup and the not-found error message
 * (which lists all currently-open project names to help the caller
 * correct a typo) so each handler only writes the project-scoped body.
 */
@Service(Service.Level.APP)
class ProjectScopedToolHandler {
    /**
     * Resolve [projectName] against the currently open IDE projects. Throws
     * [ToolCallErrorException] if no project matches — `McpToolRegistry.callTool`
     * catches it and turns it into an MCP error result naming the missing project
     * plus the list of currently-open names.
     */
    @Throws(ToolCallErrorException::class)
    suspend fun resolveProject(projectName: String): Project {
        val (project, availableNames) = readAction {
            val openProjects = ProjectManager.getInstance().openProjects
            openProjects.find { projectNameFor(it) == projectName || it.name == projectName } to openProjects.map { it.name }
        }
        return project ?: throw ToolCallErrorException(
            "Project not found: \"$projectName\". Available projects: $availableNames"
        )
    }
}
