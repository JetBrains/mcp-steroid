/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Dedicated arena test for a single DPAIA scenario — **Claude Code only**.
 *
 * Each test method launches a **fresh Docker container** with IntelliJ IDEA.
 * Before the agent timer starts, the test runs a full prewarm:
 * 1. Maven import + JDK setup (via [waitForProjectReady])
 * 2. `./mvnw compile -DskipTests` (compiles Java + installs npm packages via frontend-maven-plugin)
 *
 * Only after the project is fully built does the agent timer start.
 *
 * **Usage:**
 * ```
 * ./gradlew :test-experiments:test --tests '*DpaiaJhipsterArenaTest*' \
 *   -Darena.test.instanceId=dpaia__jhipster__sample__app-3
 * ```
 *
 * **Run a single mode:**
 * ```
 * --tests '*DpaiaJhipsterArenaTest.claude with mcp'
 * ```
 */
class DpaiaJhipsterArenaTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() {
        runClaude(withMcp = true)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() {
        runClaude(withMcp = false)
    }

    // ── Test execution ───────────────────────────────────────────────────────

    private fun runClaude(withMcp: Boolean) {
        val testCase = resolvedTestCase
        val modeLabel = if (withMcp) "mcp" else "none"
        val caseConfig = DpaiaCuratedCases.CASE_CONFIGS[testCase.instanceId]
            ?: DpaiaCuratedCases.CaseConfig()

        val lifetime = CloseableStackHost()
        try {
            val aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE
            val mcpMode = if (withMcp) null else McpConnectionMode.None

            println("[ARENA] Creating container for [claude+$modeLabel] ${testCase.instanceId} ...")

            val session = IntelliJContainer.create(
                lifetime,
                consoleTitle = "jhipster-claude-$modeLabel",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = testCase.cloneUrl,
                    repoOwnerAndName = testCase.repo.removeSuffix(".git"),
                    baseCommit = testCase.baseCommit,
                    testPatch = testCase.testPatch,
                    displayName = testCase.instanceId,
                    buildSystem = testCase.buildSystem,
                ),
                aiMode = aiMode,
                mcpConnectionMode = mcpMode,
                mountDockerSocket = true,
            ).waitForProjectReady(
                timeoutMillis = caseConfig.projectReadyTimeoutMs,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
            )

            val ideProjectDir = session.intellijDriver.getGuestProjectDir()

            // ── Agent run (TIMED) ────────────────────────────────────────────────
            val agent = session.aiAgents.claude
            val runner = ArenaTestRunner(
                container = session.scope,
                projectGuestDir = ideProjectDir,
            )

            val result = runner.runTest(
                testCase = testCase,
                agent = agent,
                withMcp = withMcp,
                timeoutSeconds = caseConfig.agentTimeoutSeconds,
                predeployedProjectDir = ideProjectDir,
            )

            // ── Extract metrics from Claude NDJSON ───────────────────────────────
            val rawOutput = result.agentResult.stdout
            val tokens = extractTokenUsage(rawOutput)
            val testMetrics = extractTestMetrics(rawOutput)

            val record = RunRecord(
                instanceId = testCase.instanceId,
                withMcp = withMcp,
                agentDurationMs = result.agentDurationMs,
                prewarmMs = 0L, // Prewarm is now inside waitForProjectReady
                exitCode = result.agentResult.exitCode,
                claimedFix = result.evaluation.agentClaimedFix,
                usedMcpSteroid = result.evaluation.usedMcpSteroid,
                summary = result.evaluation.agentSummary,
                tokenUsage = tokens,
                testMetrics = testMetrics,
            )
            results.add(record)

            // Write JSON summary
            writeRunSummary(testCase, modeLabel, result, record, session.runDirInContainer)

            // Print summary
            println("[ARENA] ════════════════════════════════════════")
            println("[ARENA] claude+$modeLabel — ${testCase.instanceId}")
            println("[ARENA]   Claimed fix:    ${record.claimedFix}")
            println("[ARENA]   Used MCP:       ${record.usedMcpSteroid}")
            println("[ARENA]   Exit code:      ${record.exitCode}")
            println("[ARENA]   Agent time:     ${record.agentDurationMs / 1000}s")
            println("[ARENA]   Prewarm time:   ${record.prewarmMs / 1000}s")
            if (tokens != null) {
                println("[ARENA]   Tokens in/out:  ${tokens.inputTokens}/${tokens.outputTokens}")
                println("[ARENA]   Cache read:     ${tokens.cacheReadTokens}")
                println("[ARENA]   Cost:           $${tokens.costUsd ?: "?"}")
                println("[ARENA]   Turns:          ${tokens.numTurns ?: "?"}")
            }
            if (testMetrics != null) {
                println("[ARENA]   Tests:          ${testMetrics.testsRun} run, ${testMetrics.testsFail} fail, BUILD ${if (testMetrics.buildSuccess == true) "SUCCESS" else "FAILURE"}")
            }
            println("[ARENA]   Summary:        ${record.summary ?: "(none)"}")
            println("[ARENA] ════════════════════════════════════════")

            // Lenient assertion
            check(result.evaluation.agentExitedSuccessfully || result.evaluation.agentClaimedFix) {
                "Claude [claude+$modeLabel] neither exited successfully (exit=${result.agentResult.exitCode}) " +
                        "nor claimed a fix for ${testCase.instanceId}."
            }

            if (withMcp) {
                check(result.evaluation.usedMcpSteroid) {
                    "Claude [claude+mcp] did not use steroid_execute_code for ${testCase.instanceId}."
                }
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        private const val DEFAULT_INSTANCE_ID = "dpaia__jhipster__sample__app-3"

        private val dataset by lazy {
            println("[ARENA] Downloading dataset from $DATASET_URL ...")
            val cases = DpaiaDatasetLoader.loadFromUrl(DATASET_URL)
            println("[ARENA] Loaded ${cases.size} test cases")
            cases
        }

        private val resolvedTestCase: DpaiaTestCase by lazy {
            val id = System.getProperty("arena.test.instanceId") ?: DEFAULT_INSTANCE_ID
            DpaiaDatasetLoader.findById(dataset, id)
        }

        val results = CopyOnWriteArrayList<RunRecord>()

        @JvmStatic
        @AfterAll
        fun printComparisonTable() {
            if (results.isEmpty()) {
                println("[ARENA] No results to compare.")
                return
            }

            println()
            println("╔═══════════════════════════════════════════════════════════════════════════════════════╗")
            println("║                   JHIPSTER ARENA — CLAUDE COMPARISON                                 ║")
            println("╠═══════════════════════════════════════════════════════════════════════════════════════╣")

            for (r in results.sortedBy { !it.withMcp }) {
                val mode = if (r.withMcp) "claude+mcp " else "claude+none"
                println("║ $mode                                                                              ║")
                println("║   Fix: ${if (r.claimedFix) "YES" else "NO "}  Exit: ${(r.exitCode?.toString() ?: "?").padStart(3)}  " +
                        "Agent: ${(r.agentDurationMs / 1000).toString().padStart(4)}s  " +
                        "Prewarm: ${(r.prewarmMs / 1000).toString().padStart(4)}s                              ║")
                val t = r.tokenUsage
                if (t != null) {
                    println("║   Tokens: ${t.inputTokens}in/${t.outputTokens}out  " +
                            "Cache: ${t.cacheReadTokens}  " +
                            "Cost: $${String.format("%.2f", t.costUsd ?: 0.0)}  " +
                            "Turns: ${t.numTurns ?: "?"}".padEnd(56) + "║")
                }
                val m = r.testMetrics
                if (m != null) {
                    println("║   Tests: ${m.testsRun} run, ${m.testsPass} pass, ${m.testsFail} fail  " +
                            "BUILD ${if (m.buildSuccess == true) "SUCCESS" else "FAILURE"}".padEnd(49) + "║")
                }
                println("║   ${(r.summary ?: "(no summary)").take(72).padEnd(72)}      ║")
            }

            println("╚═══════════════════════════════════════════════════════════════════════════════════════╝")
            println()
        }

        private fun writeRunSummary(
            testCase: DpaiaTestCase,
            modeLabel: String,
            result: ArenaTestResult,
            record: RunRecord,
            runDir: File,
        ) {
            val summary = buildJsonObject {
                put("instance_id", testCase.instanceId)
                put("agent", "claude")
                put("mode", modeLabel)
                put("run_dir", runDir.absolutePath)
                put("exit_code", result.agentResult.exitCode ?: -1)
                put("agent_claimed_fix", record.claimedFix)
                put("used_mcp_steroid", record.usedMcpSteroid)
                put("agent_duration_ms", record.agentDurationMs)
                put("prewarm_ms", record.prewarmMs)
                record.tokenUsage?.let { t ->
                    put("input_tokens", t.inputTokens)
                    put("output_tokens", t.outputTokens)
                    put("cache_read_tokens", t.cacheReadTokens)
                    t.costUsd?.let { put("cost_usd", it) }
                    t.numTurns?.let { put("num_turns", it) }
                }
                record.testMetrics?.let { m ->
                    put("tests_run", m.testsRun)
                    put("tests_pass", m.testsPass)
                    put("tests_fail", m.testsFail)
                    m.buildSuccess?.let { put("build_success", it) }
                }
                put("agent_summary", record.summary ?: "")
                put("timestamp", java.time.Instant.now().toString())
            }
            val summaryFile = IdeTestFolders.testOutputDir
                .resolve("dpaia-jhipster-claude-$modeLabel.json")
            summaryFile.parentFile.mkdirs()
            summaryFile.writeText(summary.toString())
            println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")
        }
    }

    data class RunRecord(
        val instanceId: String,
        val withMcp: Boolean,
        val agentDurationMs: Long,
        val prewarmMs: Long,
        val exitCode: Int?,
        val claimedFix: Boolean,
        val usedMcpSteroid: Boolean,
        val summary: String?,
        val tokenUsage: TokenUsage?,
        val testMetrics: TestMetrics?,
    )
}
