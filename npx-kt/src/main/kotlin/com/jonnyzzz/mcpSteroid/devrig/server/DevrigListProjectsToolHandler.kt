package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedProject

class DevrigListProjectsToolHandler(
    private val routing: DevrigProjectRoutingService,
) : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val routes = routing.routes()
        val listedProjects = routes.map { route ->
            ListedProject(
                // Opaque routing key (the disambiguated <name>-<hash>). `name` is the human-readable
                // folder name — NOT originalProjectName, which is the IDE's project_name hash and would
                // make `name` unreadable and break consumers that filter by the real folder name.
                projectName = route.exposedProjectName,
                name = route.projectInfo.name,
                path = route.projectPath,
                backendName = route.exposedBackendName,
            )
        }

        return ListProjectsResponse(
            projects = listedProjects,
        )
    }
}
