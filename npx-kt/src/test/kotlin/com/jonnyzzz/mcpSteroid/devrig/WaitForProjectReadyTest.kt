/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListedProject
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class WaitForProjectReadyTest {
    private fun project(
        path: String,
        projectName: String = "opaque-project-key",
        backendName: String = "iu-backend",
    ) = ListedProject(projectName = projectName, name = "raw-name", path = path, backendName = backendName)

    private fun projects(vararg entries: ListedProject) = ListProjectsResponse(entries.toList())

    @Test
    fun `a routed frontendless project is ready without any window`() {
        val route = project("/p")

        assertEquals(route, findProjectRoute(projects(route), "/p"))
    }

    @Test
    fun `an absent path is not ready`() {
        assertNull(findProjectRoute(projects(project("/other")), "/p"))
    }

    @Test
    fun `an explicit backend cannot match the same path routed by another backend`() {
        val wrong = project("/p", projectName = "wrong-key", backendName = "iu-wrong")
        val expected = project("/p", projectName = "right-key", backendName = "iu-right")

        assertEquals(expected, findProjectRoute(projects(wrong, expected), "/p", "iu-right"))
        assertNull(findProjectRoute(projects(wrong), "/p", "iu-right"))
    }

    @Test
    fun `awaitProjectReady returns the opaque route once the path appears`() = runTest {
        var time = 0L
        var calls = 0
        val expected = project("/p", projectName = "opaque-9fk2a0xq")

        val route = awaitProjectReady(
            pollListProjects = {
                calls += 1
                if (calls < 3) projects() else projects(expected)
            },
            projectPath = "/p",
            timeoutMs = 300_000,
            intervalMs = 1_000,
            now = { time },
            sleep = { time += it },
        )

        assertEquals(expected, route)
        assertEquals(3, calls)
    }

    @Test
    fun `awaitProjectReady returns null at timeout`() = runTest {
        var time = 0L

        val route = awaitProjectReady(
            pollListProjects = { projects() },
            projectPath = "/p",
            timeoutMs = 5_000,
            intervalMs = 1_000,
            now = { time },
            sleep = { time += it },
        )

        assertNull(route)
    }
}
