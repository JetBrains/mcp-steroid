/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
@file:Suppress("RedundantOverride")

package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.JUnit38AssumeSupportRunner
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.util.ThrowableRunnable
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.Assert
import org.junit.AssumptionViolatedException
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for Gemini CLI with MCP server.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - GEMINI_API_KEY must be available (either in env or ~/.vertex)
 *
 * Documented CI exception (root CLAUDE.md, "Single documented exception: Gemini API
 * key on CI"): TeamCity has no Gemini token by design. This class is JUnit 3
 * (`BasePlatformTestCase`), and the plain JUnit 3↔4 bridge (`JUnit38ClassRunner`)
 * reports the `AssumptionViolatedException` thrown by `requireApiKey()` as a test
 * FAILURE — its `addError` fires `fireTestFailure` for every `Throwable`
 * (JUnit 4.13.2; issue #408 showed 6 failures on every TC run). The platform's
 * [JUnit38AssumeSupportRunner] exists for exactly this case: it reroutes
 * `AssumptionViolatedException` to `fireTestAssumptionFailed`, so the missing-key
 * case reports as SKIPPED (ignored) — the test stays visible in reports and never
 * shows a misleading green "ran" result. [runBare] throws the assumption before
 * the platform fixture spins up, so the skip also costs no setUp/Docker work.
 *
 * An unresolved `%credentialsJSON:…%` reference deliberately does NOT skip:
 * `skipTestBecauseApiKeyMissing()` returns `false` for it, the test body runs,
 * and `requireApiKey()` fails hard with `IllegalStateException` — a real TC
 * misconfiguration must stay visible.
 */
@RunWith(JUnit38AssumeSupportRunner::class)
class CliGeminiIntegrationTest : CliIntegrationTestBase() {
    private fun geminiSession() = DockerGeminiSession.create(lifetime)

    override fun createAiSession(): AiAgentSession = geminiSession()

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        if (DockerGeminiSession.skipTestBecauseApiKeyMissing()) {
            val message = "Gemini API key not found — documented CI exception, see CLAUDE.md"
            System.err.println("SKIPPED $name: $message")
            // JUnit38AssumeSupportRunner (class annotation) reports this as an
            // assumption failure => SKIPPED/ignored, not failed. Thrown before
            // super.runBare so neither setUp() nor tearDown() run.
            throw AssumptionViolatedException(message)
        }
        super.runBare(testRunnable)
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
