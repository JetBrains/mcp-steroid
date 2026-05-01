/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostArchitecture
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
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
 * Same exercise as [YouTrackDbMavenTest], but pinned via [IdeDistribution.FromUrl]
 * to IntelliJ IDEA 2025.3 (build prefix `IU-253.*`). 253 is the IDE version the
 * plugin itself is compiled against (see CLAUDE.md → "Multi-Version Compatibility
 * Strategy"). Keeping a 253-pinned regression run validates that the
 * `mcp-steroid` plugin still operates correctly against the build-target IDE
 * even after the surrounding test fleet has moved its default to 261/262.
 *
 * Update the URL when a new 2025.3.x patch ships; do NOT bump it to 2026.x —
 * use the [YouTrackDbMaven261Test] sibling for that.
 */
class YouTrackDbMaven253Test {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method claude on 253`() = agentRunsOneMavenTest(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method codex on 253`() = agentRunsOneMavenTest(session.aiAgents.codex)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method gemini on 253`() = agentRunsOneMavenTest(session.aiAgents.gemini)

    private fun agentRunsOneMavenTest(agent: AiAgentSession) {
        val prompt = buildString {
            appendLine("The youtrackdb Java project is open in IntelliJ IDEA. It is a multi-module Apache Maven project.")
            appendLine()
            appendLine("Your task: pick exactly ONE test method that is fast to run, and run it via Maven.")
            appendLine()
            appendLine("Selection rules for the test method:")
            appendLine("- Pick a plain JUnit unit test. Avoid integration tests, anything named `*IT`, `*ITest`, anything using `@Testcontainers`, anything that boots a server, and anything that touches the network or large disk fixtures.")
            appendLine("- A test in the `core` module (or any small leaf module) is preferred — it should compile and run in tens of seconds.")
            appendLine("- One test method, not the whole class.")
            appendLine()
            appendLine("Execution rules:")
            appendLine("- Use Maven, not IntelliJ run configurations and not Gradle. Either `./mvnw` from the project root, or the bundled IDE Maven at `/opt/idea/plugins/maven/lib/maven3/bin/mvn` if the wrapper is missing or not executable.")
            appendLine("- Target the specific module with `-pl <module>` and the specific method with `-Dtest=<TestClass>#<testMethod>`.")
            appendLine("- Skip integration tests (`-DskipITs`) and skip spotless / formatting checks if they fail (`-Dspotless.check.skip=true`). DO NOT pass `-am` (also-make) — it would build dozens of upstream modules and cause OOM.")
            appendLine("- If the first attempt fails because a dependency module is missing, install ONLY that one upstream module: `mvn install -pl <missing-module> -DskipTests -Dspotless.check.skip=true`. Then retry the targeted test. Do NOT install the whole project.")
            appendLine()
            appendLine("Use `steroid_execute_code` to navigate the project (find pom.xml, list modules, find candidate tests via PSI/VFS).")
            appendLine("Use the `Bash` tool to invoke Maven (running `./mvnw` via `steroid_execute_code` is banned in this codebase).")
            appendLine()
            appendLine("After your first `steroid_execute_code` call, copy the `execution_id:` line so we can verify MCP was used:")
            appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
            appendLine()
            appendLine("At the end of your final response, output these markers on their own lines:")
            appendLine("TEST_CLASS: <fully qualified test class name>")
            appendLine("TEST_METHOD: <test method name>")
            appendLine("MAVEN_MODULE: <Maven submodule path you targeted with -pl, e.g. core>")
            appendLine("BUILD_RESULT: <copy the literal `BUILD SUCCESS` / `BUILD FAILURE` line from Maven output>")
            appendLine("TESTS_RUN: <copy the literal `Tests run: ...` line from Maven output>")
            appendLine("YOUTRACKDB_MAVEN_TEST_RAN: yes")
        }

        val result = agent.runPrompt(prompt, timeoutSeconds = 1500).awaitForProcessFinish()
        result.assertExitCode(0, message = "youtrackdb maven test run for ${agent.displayName} on 253")

        val combined = result.stdout + "\n" + result.stderr

        check(combined.contains("YOUTRACKDB_MAVEN_TEST_RAN: yes", ignoreCase = false)) {
            "Agent did not report YOUTRACKDB_MAVEN_TEST_RAN: yes.\nOutput:\n$combined"
        }

        assertUsedExecuteCodeEvidence(combined)

        val mavenInvocationPatterns = listOf("./mvnw", "maven3/bin/mvn", "mvn ", "BUILD SUCCESS", "Tests run:")
        val invokedMaven = mavenInvocationPatterns.any { combined.contains(it) }
        check(invokedMaven) {
            "Agent did not show evidence of running Maven.\nExpected one of: $mavenInvocationPatterns\nOutput:\n$combined"
        }

        check(combined.contains("BUILD SUCCESS")) {
            "Agent did not report a `BUILD SUCCESS` from Maven — the chosen test must actually pass.\nOutput:\n$combined"
        }

        check(combined.contains("Tests run:")) {
            "Agent did not report a `Tests run:` line — Surefire output was not captured.\nOutput:\n$combined"
        }

        for (marker in listOf("TEST_CLASS:", "TEST_METHOD:", "MAVEN_MODULE:")) {
            check(combined.contains(marker)) {
                "Agent did not emit the `$marker` marker.\nOutput:\n$combined"
            }
        }

        println("[TEST] Agent '${agent.displayName}' successfully ran a youtrackdb Maven test on pinned 253")
    }

    private fun assertUsedExecuteCodeEvidence(combined: String) {
        val toolEvidencePatterns = listOf(
            "execution_id:",
            "tool mcp-steroid.steroid_execute_code",
            "steroid_execute_code(",
            "TOOL_EVIDENCE:"
        )

        val hasToolEvidence = toolEvidencePatterns.any { pattern ->
            combined.contains(pattern, ignoreCase = true)
        }
        check(hasToolEvidence) {
            "Agent must show evidence of steroid_execute_code usage.\n" +
                    "Expected one of: $toolEvidencePatterns\nOutput:\n$combined"
        }
    }

    companion object {

        // Pinned IntelliJ IDEA 2025.3 (build prefix IU-253.*) — Linux tarball.
        // 253 is the build target of the mcp-steroid plugin itself; this sibling
        // keeps a regression run on the build-target IDE even after the default
        // STABLE channel has moved on. Bump the patch suffix when 2025.3.6+
        // ships; never bump to 2026.x — use the YouTrackDbMaven261Test sibling.
        private val PINNED_253_URL: String = when (resolveHostArchitecture()) {
            HostArchitecture.ARM64 -> "https://download.jetbrains.com/idea/ideaIU-2025.3-aarch64.tar.gz"
            HostArchitecture.X86_64 -> "https://download.jetbrains.com/idea/ideaIU-2025.3.tar.gz"
        }

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                "ide-agent",
                consoleTitle = "youtrackdb-253",
                project = IntelliJProject.YouTrackDbProject,
                distribution = IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdea,
                    url = PINNED_253_URL,
                ),
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
