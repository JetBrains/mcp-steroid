/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Abstract base class for dedicated DPAIA scenario tests — **Claude Code only**.
 *
 * Each subclass overrides [instanceId] to select a specific dpaia arena scenario.
 * Two test methods are inherited: "claude with mcp" and "claude without mcp".
 *
 * Each test method launches a **fresh Docker container** with IntelliJ IDEA.
 * Before the agent timer starts, the test runs a full prewarm:
 * 1. Maven/Gradle import + JDK setup (via [waitForProjectReady])
 * 2. Full project compile (compileProject = true)
 *
 * Only after the project is fully built does the agent timer start.
 *
 * @see DpaiaJhipsterArenaTest for the original concrete implementation this was factored from.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DpaiaScenarioBaseTest {

    /** The DPAIA instance ID for this scenario, e.g. "dpaia__jhipster__sample__app-3". */
    protected abstract val instanceId: String

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

        val consoleTitle = instanceId.take(40)

        val lifetime = CloseableStackHost()
        try {
            val aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE
            val mcpMode = if (withMcp) null else McpConnectionMode.None

            println("[ARENA] Creating container for [claude+$modeLabel] ${testCase.instanceId} ...")

            val buildSystem = when (testCase.buildSystem) {
                "maven" -> BuildSystem.MAVEN
                "gradle" -> BuildSystem.GRADLE
                else -> BuildSystem.NONE
            }

            val session = IntelliJContainer.create(
                lifetime,
                consoleTitle = "$consoleTitle-$modeLabel",
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
                buildSystem = buildSystem,
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
            val decodedLogMetrics = findDecodedLogFile(session.runDirInContainer)
                ?.let { extractDecodedLogMetrics(it.readText()) }

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
                decodedLogMetrics = decodedLogMetrics,
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
                println("[ARENA]   Cache create:   ${tokens.cacheCreationTokens}")
                println("[ARENA]   Cache read:     ${tokens.cacheReadTokens}")
                println("[ARENA]   Cost:           $${tokens.costUsd ?: "?"}")
                println("[ARENA]   Turns:          ${tokens.numTurns ?: "?"}")
                println("[ARENA]   API duration:   ${tokens.durationApiMs?.let { "${it / 1000}s" } ?: "?"}")
            }
            if (testMetrics != null) {
                println("[ARENA]   Tests:          ${testMetrics.testsRun} run, ${testMetrics.testsFail} fail, BUILD ${if (testMetrics.buildSuccess == true) "SUCCESS" else "FAILURE"}")
            }
            if (decodedLogMetrics != null) {
                println("[ARENA]   exec_code:      ${decodedLogMetrics.execCodeCalls}")
                println("[ARENA]   Read/Edit/Write: ${decodedLogMetrics.readCalls}/${decodedLogMetrics.editCalls}/${decodedLogMetrics.writeCalls}")
                println("[ARENA]   Glob/Grep/Bash: ${decodedLogMetrics.globCalls}/${decodedLogMetrics.grepCalls}/${decodedLogMetrics.bashCalls}")
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

    // ── Results + summary ────────────────────────────────────────────────────

    private val results = CopyOnWriteArrayList<RunRecord>()

    @AfterAll
    fun printComparisonTable() {
        if (results.isEmpty()) {
            println("[ARENA] No results to compare.")
            return
        }

        println()
        println("╔═══════════════════════════════════════════════════════════════════════════════════════╗")
        println("║              DPAIA ARENA — CLAUDE COMPARISON (${instanceId.take(37).padEnd(37)})  ║")
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
                        "Cache: ${t.cacheCreationTokens}c/${t.cacheReadTokens}r  " +
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
                put("cache_creation_tokens", t.cacheCreationTokens)
                t.costUsd?.let { put("cost_usd", it) }
                t.numTurns?.let { put("num_turns", it) }
                t.durationApiMs?.let { put("duration_api_ms", it) }
            }
            record.testMetrics?.let { m ->
                put("tests_run", m.testsRun)
                put("tests_pass", m.testsPass)
                put("tests_fail", m.testsFail)
                m.buildSuccess?.let { put("build_success", it) }
            }
            record.decodedLogMetrics?.let { d ->
                put("exec_code_calls", d.execCodeCalls)
                put("read_calls", d.readCalls)
                put("write_calls", d.writeCalls)
                put("edit_calls", d.editCalls)
                put("bash_calls", d.bashCalls)
                put("glob_calls", d.globCalls)
                put("grep_calls", d.grepCalls)
            }
            put("agent_summary", record.summary ?: "")
            put("timestamp", java.time.Instant.now().toString())
        }
        val summaryFile = IdeTestFolders.testOutputDir
            .resolve("dpaia-arena-run-${testCase.instanceId}-claude-$modeLabel.json")
        summaryFile.parentFile.mkdirs()
        summaryFile.writeText(summary.toString())
        println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")

        // Append to comparison CSV
        val passLabel = System.getProperty("arena.pass.label", "")
        val csvFile = IdeTestFolders.testOutputDir.resolve("arena-comparison.csv")
        appendComparisonCsv(
            csvFile = csvFile,
            instanceId = testCase.instanceId,
            passLabel = passLabel,
            claimedFix = record.claimedFix,
            durationS = record.agentDurationMs / 1000,
            tokens = record.tokenUsage,
            testMetrics = record.testMetrics,
            decoded = record.decodedLogMetrics,
        )
        println("[ARENA] Comparison CSV appended to: ${csvFile.absolutePath}")
    }

    // ── Dataset loading ──────────────────────────────────────────────────────

    private val resolvedTestCase: DpaiaTestCase by lazy {
        DpaiaDatasetLoader.findById(dataset, instanceId)
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
        val decodedLogMetrics: DecodedLogMetrics? = null,
    )

    companion object {
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        val dataset: List<DpaiaTestCase> by lazy {
            println("[ARENA] Downloading dataset from $DATASET_URL ...")
            val cases = DpaiaDatasetLoader.loadFromUrl(DATASET_URL)
            println("[ARENA] Loaded ${cases.size} test cases")
            cases
        }
    }
}
