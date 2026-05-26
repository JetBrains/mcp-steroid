/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.server.ProgressTaskInfo
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.WindowInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class DevrigProjectRoutingServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `hash is stable and exactly eight base64url chars`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project")).toRealPath()

        val first = DevrigProjectRoutingService.hash8(projectHome, 1234)
        val second = DevrigProjectRoutingService.hash8(projectHome, 1234)

        assertEquals(first, second)
        assertEquals(8, first.length)
        assertEquals(first, first.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `hash changes for different ide pids on the same project home`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project")).toRealPath()

        assertNotEquals(
            DevrigProjectRoutingService.hash8(projectHome, 1234),
            DevrigProjectRoutingService.hash8(projectHome, 5678),
        )
    }

    @Test
    fun `hash changes for different canonical project homes`() {
        val projectA = Files.createDirectories(tempDir.resolve("project-a")).toRealPath()
        val projectB = Files.createDirectories(tempDir.resolve("project-b")).toRealPath()

        assertNotEquals(
            DevrigProjectRoutingService.hash8(projectA, 1234),
            DevrigProjectRoutingService.hash8(projectB, 1234),
        )
    }

    @Test
    fun `canonical path collapses symbolic link variants`() {
        val realProject = Files.createDirectories(tempDir.resolve("real").resolve("project"))
        val symlink = tempDir.resolve("link-project")
        Files.createSymbolicLink(symlink, realProject)

        assertEquals(
            realProject.toRealPath(),
            DevrigProjectRoutingService.canonicalProjectHome(symlink.toString()),
        )
    }

    @Test
    fun `project route exposes unique name and maps back to original project name`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
            )
        )

        val route = service.routes().values.single()

        assertEquals("mcp-steroid-${route.hash8}", route.exposedProjectName)
        assertEquals("mcp-steroid", route.originalProjectName)
        assertEquals("http://127.0.0.1:4343", route.bridgeBaseUrl)
        assertEquals(mapOf("Authorization" to "Bearer secret-42"), route.headers)
        assertEquals(route, service.requireProject(route.exposedProjectName))
    }

    @Test
    fun `duplicate original project names in different ides expose distinct names`() {
        val projectA = Files.createDirectories(tempDir.resolve("project-a"))
        val projectB = Files.createDirectories(tempDir.resolve("project-b"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectA.toString())),
            ),
            state(
                pid = 43,
                projects = listOf(ProjectInfo("mcp-steroid", projectB.toString())),
            ),
        )

        val routes = service.routes().values.toList()

        assertEquals(2, routes.size)
        assertEquals(2, routes.map { it.exposedProjectName }.distinct().size)
        assertEquals(setOf("mcp-steroid"), routes.map { it.originalProjectName }.toSet())
        assertEquals(setOf(42L, 43L), routes.map { it.idePid }.toSet())
        for (route in routes) {
            assertEquals(route, service.requireProject(route.exposedProjectName))
        }
    }

    @Test
    fun `stale exposed project name returns actionable error`() {
        val service = routingService()

        val error = assertFailsWith<ProjectRouteNotFoundException> {
            service.requireProject("missing-project-abcdefgh")
        }

        assertEquals(
            "project_name 'missing-project-abcdefgh' is no longer present; call steroid_list_projects to refresh",
            error.message,
        )
    }

    @Test
    fun `window project name and window id use the same project hash suffix`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
            )
        )

        val window = WindowInfo(
            projectName = "mcp-steroid",
            projectPath = projectHome.toString(),
            title = "MCP Steroid",
            isActive = true,
            isVisible = true,
            bounds = null,
            windowId = "frame-1",
        )

        val rewritten = service.rewriteWindow(42, window)
        val route = service.routes().values.single()

        assertEquals(route.exposedProjectName, rewritten.projectName)
        assertEquals("frame-1-${route.hash8}", rewritten.windowId)
        assertEquals("frame-1", service.routeWindow(rewritten.windowId)?.originalWindowId)
    }

    @Test
    fun `window routing uses project path to disambiguate duplicate original project names`() {
        val sharedProject = Files.createDirectories(tempDir.resolve("shared-project"))
        val otherProject = Files.createDirectories(tempDir.resolve("other-project"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", sharedProject.toString())),
            ),
            state(
                pid = 43,
                projects = listOf(
                    ProjectInfo("mcp-steroid", otherProject.toString()),
                    ProjectInfo("mcp-steroid", sharedProject.toString()),
                ),
            ),
        )

        val rewritten = service.rewriteWindow(
            43,
            WindowInfo(
                projectName = "mcp-steroid",
                projectPath = sharedProject.toString(),
                title = "MCP Steroid",
                isActive = true,
                isVisible = true,
                bounds = null,
                windowId = "frame-1",
            ),
        )
        val sharedRealProject = sharedProject.toRealPath()
        val otherRealProject = otherProject.toRealPath()
        val otherPidRoute = service.routes().values.single { it.idePid == 42L && it.realProjectHome == sharedRealProject }
        val samePidOtherRoute = service.routes().values.single { it.idePid == 43L && it.realProjectHome == otherRealProject }
        val samePidPathRoute = service.routes().values.single { it.idePid == 43L && it.realProjectHome == sharedRealProject }
        val windowRoute = service.routeWindow(rewritten.windowId) ?: error("missing window route")

        assertEquals(samePidPathRoute.exposedProjectName, rewritten.projectName)
        assertEquals("frame-1-${samePidPathRoute.hash8}", rewritten.windowId)
        assertNotEquals("frame-1-${samePidOtherRoute.hash8}", rewritten.windowId)
        assertNotEquals("frame-1-${otherPidRoute.hash8}", rewritten.windowId)
        assertEquals(43L, windowRoute.idePid)
        assertEquals("frame-1", windowRoute.originalWindowId)
        assertEquals(samePidPathRoute, windowRoute.projectRoute)
    }

    @Test
    fun `window without project name or path is left unchanged`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
            )
        )
        val window = WindowInfo(
            projectName = null,
            projectPath = null,
            title = "Welcome",
            isActive = true,
            isVisible = true,
            bounds = null,
            windowId = "welcome-frame",
        )

        val rewritten = service.rewriteWindow(42, window)

        assertEquals(window, rewritten)
        assertEquals(null, service.routeWindow("welcome-frame"))
    }

    @Test
    fun `background task project name is rewritten to exposed project name`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
            )
        )

        val rewritten = service.rewriteBackgroundTask(
            42,
            ProgressTaskInfo(
                title = "Indexing",
                text = "",
                text2 = "",
                fraction = null,
                isIndeterminate = true,
                isCancellable = false,
                projectName = "mcp-steroid",
            )
        )

        assertEquals(service.routes().values.single().exposedProjectName, rewritten.projectName)
    }

    @Test
    fun `screenshot execution id remembers owning ide pid`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
            )
        )
        val route = service.routes().values.single()

        service.rememberScreenshotExecution("eid_1", route)

        assertEquals(42, service.routeScreenshotExecution("eid_1"))
    }

    @Test
    fun `single ide policy returns null when no ides are routable`() {
        val service = routingService()

        assertEquals(null, service.singleIdeOrNull())
    }

    @Test
    fun `single ide policy returns null when multiple ides are routable`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val service = routingService(
            state(pid = 1, projects = listOf(ProjectInfo("a", projectA.toString()))),
            state(pid = 2, projects = listOf(ProjectInfo("b", projectB.toString()))),
        )

        assertEquals(null, service.singleIdeOrNull())
    }

    @Test
    fun `single ide policy returns the discovered ide when only one ide is routable`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(pid = 42, projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString()))),
        )

        assertNotNull(service.singleIdeOrNull())
        assertEquals(42, service.singleIdeOrNull()?.pid)
    }

    @Test
    fun `prompt context is parsed from routed IDE build number`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
                build = "IU-261.24374.151",
            ),
        )
        val route = routing.routes().values.single()

        val context = DevrigPromptsContextHandler(routing).buildPromptsContext(route.exposedProjectName)

        assertEquals("IU", context.productCode)
        assertEquals(261, context.baselineVersion)
    }

    @Test
    fun `prompt context falls back to generic when no projects are routed`() {
        val context = DevrigPromptsContextHandler(routingService()).buildPromptsContext(null)

        assertEquals("Generic", context.productCode)
        assertEquals(253, context.baselineVersion)
    }

    @Test
    fun `prompt context uses the only routed project when no project name is given`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
                build = "IU-261.24374.151",
            ),
        )

        val context = DevrigPromptsContextHandler(routing).buildPromptsContext(null)

        assertEquals("IU", context.productCode)
        assertEquals(261, context.baselineVersion)
    }

    @Test
    fun `prompt context falls back to generic when project name is omitted and multiple projects are routed`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val routing = routingService(
            state(pid = 42, projects = listOf(ProjectInfo("a", projectA.toString()))),
            state(pid = 43, projects = listOf(ProjectInfo("b", projectB.toString()))),
        )

        val context = DevrigPromptsContextHandler(routing).buildPromptsContext(null)

        assertEquals("Generic", context.productCode)
        assertEquals(253, context.baselineVersion)
    }

    @Test
    fun `prompt context parser supports common product build prefixes`() {
        val riderCppProductCode = charArrayOf('R', 'D', 'C', 'P', 'P', 'P').concatToString()
        val builds = mapOf(
            "IU-261.24374.151" to "IU",
            "IC-253.1" to "IC",
            "CL-253.2" to "CL",
            "RD-253.3" to "RD",
            "GO-253.4" to "GO",
            "PY-253.5" to "PY",
            "WS-253.6" to "WS",
            "DB-253.7" to "DB",
            "RM-253.8" to "RM",
            "QA-253.9" to "QA",
            "$riderCppProductCode-253.10" to riderCppProductCode,
        )

        for ((build, productCode) in builds) {
            val context = DevrigPromptsContextHandler.promptsContextFromBuild(build)
            assertEquals(productCode, context.productCode)
            assertEquals(build.substringAfter('-').substringBefore('.').toInt(), context.baselineVersion)
        }
    }

    @Test
    fun `prompt context parser falls back to generic for malformed or unknown builds`() {
        val builds = listOf(
            "IU",
            "-261.1",
            "IU-",
            "IU-next",
            "ZZ-261.1",
        )

        for (build in builds) {
            val context = DevrigPromptsContextHandler.promptsContextFromBuild(build)
            assertEquals("Generic", context.productCode, build)
            assertEquals(253, context.baselineVersion, build)
        }
    }

    private fun routingService(vararg states: IdeMonitorState): DevrigProjectRoutingService =
        DevrigProjectRoutingService { states.associateBy { it.ide.pid } }

    private fun state(
        pid: Long,
        projects: List<ProjectInfo>,
        build: String = "IU-261.1",
    ): IdeMonitorState {
        val ide = discoveredIde(pid, build)
        return IdeMonitorState(
            ide = ide,
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = projects,
        )
    }

    private fun discoveredIde(pid: Long, build: String): DiscoveredIde =
        DiscoveredIde(
            pid = pid,
            mcpUrl = "http://127.0.0.1:4343/mcp",
            markerPath = "/tmp/$pid.mcp-steroid",
            marker = PidMarker(
                schema = PidMarker.SCHEMA_VERSION,
                pid = pid,
                mcpSteroidServer = McpSteroidServerInfo(
                    mcpUrl = "http://127.0.0.1:4343/mcp",
                    port = 4343,
                    headers = mapOf("Authorization" to "Bearer secret-$pid"),
                ),
                ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
                plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
                createdAt = "2026-05-17T00:00:00Z",
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )
}
