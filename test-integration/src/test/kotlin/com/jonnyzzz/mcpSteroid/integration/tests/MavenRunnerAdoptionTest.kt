/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Fast-iteration test measuring whether an AI agent uses MavenRunConfigurationType
 * (via steroid_execute_code) instead of falling back to Bash `./mvnw test`
 * when asked to run Maven tests.
 *
 * Target runtime: ~2-3 minutes (container startup + single agent prompt).
 *
 * Uses test-project-maven which has a simple Calculator class with passing JUnit tests.
 */
class MavenRunnerAdoptionTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(MavenRunnerAdoptionTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                "ide-agent",
                consoleTitle = "Maven Runner Adoption",
                project = IntelliJProject.MavenTestProject,
            ).waitForProjectReady(
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
            )
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `maven runner adoption - claude uses exec_code for test execution`() {
        val console = session.console
        val agent = session.aiAgents.claude

        console.writeStep(1, "Running Claude agent with Maven test prompt")
        val result = agent.runPrompt(
            "Run all Maven tests in this project. Report the test results (pass count, fail count).",
            timeoutSeconds = 300
        )
            .assertExitCode(0) { "Agent prompt failed" }
            .assertNoErrorsInOutput("Agent should not produce errors")

        console.writeStep(2, "Analyzing agent tool usage")

        val output = result.stdout
        val lines = output.lines()

        // Count steroid_execute_code calls related to test/maven execution
        val execCodeTestCalls = lines.count { line ->
            line.contains("steroid_execute_code") &&
                    (line.contains("test", ignoreCase = true) || line.contains("maven", ignoreCase = true))
        }

        // Count Bash ./mvnw test fallback calls
        val bashMvnCalls = lines.count { line ->
            line.contains("Bash", ignoreCase = true) &&
                    (line.contains("mvnw", ignoreCase = true) || line.contains("mvn ", ignoreCase = true))
        }

        // Report metrics
        println("=== Maven Runner Adoption Metrics ===")
        println("exec_code calls (test/maven): $execCodeTestCalls")
        println("bash mvn/mvnw calls: $bashMvnCalls")
        println("agent output length: ${output.length} chars, ${lines.size} lines")
        println("=====================================")

        console.writeStep(3, "Verifying exec_code adoption")

        // The agent should use exec_code for Maven test execution, not Bash ./mvnw test
        assertTrue(execCodeTestCalls > 0,
            "Agent should use steroid_execute_code for Maven test execution, " +
                    "but found $execCodeTestCalls exec_code calls and $bashMvnCalls bash mvn calls. " +
                    "Output:\n${output.take(2000)}"
        )

        if (bashMvnCalls > 0) {
            println("WARNING: Agent also used Bash mvn/mvnw ($bashMvnCalls calls) — exec_code is preferred")
        }

        console.writeSuccess("Claude used steroid_execute_code for Maven test execution " +
                "(exec_code=$execCodeTestCalls, bash_mvn=$bashMvnCalls)")
    }
}
