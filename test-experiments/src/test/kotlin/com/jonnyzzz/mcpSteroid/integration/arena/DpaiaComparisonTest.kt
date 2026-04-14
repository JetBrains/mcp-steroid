/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A/B comparison test: "agent with MCP Steroid" vs "agent without MCP Steroid".
 *
 * Each test method starts a **fresh Docker container** so agents never see state
 * left by a previous run. After all methods finish, [printComparisonTable] prints
 * a side-by-side summary.
 *
 * **Run a specific subset:**
 * ```
 * -Dcomparison.test.cases=dpaia__spring__petclinic__rest-14
 * ```
 *
 * **Run a single agent+mode:**
 * ```
 * --tests '*DpaiaComparisonTest.claude with mcp'
 * ```
 */
class DpaiaComparisonTest {

    // ── Claude ───────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() {
        runComparisonTest(agentName = "claude", withMcp = true)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() {
        runComparisonTest(agentName = "claude", withMcp = false)
    }

    // ── Codex ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() {
        runComparisonTest(agentName = "codex", withMcp = true)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() {
        runComparisonTest(agentName = "codex", withMcp = false)
    }

    // ── Gemini ───────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `gemini with mcp`() {
        runComparisonTest(agentName = "gemini", withMcp = true)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `gemini without mcp`() {
        runComparisonTest(agentName = "gemini", withMcp = false)
    }

    // ── Test execution ───────────────────────────────────────────────────────

    private fun runComparisonTest(agentName: String, withMcp: Boolean) {
        val cases = selectTestCases()
        for (testCase in cases) {
            runSingleCase(testCase, agentName, withMcp)
        }
    }

    private fun runSingleCase(testCase: DpaiaTestCase, agentName: String, withMcp: Boolean) {
        val modeLabel = if (withMcp) "mcp" else "none"
        val caseConfig = DpaiaCuratedCases.CASE_CONFIGS[testCase.instanceId]
            ?: DpaiaCuratedCases.CaseConfig()

        val lifetime = CloseableStackHost()
        try {
            val aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE
            val mcpMode = if (withMcp) null else McpConnectionMode.None

            val session = IntelliJContainer.create(
                lifetime,
                consoleTitle = "comparison-${testCase.instanceId}-$agentName-$modeLabel",
                aiMode = aiMode,
                mcpConnectionMode = mcpMode,
                mountDockerSocket = true,
            ).waitForProjectReady(
                timeoutMillis = caseConfig.projectReadyTimeoutMs,
            )

            val agent: AiAgentSession = when (agentName) {
                "claude" -> session.aiAgents.claude
                "codex" -> session.aiAgents.codex
                "gemini" -> session.aiAgents.gemini
                else -> error("Unknown agent: $agentName")
            }

            val runner = ArenaTestRunner(
                container = session.scope,
                projectGuestDir = ARENA_WORKSPACE,
            )

            val result = runner.runTest(
                testCase = testCase,
                agent = agent,
                withMcp = withMcp,
                timeoutSeconds = caseConfig.agentTimeoutSeconds,
            )

            results.add(
                RunRecord(
                    instanceId = testCase.instanceId,
                    agentName = agentName,
                    withMcp = withMcp,
                    exitCode = result.agentResult.exitCode,
                    claimedFix = result.evaluation.agentClaimedFix,
                    usedMcpSteroid = result.evaluation.usedMcpSteroid,
                    summary = result.evaluation.agentSummary,
                    agentDurationMs = result.agentDurationMs,
                )
            )

            println("[COMPARISON] [$agentName+$modeLabel] ${testCase.instanceId} — " +
                    "fix=${result.evaluation.agentClaimedFix}, " +
                    "mcp=${result.evaluation.usedMcpSteroid}, " +
                    "exit=${result.agentResult.exitCode}")

            check(result.evaluation.agentExitedSuccessfully || result.evaluation.agentClaimedFix) {
                "Agent [$agentName+$modeLabel] neither exited successfully nor claimed a fix " +
                        "for ${testCase.instanceId}.\nOutput:\n${result.agentResult.stdout}"
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        private const val ARENA_WORKSPACE = "/home/agent/comparison-projects"

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

        val results = CopyOnWriteArrayList<RunRecord>()

        @JvmStatic
        @AfterAll
        fun printComparisonTable() {
            if (results.isEmpty()) {
                println("[COMPARISON] No results to compare.")
                return
            }

            println()
            println("╔════════════════════════════════════════════════════════════════════════════════╗")
            println("║                      COMPARISON TABLE                                         ║")
            println("╠════════════════════════════════════════════════════════════════════════════════╣")
            println("║ Instance                          │ Agent+Mode      │ Fix? │ Exit │ Duration  ║")
            println("╠════════════════════════════════════════════════════════════════════════════════╣")
            for (r in results.sortedWith(compareBy({ it.instanceId }, { it.agentName }, { !it.withMcp }))) {
                val instance = r.instanceId.takeLast(33).padEnd(33)
                val mode = if (r.withMcp) "mcp " else "none"
                val label = "${r.agentName}+$mode".padEnd(15)
                val fix = if (r.claimedFix) " YES" else "  NO"
                val exit = (r.exitCode?.toString() ?: "?").padStart(4)
                val dur = "${r.agentDurationMs / 1000}s".padStart(9)
                println("║ $instance │ $label │ $fix │ $exit │ $dur ║")
            }
            println("╚════════════════════════════════════════════════════════════════════════════════╝")
            println()
        }
    }

    data class RunRecord(
        val instanceId: String,
        val agentName: String,
        val withMcp: Boolean,
        val exitCode: Int?,
        val claimedFix: Boolean,
        val usedMcpSteroid: Boolean,
        val summary: String?,
        val agentDurationMs: Long,
    )
}
