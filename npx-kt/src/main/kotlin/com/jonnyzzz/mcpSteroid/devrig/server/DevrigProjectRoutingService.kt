/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.server.ProgressTaskInfo
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.WindowInfo
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class DevrigProjectRoutingService(
    private val stateProvider: () -> Map<Long, IdeMonitorState>,
) {
    constructor(ideMonitor: IdeMonitorService) : this({ ideMonitor.states.value })

    private val windowRoutes = ConcurrentHashMap<String, WindowRoute>()
    private val screenshotExecutionRoutes = ConcurrentHashMap<String, Long>()

    fun routes(): Map<String, ProjectRoute> {
        val routes = linkedMapOf<String, ProjectRoute>()
        for ((pid, state) in stateProvider()) {
            for (project in state.lastSnapshot) {
                val route = projectRoute(pid, state.ide, project)
                routes[route.exposedProjectName] = route
            }
        }
        return routes
    }

    fun routeProject(exposedProjectName: String): ProjectRoute? =
        routes()[exposedProjectName]

    fun requireProject(exposedProjectName: String): ProjectRoute =
        routeProject(exposedProjectName)
            ?: throw ProjectRouteNotFoundException(exposedProjectName)

    fun rewriteWindow(idePid: Long, window: WindowInfo): WindowInfo {
        val route = routeForWindow(idePid, window) ?: return window
        val exposedWindowId = "${window.windowId}-${route.hash8}"
        windowRoutes[exposedWindowId] = WindowRoute(
            idePid = idePid,
            exposedWindowId = exposedWindowId,
            originalWindowId = window.windowId,
            projectRoute = route,
        )
        return window.copy(
            projectName = window.projectName?.let { route.exposedProjectName },
            windowId = exposedWindowId,
        )
    }

    fun rewriteBackgroundTask(idePid: Long, task: ProgressTaskInfo): ProgressTaskInfo {
        val projectName = task.projectName ?: return task
        val route = routes().values.firstOrNull {
            it.idePid == idePid && it.originalProjectName == projectName
        } ?: return task
        return task.copy(projectName = route.exposedProjectName)
    }

    fun routeWindow(exposedWindowId: String): WindowRoute? =
        windowRoutes[exposedWindowId]

    fun rememberScreenshotExecution(executionId: String, route: ProjectRoute) {
        screenshotExecutionRoutes[executionId] = route.idePid
    }

    fun routeScreenshotExecution(executionId: String): Long? =
        screenshotExecutionRoutes[executionId]

    fun singleRouteOrNull(): ProjectRoute? {
        val routes = routes().values.toList()
        return if (routes.size == 1) routes.single() else null
    }

    fun singleIdeOrNull(): DiscoveredIde? {
        val ides = stateProvider().values.map { it.ide }.distinctBy { it.pid }
        return if (ides.size == 1) ides.single() else null
    }

    private fun projectRoute(idePid: Long, ide: DiscoveredIde, project: ProjectInfo): ProjectRoute {
        val realHome = canonicalProjectHome(project.path)
        val hash8 = hash8(realHome, idePid)
        return ProjectRoute(
            idePid = idePid,
            bridgeBaseUrl = bridgeBaseUrl(ide.mcpUrl),
            headers = ide.marker.mcpSteroidServer.headers,
            originalProjectName = project.name,
            exposedProjectName = "${project.name}-$hash8",
            projectPath = project.path,
            realProjectHome = realHome,
            hash8 = hash8,
            ide = ide.marker.ide,
            plugin = ide.marker.plugin,
        )
    }

    private fun routeForWindow(idePid: Long, window: WindowInfo): ProjectRoute? {
        val allRoutes = routes().values.filter { it.idePid == idePid }
        val projectPath = window.projectPath
        if (projectPath != null) {
            val realPath = canonicalProjectHome(projectPath)
            allRoutes.firstOrNull { it.realProjectHome == realPath }?.let { return it }
        }
        val projectName = window.projectName ?: return null
        return allRoutes.firstOrNull { it.originalProjectName == projectName }
    }

    companion object {
        fun canonicalProjectHome(projectHome: String): Path =
            Path.of(projectHome).toRealPath()

        fun hash8(realProjectHome: Path, idePid: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(realProjectHome.toString().encodeToByteArray())
            digest.update(0.toByte())
            digest.update(idePid.toString().encodeToByteArray())
            val firstSix = digest.digest().copyOfRange(0, 6)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(firstSix)
        }

        fun bridgeBaseUrl(mcpUrl: String): String =
            mcpUrl.trimEnd('/').removeSuffix("/mcp")
    }
}

data class ProjectRoute(
    val idePid: Long,
    val bridgeBaseUrl: String,
    val headers: Map<String, String>,
    val originalProjectName: String,
    val exposedProjectName: String,
    val projectPath: String,
    val realProjectHome: Path,
    val hash8: String,
    val ide: IdeInfo,
    val plugin: PluginInfo,
)

data class WindowRoute(
    val idePid: Long,
    val exposedWindowId: String,
    val originalWindowId: String,
    val projectRoute: ProjectRoute,
)

class ProjectRouteNotFoundException(projectName: String) : IllegalArgumentException(
    "project_name '$projectName' is no longer present; call steroid_list_projects to refresh"
)
