/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeChannel
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Runtime compatibility: validates the production plugin (built against 253)
 * works correctly when loaded into newer IDE versions.
 *
 * Strategy: the plugin is always built against 253. These tests install the
 * same binary into the latest stable and EAP IDEs and exercise core MCP tools.
 * The list_windows call specifically triggers mcp-steroid#18 (ClassCastException
 * on the Pair type change in 262).
 *
 * Run:
 *   ./gradlew :test-experiments:test --tests '*PluginRuntimeCompatibilityTest*'
 */
class PluginRuntimeCompatibilityTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `runtime compat stable`() =
        verifyRuntimeCompat(IdeDistribution.Latest(IdeProduct.IntelliJIdea, IdeChannel.STABLE))

    // Reproduces mcp-steroid#18 at runtime: the 253-built plugin calls
    // StatusBarEx.getBackgroundProcessModels() which returns kotlin.Pair in 262,
    // but the plugin bytecode expects c.i.o.u.Pair → ClassCastException.
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `runtime compat eap`() =
        verifyRuntimeCompat(IdeDistribution.Latest(IdeProduct.IntelliJIdea, IdeChannel.EAP))

    private fun verifyRuntimeCompat(dist: IdeDistribution) = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            "ide-agent",
            consoleTitle = "runtime-compat",
            distribution = dist,
        )

        // 1. list_projects — plugin loaded, MCP server started
        val projects = session.mcpSteroid.mcpListProjects()
        assertTrue(projects.isNotEmpty(), "Should have at least one project")

        // 2. list_windows — triggers mcp-steroid#18 on 262+
        val windows = session.mcpSteroid.mcpListWindows()
        assertTrue(windows.isNotEmpty(), "Should have at least one window")
        val projectWindow = windows.find { it.projectName != null }
        assertNotNull(projectWindow, "Should have a window with project")
        assertFalse(projectWindow!!.modalDialogShowing, "Should not have modal dialog")

        // 3. execute_code — compilation + execution works
        session.mcpSteroid.mcpExecuteCode(
            code = """
                val version = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
                println("RUNTIME_COMPAT_OK: ${'$'}version")
            """.trimIndent(),
            taskId = "runtime-compat",
            reason = "Runtime compatibility check",
        ).assertExitCode(0, "execute_code should succeed")
            .assertOutputContains("RUNTIME_COMPAT_OK", message = "should print IDE version")
    }
}
