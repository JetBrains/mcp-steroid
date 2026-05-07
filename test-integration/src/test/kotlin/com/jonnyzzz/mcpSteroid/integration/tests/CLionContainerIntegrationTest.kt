/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class CLionContainerIntegrationTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `container starts and CLion becomes ready`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            "clion-agent",
            consoleTitle = "clion-container",
            distribution = IdeDistribution.Latest(IdeProduct.CLion),
        ).waitForProjectReady()

        val projects = session.mcpSteroid.mcpListProjects()
        check(projects.isNotEmpty()) { "Expected at least one project to be open in CLion session" }
    }
}
