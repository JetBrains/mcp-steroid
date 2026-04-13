/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test that runs dpaia.dev arena test cases.
 *
 * Each test case:
 * 1. Deploys the arena project at the IDE's project-home path BEFORE IntelliJ starts
 *    (via [IntelliJProject.ProjectFromGitCommitAndPatch]). This includes the clone,
 *    checkout, and test-patch application.
 * 2. IntelliJ starts and opens the arena project directly — no steroid_open_project needed.
 * 3. [waitForProjectReady] waits for the project to be fully indexed before the test begins.
 * 4. Asks an AI agent to fix the code via MCP Steroid (project already open and indexed).
 * 5. Evaluates whether the agent engaged meaningfully.
 * 6. Writes a run summary JSON for the improvement pipeline (docs/dpaia-runs/).
 *
 * The dataset is downloaded from:
 * https://github.com/dpaia/ee-dataset/blob/main/datasets/java-spring-ee-dataset.json
 *
 * To run a specific test case, set the system property:
 *   -Darena.test.instanceId=dpaia__empty__maven__springboot3-3
 *
 * To run only tests with a specific tag:
 *   -Darena.test.tag=JPA
 *
 * To limit the number of test cases (for CI):
 *   -Darena.test.maxCases=3
 */
class DpaiaArenaTest {

    @TestFactory
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `arena test cases`(): List<DynamicTest> {
        // If session initialization failed in @BeforeAll, return a single failing DynamicTest
        // so JUnit reports a real failure (not a skipped/missing class with no test records).
        val infra = sessionFailure
        if (infra != null) {
            return listOf(
                DynamicTest.dynamicTest("[infra] session initialization failed") {
                    throw AssertionError(
                        "[ARENA] INFRA_FAILURE: session could not be initialized. " +
                                "Cause: ${infra.message}",
                        infra
                    )
                }
            )
        }

        val selectedCases = selectTestCases()
        println("[ARENA] Selected ${selectedCases.size} test case(s) for execution")

        val agents = session.aiAgents.aiAgents

        // The arena project is already deployed at the IDE project-home path and fully
        // indexed by waitForProjectReady() in @BeforeAll. Pass this path to runTest()
        // so it skips re-cloning and uses the already-indexed project.
        val ideProjectDir = session.intellijDriver.getGuestProjectDir()

        return selectedCases.flatMap { testCase ->
            agents.map { (agentName, agent) ->
                DynamicTest.dynamicTest("[$agentName] ${testCase.instanceId}") {
                    val runner = ArenaTestRunner(
                        container = session.scope,
                        projectGuestDir = ideProjectDir,
                    )

                    val caseConfig = DpaiaCuratedCases.CASE_CONFIGS[testCase.instanceId] ?: DpaiaCuratedCases.CaseConfig()
                    val result = runner.runTest(
                        testCase = testCase,
                        agent = agent,
                        timeoutSeconds = caseConfig.agentTimeoutSeconds,
                        // No prewarm needed: project was deployed before IntelliJ started
                        // and is already fully indexed via waitForProjectReady() in @BeforeAll.
                        predeployedProjectDir = ideProjectDir,
                    )

                    // Write run summary BEFORE assertions so the improvement pipeline
                    // always gets data even when the test fails (e.g. agent didn't use MCP).
                    writeRunSummary(testCase, agentName, result)

                    // Basic assertions: agent ran successfully and used MCP Steroid
                    result.agentResult.assertExitCode(0, message = "arena test ${testCase.instanceId}")
                    result.agentResult.assertOutputContains(
                        "steroid_execute_code",
                        message = "agent must use steroid_execute_code for ${testCase.instanceId}"
                    )

                    println("[ARENA] Test ${testCase.instanceId} completed with agent '$agentName'")
                    println("[ARENA]   Claimed fix: ${result.evaluation.agentClaimedFix}")
                    println("[ARENA]   Summary: ${result.evaluation.agentSummary ?: "(none)"}")
                }
            }
        }
    }

    companion object {
        /** Dataset URL */
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        /**
         * Default test case to run when no system property is set.
         * This is a simple JPA entity test from a small Spring Boot project.
         */
        private const val DEFAULT_INSTANCE_ID = "dpaia__empty__maven__springboot3-3"

        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        /**
         * Stores the exception thrown during [session] lazy initialization, if any.
         * Set by [beforeAll] when session init fails — read by [arena test cases] to
         * return a single failing [DynamicTest] instead of having JUnit mark the entire
         * class as `initializationError` with zero test records.
         */
        @Volatile
        var sessionFailure: Exception? = null

        private val dataset by lazy {
            println("[ARENA] Downloading dataset from $DATASET_URL ...")
            val cases = DpaiaDatasetLoader.loadFromUrl(DATASET_URL)
            println("[ARENA] Loaded ${cases.size} test cases")
            cases
        }

        private fun selectTestCases(): List<DpaiaTestCase> {
            // Option 1: Specific instance ID via system property (defaults to DEFAULT_INSTANCE_ID)
            val specificId = System.getProperty("arena.test.instanceId") ?: DEFAULT_INSTANCE_ID
            if (System.getProperty("arena.test.tag") == null && System.getProperty("arena.test.maxCases") == null) {
                return listOf(DpaiaDatasetLoader.findById(dataset, specificId))
            }

            // Option 2: Filter by tag
            val tag = System.getProperty("arena.test.tag")
            val filtered = if (tag != null) {
                DpaiaDatasetLoader.filterByTag(dataset, tag)
            } else {
                dataset
            }

            // Option 3: Limit number of cases
            val maxCases = System.getProperty("arena.test.maxCases")?.toIntOrNull() ?: 1

            return filtered.take(maxCases)
        }

        /**
         * The single test case that will be pre-deployed before IntelliJ starts.
         * Resolved once from the system property / default so both [session] and
         * [selectTestCases] see the same case.
         */
        private val setupTestCase: DpaiaTestCase by lazy {
            selectTestCases().first()
        }

        /**
         * The Docker container with IntelliJ IDEA.
         *
         * The arena project ([setupTestCase]) is deployed to the IDE's project-home path
         * BEFORE IntelliJ starts — via [IntelliJProject.ProjectFromGitCommitAndPatch].
         * [waitForProjectReady] then waits for the project to be fully indexed.
         * When this lazy completes, the arena project is open and indexed in IntelliJ.
         */
        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                "ide-agent",
                consoleTitle = "dpaia-arena",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = setupTestCase.cloneUrl,
                    repoOwnerAndName = setupTestCase.repo.removeSuffix(".git"),
                    baseCommit = setupTestCase.baseCommit,
                    testPatch = setupTestCase.testPatch,
                    displayName = setupTestCase.instanceId,
                    buildSystem = setupTestCase.buildSystem,
                ),
                mountDockerSocket = true,
            ).waitForProjectReady()
        }

        /**
         * Write a run summary JSON for the improvement pipeline (docs/dpaia-runs/).
         *
         * Path: [IdeTestFolders.testOutputDir]/dpaia-arena-run-{instanceId}.json
         *
         * The shell pipeline (run-one.sh) reads this to locate the arena run directory and
         * metrics without searching through the build directory tree.
         *
         * Called BEFORE assertions so data is always captured even on test failure.
         */
        private fun writeRunSummary(testCase: DpaiaTestCase, agentName: String, result: ArenaTestResult) {
            val combined = result.agentResult.stdout + "\n" + result.agentResult.stderr
            val execCodeCalls = combined.lines().count { "steroid_execute_code" in it.lowercase() }

            val summary = buildJsonObject {
                put("instance_id", testCase.instanceId)
                put("agent", agentName)
                put("run_dir", session.runDirInContainer.absolutePath)
                put("exit_code", result.agentResult.exitCode ?: -1)
                put("agent_claimed_fix", result.evaluation.agentClaimedFix)
                put("used_mcp_steroid", result.evaluation.usedMcpSteroid)
                put("exec_code_calls", execCodeCalls)
                put("agent_duration_ms", result.agentDurationMs)
                put("prewarm_duration_ms", result.prewarmDurationMs)
                put("agent_summary", result.evaluation.agentSummary ?: "")
                put("timestamp", java.time.Instant.now().toString())
            }

            val summaryFile = IdeTestFolders.testOutputDir.resolve("dpaia-arena-run-${testCase.instanceId}.json")
            summaryFile.parentFile.mkdirs()
            summaryFile.writeText(summary.toString())
            println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")
            println("[ARENA] Run dir (for improvement pipeline): ${session.runDirInContainer.absolutePath}")
        }

        /**
         * Write an infra-failure summary JSON so the improvement pipeline can detect
         * infrastructure failures without parsing raw log text.
         *
         * Called from [beforeAll] on session init failure. Does NOT throw — logs any
         * write error without masking the original exception.
         */
        private fun writeInfraFailureSummary(instanceId: String, e: Exception) {
            val summary = buildJsonObject {
                put("instance_id", instanceId)
                put("infra_failure", true)
                put("infra_failure_message", e.message ?: "unknown")
                put("timestamp", java.time.Instant.now().toString())
            }
            try {
                val summaryFile = IdeTestFolders.testOutputDir.resolve("dpaia-arena-run-$instanceId.json")
                summaryFile.parentFile.mkdirs()
                summaryFile.writeText(summary.toString())
                println("[ARENA] Infra failure summary written to: ${summaryFile.absolutePath}")
            } catch (_: Exception) {
                // Don't mask the original exception
            }
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Repo cache warming is handled automatically by IntelliJContainer.create()
            // based on the project's getRepoUrlForCache(). No explicit call needed here.
            try {
                session.toString()
            } catch (e: Exception) {
                // Log a structured message so the failure pipeline can detect infrastructure
                // failures (container crash, display startup, IDE never appeared) separately
                // from agent behavior failures (agent ran but didn't use MCP).
                System.err.println("[ARENA] INFRA_FAILURE: session initialization failed")
                System.err.println("[ARENA] INFRA_FAILURE cause: ${e.message}")
                // Write a structured JSON artifact so Phase 3/4 analysis can detect infra-only
                // failures without parsing raw log text (avoids misleading "0 MCP calls" verdicts).
                writeInfraFailureSummary(setupTestCase.instanceId, e)
                // Store the failure instead of rethrowing — rethrowing here causes JUnit to mark
                // the entire class as initializationError with zero test records, hiding the
                // failure from test reports. Instead, we surface it as a real failing DynamicTest
                // in the @TestFactory so the run shows a visible, named failure.
                sessionFailure = e
            }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
