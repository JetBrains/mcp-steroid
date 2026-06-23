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
                projectName = route.exposedProjectName,
                name = route.originalProjectName,
                path = route.projectPath,
                backendName = route.exposedBackendName,
            )
        }

        return ListProjectsResponse(
            projects = listedProjects,
        )
    }
}
