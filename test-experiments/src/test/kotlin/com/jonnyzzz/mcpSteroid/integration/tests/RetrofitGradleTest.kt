/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test: verifies an AI agent can navigate the Retrofit Gradle
 * multi-module project, pick one fast unit-test method, and run it through
 * IntelliJ's Gradle integration.
 *
 * Project under test: https://github.com/square/retrofit (Java, Apache Gradle,
 * multiple subprojects: `retrofit`, `retrofit:java-test`, `retrofit:android-test`,
 * `retrofit:kotlin-test`, `retrofit-mock`, `retrofit-converters` family,
 * `retrofit-adapters` family).
 *
 * The agent is intentionally NOT told which test to run. The point is to confirm
 * the agent can:
 *   1. Discover the multi-module Gradle layout via PSI/VFS
 *   2. Pick one fast plain-JUnit test (NOT an Android test, NOT an integration test)
 *   3. Drive Gradle through IntelliJ — `GradleRunConfiguration.isRunAsTest = true`
 *      + `(descriptor.executionConsole as SMTRunnerConsoleView).resultsViewer
 *      .testsRootNode.allTests` — NOT through `Bash` / `./gradlew`
 *   4. Report pass/fail through the marker contract
 *
 * Counterpart to [KeycloakMavenTest] / [YouTrackDbMavenTest], but for the Gradle
 * recipe documented at `mcp-steroid://skill/execute-code-gradle`.
 */
class RetrofitGradleTest {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one gradle test method claude`() = agentRunsOneGradleTest(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one gradle test method codex`() = agentRunsOneGradleTest(session.aiAgents.codex)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one gradle test method gemini`() = agentRunsOneGradleTest(session.aiAgents.gemini)

    private fun agentRunsOneGradleTest(agent: AiAgentSession) {
        val prompt = buildRetrofitGradlePrompt()

        val result = agent.runPrompt(prompt, timeoutSeconds = 1500).awaitForProcessFinish()
        result.assertExitCode(0, message = "retrofit gradle test run for ${agent.displayName}")

        assertRetrofitGradleAgentSucceeded(result.stdout + "\n" + result.stderr)

        println("[TEST] Agent '${agent.displayName}' successfully ran a Retrofit Gradle test")
    }

    companion object {

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                "ide-agent",
                consoleTitle = "retrofit",
                project = IntelliJProject.RetrofitProject,
            ).waitForProjectReady(buildSystem = BuildSystem.GRADLE)
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Repo cache warming is handled automatically by IntelliJContainer.create()
            // based on the project's getRepoUrlForCache(). No explicit call needed here.
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
