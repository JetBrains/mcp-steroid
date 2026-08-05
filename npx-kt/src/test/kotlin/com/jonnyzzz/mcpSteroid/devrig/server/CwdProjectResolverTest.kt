/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.io.TempDir

class CwdProjectResolverTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `cwd inside exactly one route resolves to One`() {
        val projectPath = Files.createDirectories(tempDir.resolve("proj"))
        val route = route(projectPath, "proj")

        val match = resolveProjectFromCwd(projectPath.resolve("src"), listOf(route))

        assertEquals(CwdProjectMatch.One(route), match)
    }

    @Test
    fun `cwd outside every route resolves to None`() {
        val projectPath = Files.createDirectories(tempDir.resolve("proj"))
        val elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"))

        val match = resolveProjectFromCwd(elsewhere, listOf(route(projectPath, "proj")))

        assertEquals(CwdProjectMatch.None, match)
    }

    @Test
    fun `no routes at all resolves to None`() {
        assertEquals(CwdProjectMatch.None, resolveProjectFromCwd(tempDir, emptyList()))
    }

    @Test
    fun `cwd inside two routes at the same depth resolves to Ambiguous with both candidates`() {
        // Two IDEs both routing the exact same on-disk project path (e.g. two backends opened it).
        val projectPath = Files.createDirectories(tempDir.resolve("proj"))
        val routeA = route(projectPath, "proj-a")
        val routeB = route(projectPath, "proj-b")

        val match = resolveProjectFromCwd(projectPath, listOf(routeA, routeB))

        assertIs<CwdProjectMatch.Ambiguous>(match)
        assertEquals(setOf(routeA, routeB), match.candidates.toSet())
    }

    @Test
    fun `cwd nested under two routes picks the deepest, most specific one`() {
        val outer = Files.createDirectories(tempDir.resolve("outer"))
        val inner = Files.createDirectories(outer.resolve("inner"))
        val outerRoute = route(outer, "outer")
        val innerRoute = route(inner, "inner")

        val match = resolveProjectFromCwd(inner.resolve("src"), listOf(outerRoute, innerRoute))

        assertEquals(CwdProjectMatch.One(innerRoute), match)
    }

    @Test
    fun `a sibling directory sharing a path prefix does not match a route at the shorter path`() {
        val projectPath = Files.createDirectories(tempDir.resolve("proj"))
        val sibling = Files.createDirectories(tempDir.resolve("projbeta"))

        val match = resolveProjectFromCwd(sibling, listOf(route(projectPath, "proj")))

        assertEquals(CwdProjectMatch.None, match)
    }

    private fun route(projectPath: Path, exposedName: String): ProjectRoute = ProjectRoute(
        route = DiscoveredIde(
            backendName = "backend-$exposedName",
            pid = 1,
            rpcBaseUrl = "http://127.0.0.1:4343/mcp",
            bridgeHeaders = emptyMap(),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        ),
        projectInfo = IdeProjectState(name = exposedName, projectPath = projectPath.toString()),
        exposedProjectName = exposedName,
        projectPath = projectPath.toString(),
    )
}
