/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.devrig.DevrigVersionMetadata
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler

class StubMcpSteroidTools(
    val services: DevrigServices,
) : McpSteroidTools() {
    private val bridge = DevrigToolBridgeClient(services.projectRouting, services.mcpHttpClient)
    private val listProjects = DevrigListProjectsToolHandler(services)
    private val listWindows = DevrigListWindowsToolHandler(services)
    val promptsContext = DevrigPromptsContextHandler(services.projectRouting)
    private val executeCode = DevrigExecuteCodeToolHandler(bridge)
    private val executeFeedback = DevrigExecuteFeedbackToolHandler(bridge)
    private val visionScreenshot = DevrigVisionScreenshotToolHandler(bridge)
    private val visionInput = DevrigVisionInputToolHandler(bridge)
    private val openProject = DevrigOpenProjectToolHandler(bridge)

    override fun <T> handler(type: Class<T>): T {
        val handler = when (type) {
            ListProjectsToolHandler::class.java -> listProjects
            ListWindowsToolHandler::class.java -> listWindows
            PromptsContextHandler::class.java -> promptsContext
            ExecuteCodeToolHandler::class.java -> executeCode
            ExecuteFeedbackToolHandler::class.java -> executeFeedback
            VisionScreenshotToolHandler::class.java -> visionScreenshot
            VisionInputToolHandler::class.java -> visionInput
            OpenProjectToolHandler::class.java -> openProject
            else -> unsupportedHandler(type)
        }
        return type.cast(handler)
    }

    private fun unsupportedHandler(type: Class<*>): Nothing =
        throw UnsupportedOperationException(
            "not yet ready: handler<${type.name}>() is not wired in devrig yet for ${services.clientInfo.client}"
        )
}

class DevrigListProjectsToolHandler(
    private val services: DevrigServices,
) : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val routes = services.projectRouting.routes().values.toList()
        val first = routes.firstOrNull()
        return ListProjectsResponse(
            ide = first?.ide ?: IdeInfo("devrig", DevrigVersionMetadata.getDevrigVersion(), "devrig"),
            plugin = first?.plugin ?: PluginInfo(
                "com.jonnyzzz.mcp-steroid.devrig",
                "devrig",
                DevrigVersionMetadata.getDevrigVersion(),
            ),
            pid = first?.idePid ?: ProcessHandle.current().pid(),
            projects = routes.map { route ->
                ProjectInfo(
                    name = route.exposedProjectName,
                    path = route.projectPath,
                )
            },
        )
    }
}

class DevrigPromptsContextHandler(
    private val routing: DevrigProjectRoutingService,
) : PromptsContextHandler {
    override suspend fun buildPromptsContext(projectName: String): PromptsContext {
        val route = routing.requireProject(projectName)
        return promptsContextFromBuild(route.ide.build)
    }

    companion object {
        fun promptsContextFromBuild(build: String): PromptsContext {
            val dash = build.indexOf('-')
            if (dash <= 0 || dash == build.lastIndex) return PromptsContext.Generic
            val productCode = build.substring(0, dash)
            val baseline = build.substring(dash + 1).substringBefore('.').toIntOrNull()
                ?: return PromptsContext.Generic
            return PromptsContext(productCode = productCode, baselineVersion = baseline)
        }
    }
}
