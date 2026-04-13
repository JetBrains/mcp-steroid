/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.git.BareRepoCache
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * A/B comparison test: "agent with MCP Steroid" vs "agent without MCP Steroid".
 *
 * Runs the same DPAIA arena cases twice — once with IntelliJ IDE tools available
 * via [AiMode.AI_MCP] and once without ([AiMode.NONE]) — then logs and compares outcomes.
 *
 * The goal is to show cases where MCP Steroid provides measurably better results:
 * - Agent with MCP: opens project in IDE, uses steroid_execute_code for navigation,
 *   compilation, and test running
 * - Agent without MCP: uses only bash/shell commands, mvn/gradle, and file tools
 *
 * Two shared containers are kept alive across all test cases:
 * - [sessionWithMcp]: IntelliJ + agents registered with MCP Steroid ([AiMode.AI_MCP])
 * - [sessionWithoutMcp]: IntelliJ running, agents have NO MCP registered ([AiMode.NONE])
 *
 * Test cases come from [DpaiaCuratedCases.PRIMARY_COMPARISON_CASES].
 *
 * **Run a specific subset:**
 * ```
 * -Dcomparison.test.cases=dpaia__spring__petclinic__rest-14,dpaia__feature__service-25
 * ```
 *
 * **Limit the number of cases:**
 * ```
 * -Dcomparison.test.maxCases=1
 * ```
 */
class DpaiaComparisonTest {

    @TestFactory
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `comparison test cases`(): List<DynamicTest> {
        val cases = selectTestCases()
        println("[COMPARISON] Running ${cases.size} case(s) × 2 agents × 2 modes = ${cases.size * 4} dynamic tests")

        // Pairs: agentName → (agentWithMcp, agentWithoutMcp)
        val agentPairs = listOf(
            "claude" to (sessionWithMcp.aiAgents.claude to sessionWithoutMcp.aiAgents.claude),
            "codex"  to (sessionWithMcp.aiAgents.codex  to sessionWithoutMcp.aiAgents.codex),
        )

        return cases.flatMap { testCase ->
            agentPairs.flatMap { (agentName, pair) ->
                val (agentWithMcp, agentWithoutMcp) = pair
                listOf(
                    DynamicTest.dynamicTest("[$agentName+mcp] ${testCase.instanceId}") {
                        runComparisonTest(testCase, agentName, agentWithMcp, withMcp = true, sessionWithMcp)
                    },
                    DynamicTest.dynamicTest("[$agentName+none] ${testCase.instanceId}") {
                        runComparisonTest(testCase, agentName, agentWithoutMcp, withMcp = false, sessionWithoutMcp)
                    },
                )
            }
        }
    }

    private fun runComparisonTest(
        testCase: DpaiaTestCase,
        agentName: String,
        agent: AiAgentSession,
        withMcp: Boolean,
        session: IntelliJContainer,
    ) {
        val modeLabel = if (withMcp) "MCP" else "NONE"
        println("[COMPARISON] ========================================")
        println("[COMPARISON] Running: ${testCase.instanceId} [$agentName, $modeLabel]")
        println("[COMPARISON] Repo: ${testCase.repo}")
        println("[COMPARISON] Tags: ${testCase.tags}")
        println("[COMPARISON] ========================================")

        val runner = ArenaTestRunner(
            container = session.scope,
            projectGuestDir = ARENA_WORKSPACE,
        )

        val caseConfig = DpaiaCuratedCases.CASE_CONFIGS[testCase.instanceId] ?: DpaiaCuratedCases.CaseConfig()
        val result = runner.runTest(
            testCase = testCase,
            agent = agent,
            withMcp = withMcp,
            timeoutSeconds = caseConfig.agentTimeoutSeconds,
        )

        // Log both outcomes — the comparison value is in the printed metrics.
        // Tests do not hard-fail here so both MCP and no-MCP results are always collected.
        println("[COMPARISON] ========================================")
        println("[COMPARISON] Result: ${testCase.instanceId} [$agentName, $modeLabel]")
        println("[COMPARISON]   Exit code:    ${result.agentResult.exitCode}")
        println("[COMPARISON]   Claimed fix:  ${result.evaluation.agentClaimedFix}")
        println("[COMPARISON]   Used MCP:     ${result.evaluation.usedMcpSteroid}")
        println("[COMPARISON]   Summary:      ${result.evaluation.agentSummary ?: "(none)"}")
        println("[COMPARISON] ========================================")

        // A pass requires the agent to have at minimum attempted a fix
        check(result.evaluation.agentExitedSuccessfully || result.evaluation.agentClaimedFix) {
            "Agent [$agentName, $modeLabel] neither exited successfully nor claimed a fix " +
                    "for ${testCase.instanceId}.\nOutput:\n${result.agentResult.stdout}"
        }
    }

    companion object {
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        /** Guest directory for cloned arena projects — separate from DpaiaArenaTest workspace. */
        private const val ARENA_WORKSPACE = "/home/agent/comparison-projects"

        @JvmStatic
        val lifetimeWithMcp by lazy { CloseableStackHost() }

        @JvmStatic
        val lifetimeWithoutMcp by lazy { CloseableStackHost() }

        /**
         * Maximum project-ready timeout across all configured cases.
         * Ensures the IDE startup wait covers the slowest case (e.g. microshop-18 with 20-min timeout).
         */
        private val maxProjectReadyTimeoutMs: Long =
            DpaiaCuratedCases.PRIMARY_COMPARISON_CASES.maxOf { id ->
                DpaiaCuratedCases.CASE_CONFIGS[id]?.projectReadyTimeoutMs
                    ?: DpaiaCuratedCases.CaseConfig().projectReadyTimeoutMs
            }

        /** Container where agents connect to MCP Steroid via HTTP. */
        val sessionWithMcp by lazy {
            IntelliJContainer.create(
                lifetimeWithMcp,
                consoleTitle = "comparison-mcp",
                aiMode = AiMode.AI_MCP,
                mountDockerSocket = true,
            ).waitForProjectReady(timeoutMillis = maxProjectReadyTimeoutMs)
        }

        /** Container where agents have NO MCP Steroid — baseline/control group. */
        val sessionWithoutMcp by lazy {
            IntelliJContainer.create(
                lifetimeWithoutMcp,
                consoleTitle = "comparison-none",
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = true,
            ).waitForProjectReady(timeoutMillis = maxProjectReadyTimeoutMs)
        }

        private val dataset by lazy {
            println("[COMPARISON] Downloading dataset from $DATASET_URL ...")
            val cases = DpaiaDatasetLoader.loadFromUrl(DATASET_URL)
            println("[COMPARISON] Loaded ${cases.size} test cases")
            cases
        }

        private fun selectTestCases(): List<DpaiaTestCase> {
            val specificIds = System.getProperty("comparison.test.cases")
            val ids = if (!specificIds.isNullOrBlank()) {
                specificIds.split(',').map { it.trim() }.filter { it.isNotBlank() }
            } else {
                DpaiaCuratedCases.PRIMARY_COMPARISON_CASES
            }
            val maxCases = System.getProperty("comparison.test.maxCases")?.toIntOrNull() ?: ids.size
            return ids.take(maxCases).map { id -> DpaiaDatasetLoader.findById(dataset, id) }
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Warm bare repo cache on the host before containers start
            val cacheDir = IdeTestFolders.repoCacheDirOrNull
            if (cacheDir != null) {
                BareRepoCache.warmDpaiaRepos(cacheDir)
            }
            // Initialize both sessions so they are ready before tests run
            sessionWithMcp.toString()
            sessionWithoutMcp.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetimeWithMcp.closeAllStacks()
            lifetimeWithoutMcp.closeAllStacks()
        }
    }
}
