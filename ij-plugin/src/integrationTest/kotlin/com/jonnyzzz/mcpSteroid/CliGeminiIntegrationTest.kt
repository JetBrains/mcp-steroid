/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
@file:Suppress("RedundantOverride")

package com.jonnyzzz.mcpSteroid

import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.Assert
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for Gemini CLI with MCP server.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - GEMINI_API_KEY must be available (either in env or ~/.vertex)
 */
class CliGeminiIntegrationTest : CliIntegrationTestBase() {
    private fun geminiSession() = DockerGeminiSession.create(lifetime)

    override fun createAiSession(): AiAgentSession = geminiSession()

    /**
     * Documented CI exception (root CLAUDE.md, "Single documented exception: Gemini API
     * key on CI"): TeamCity has no Gemini token by design. This class is JUnit 3
     * (`BasePlatformTestCase`), where `JUnit38ClassRunner` reports the
     * `AssumptionViolatedException` thrown by `requireApiKey()` as a test FAILURE
     * (its `addError` fires `fireTestFailure` for every `Throwable` — JUnit 4.13.2).
     * `UsefulTestCase.runBare()` checking `shouldRunTest()` is the platform's skip
     * hook for that path, so gate on the missing key here instead of failing 6 tests
     * on every TC run.
     *
     * An unresolved `%credentialsJSON:…%` reference deliberately does NOT skip:
     * `skipTestBecauseApiKeyMissing()` returns `false` for it, the test body runs,
     * and `requireApiKey()` fails hard with `IllegalStateException` — a real TC
     * misconfiguration must stay visible.
     */
    override fun shouldRunTest(): Boolean {
        if (DockerGeminiSession.skipTestBecauseApiKeyMissing()) {
            System.err.println(
                "SKIPPED $name: Gemini API key not found — documented CI exception, see CLAUDE.md"
            )
            return false
        }
        return super.shouldRunTest()
    }

    fun testGeminiInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        geminiSession()
            .runInContainer(args = listOf("--version"))
            .assertExitCode(0) { "Gemini failed" }
    }

    fun testMcpServerRegistration() {
        val mcpName = "intellij"
        timeoutRunBlocking(180.seconds) {
            val session = geminiSession()
            session.registerHttpMcp(resolveDockerUrl(), mcpName)
            session.runInContainer(listOf("mcp", "list"))
                .assertExitCode(0) { "mcp list should succeed" }
                .assertOutputContains(mcpName, message = "mcp list should contain registered server")
        }
    }


    override fun testDiscoversSteroidTools() {
        //needed to make test runner work
        super.testDiscoversSteroidTools()
    }

    override fun testSystemPropertyCanBeRead() {
        //needed to make test runner work
        super.testSystemPropertyCanBeRead()
    }

    override fun testCompilationErrorsDelivered() {
        //needed to make test runner work
        super.testCompilationErrorsDelivered()
    }

    override fun testCompilationWarningsDelivered() {
        //needed to make test runner work
        super.testCompilationWarningsDelivered()
    }

    override fun testExecSessionReset() {
        //the test is ignored
        //needed to make test runner work
        //super.testExecSessionReset()
    }
}
