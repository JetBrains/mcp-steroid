/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Default-IDE variant of the structural-search youtrackdb scenario. Uses the
 * STABLE channel (whatever the current GA is). For pinned IDE-version variants
 * see [StructuralSearchYoutrackdb261Test] — same prompt, same assertions, only
 * the IDE container differs. Shared logic lives in
 * `StructuralSearchYoutrackdbPromptShared.kt`.
 *
 * The point of this scenario (vs. [StructuralSearchPromptTest] in
 * `:test-integration:`) is to validate the SSR skill articles against a real-
 * world Maven multi-module Java codebase: https://github.com/JetBrains/youtrackdb
 * — `Matcher` over `GlobalSearchScope.projectScope(project)`, real Java profile,
 * real indexer warm-up.
 */
class StructuralSearchYoutrackdbTest {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `ssr audit on youtrackdb claude`() = audit(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `ssr audit on youtrackdb codex`() = audit(session.aiAgents.codex)

    private fun audit(agent: AiAgentSession) =
        runStructuralSearchYoutrackdbAudit(session, agent, label = SSR_YOUTRACKDB_LABEL)

    companion object {

        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                "ide-agent",
                consoleTitle = "ssr / youtrackdb",
                project = IntelliJProject.YouTrackDbProject,
            ).waitForProjectReady(buildSystem = BuildSystem.MAVEN)
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
