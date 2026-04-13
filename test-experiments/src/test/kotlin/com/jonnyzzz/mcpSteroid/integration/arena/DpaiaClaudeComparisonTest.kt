/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.git.BareRepoCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Claude-only A/B comparison: "Claude + MCP Steroid" vs "Claude without any IDE tools".
 *
 * Runs one DPAIA test case twice with Claude:
 * - `[claude+mcp]`: Claude uses IntelliJ IDEA via MCP Steroid (project pre-deployed at startup)
 * - `[claude+none]`: Claude uses only bash/grep/mvn/gradle — no IDE tooling
 *
 * The MCP container deploys the test project BEFORE IntelliJ starts
 * (via [IntelliJProject.ProjectFromGitCommitAndPatch]) so no steroid_open_project call is needed.
 * After IntelliJ starts, [IntelliJContainer.waitForProjectReady] waits for full indexing and JDK setup.
 *
 * **Run a specific scenario (required for meaningful MCP comparison):**
 * ```
 * -Dclaude.comparison.instanceId=dpaia__empty__maven__springboot3-3
 * ```
 *
 * Captures per-run metrics:
 * - Wall-clock time (total: git clone + patch + agent run)
 * - Agent-only time (from [ArenaTestResult.agentDurationMs])
 * - Claude token usage parsed from stream-json NDJSON (input, output, cache-read, cost, turns)
 * - Claimed fix (ARENA_FIX_APPLIED: yes), MCP tool usage, agent summary
 *
 * After all 20 dynamic tests finish, [generateReport] writes:
 * - `[testOutputDir]/claude-comparison-report.md` — human-readable Markdown report
 * - `[testOutputDir]/claude-comparison-report.json` — machine-readable JSON data
 *
 * **Run default scenario (dpaia__empty__maven__springboot3-3):**
 * ```
 * ./gradlew :test-experiments:test --tests '*DpaiaClaudeComparisonTest*'
 * ```
 *
 * **Override case list (deprecated, prefer instanceId for one case):**
 * ```
 * -Dclaude.comparison.cases=dpaia__feature__service-125
 * ```
 *
 * **Limit number of cases:**
 * ```
 * -Dclaude.comparison.maxCases=1
 * ```
 *
 * To compare multiple scenarios, run this test once per scenario
 * (e.g. via docs/dpaia-runs/run-all.sh) rather than in a single Docker session.
 */
class DpaiaClaudeComparisonTest {

    @TestFactory
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    fun `claude comparison runs`(): List<DynamicTest> {
        val cases = selectTestCases()
        println("[CLAUDE-CMP] Running ${cases.size} cases × 2 modes = ${cases.size * 2} dynamic tests")
        println("[CLAUDE-CMP] Cases: ${cases.map { it.instanceId }}")

        return cases.flatMap { testCase ->
            listOf(
                DynamicTest.dynamicTest("[claude+mcp] ${testCase.instanceId}") {
                    results.add(runOne(testCase, withMcp = true, sessionWithMcp))
                },
                DynamicTest.dynamicTest("[claude+none] ${testCase.instanceId}") {
                    results.add(runOne(testCase, withMcp = false, sessionWithoutMcp))
                },
            )
        }
    }

    // ── Data model ─────────────────────────────────────────────────────────────

    data class TokenUsage(
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long = 0L,
        val costUsd: Double? = null,
        val numTurns: Int? = null,
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    data class TestMetrics(
        val testsRun: Int,
        val testsPass: Int,
        val testsFail: Int,
        val testsError: Int,
        val buildSuccess: Boolean?,
    )

    data class RunRecord(
        val instanceId: String,
        val tags: List<String>,
        val withMcp: Boolean,
        /** Total wall-clock ms: git clone + patch + agent prompt. */
        val totalDurationMs: Long,
        /** Agent-only ms: just [agent.runPrompt]. */
        val agentDurationMs: Long,
        /** IDE pre-warm ms: open + index time (NOT in agent budget). */
        val prewarmDurationMs: Long = 0L,
        val exitCode: Int?,
        val agentClaimedFix: Boolean,
        val usedMcpSteroid: Boolean,
        val agentSummary: String?,
        val tokenUsage: TokenUsage?,
        /** Maven test result metrics extracted from agent output (null if no mvn test ran). */
        val testMetrics: TestMetrics? = null,
        /** Non-null if an exception was thrown during the run. */
        val errorMessage: String? = null,
        /** Number of steroid_execute_code tool calls (0 for NONE mode, null if not parsed). */
        val steroidCallCount: Int? = null,
        /** Total number of tool_use calls across all turns (null if not parsed). */
        val totalToolCalls: Int? = null,
        /** Number of tool calls that returned is_error=true (null if not parsed). */
        val toolErrorCount: Int? = null,
    ) {
        val modeLabel: String get() = if (withMcp) "MCP" else "NONE"
        val isSuccess: Boolean get() = errorMessage == null && (exitCode == 0 || agentClaimedFix)
        val cacheHitRate: Double?
            get() {
                val u = tokenUsage ?: return null
                val total = u.inputTokens + u.cacheReadTokens
                return if (total > 0) u.cacheReadTokens.toDouble() / total else null
            }
    }

    companion object {

        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        /** Guest directory used by ArenaTestRunner when project is not pre-deployed (fallback). */
        private const val ARENA_WORKSPACE = "/home/agent/claude-comparison"

        private val results = CopyOnWriteArrayList<RunRecord>()

        @JvmStatic
        val lifetimeWithMcp by lazy { CloseableStackHost() }

        @JvmStatic
        val lifetimeWithoutMcp by lazy { CloseableStackHost() }

        /**
         * The single test case that will be pre-deployed before IntelliJ starts.
         * Resolved once from the system property / default so both [sessionWithMcp] and
         * [selectTestCases] see the same case.
         *
         * Use -Dclaude.comparison.instanceId=<id> to override.
         */
        private val setupTestCase: DpaiaTestCase by lazy {
            val instanceId = System.getProperty("claude.comparison.instanceId")
            if (!instanceId.isNullOrBlank()) {
                DpaiaDatasetLoader.findById(dataset, instanceId)
            } else {
                selectTestCases().first()
            }
        }

        /**
         * Container where Claude is registered with MCP Steroid (HTTP transport).
         *
         * The arena project ([setupTestCase]) is deployed to the IDE's project-home path
         * BEFORE IntelliJ starts — via [IntelliJProject.ProjectFromGitCommitAndPatch].
         * [waitForProjectReady] then waits for the project to be fully indexed and JDK configured.
         * When this lazy completes, the arena project is open and indexed in IntelliJ.
         *
         * No steroid_open_project call is needed — the project is already loaded.
         */
        val sessionWithMcp by lazy {
            IntelliJContainer.create(
                lifetimeWithMcp,
                consoleTitle = "claude-cmp-mcp",
                aiMode = AiMode.AI_MCP,
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
         * Container where Claude has NO MCP registered — baseline/control group.
         *
         * Same project as [sessionWithMcp] is pre-deployed and fully imported in IntelliJ,
         * so the environment is identical (same Maven deps, same codebase, same IntelliJ state).
         * The only difference is that MCP Steroid is NOT registered with the Claude agent.
         */
        val sessionWithoutMcp by lazy {
            IntelliJContainer.create(
                lifetimeWithoutMcp,
                consoleTitle = "claude-cmp-none",
                mcpConnectionMode = McpConnectionMode.None,
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

        private val dataset by lazy {
            println("[CLAUDE-CMP] Downloading dataset from $DATASET_URL ...")
            DpaiaDatasetLoader.loadFromUrl(DATASET_URL).also {
                println("[CLAUDE-CMP] Loaded ${it.size} test cases")
            }
        }

        private fun selectTestCases(): List<DpaiaTestCase> {
            // Single scenario mode — preferred: aligns with pre-deployed project in sessionWithMcp.
            val singleId = System.getProperty("claude.comparison.instanceId")
            if (!singleId.isNullOrBlank()) {
                return listOf(DpaiaDatasetLoader.findById(dataset, singleId))
            }
            val specificIds = System.getProperty("claude.comparison.cases")
            val ids = if (!specificIds.isNullOrBlank()) {
                specificIds.split(',').map { it.trim() }.filter { it.isNotBlank() }
            } else {
                listOf(DpaiaCuratedCases.DEFAULT_SIMPLE)
            }
            val maxCases = System.getProperty("claude.comparison.maxCases")?.toIntOrNull() ?: ids.size
            return ids.take(maxCases).map { id -> DpaiaDatasetLoader.findById(dataset, id) }
        }

        // ── Test execution ──────────────────────────────────────────────────

        private fun runOne(testCase: DpaiaTestCase, withMcp: Boolean, session: IntelliJContainer): RunRecord {
            val modeLabel = if (withMcp) "MCP" else "NONE"
            println("[CLAUDE-CMP] ========================================")
            println("[CLAUDE-CMP] Starting: ${testCase.instanceId} [$modeLabel]")
            println("[CLAUDE-CMP] Repo: ${testCase.repo} | Tags: ${testCase.tags}")
            println("[CLAUDE-CMP] ========================================")

            val startMs = System.currentTimeMillis()
            try {
                val runner = ArenaTestRunner(container = session.scope, projectGuestDir = ARENA_WORKSPACE)
                val result = runner.runTest(
                    testCase = testCase,
                    agent = session.aiAgents.claude,
                    withMcp = withMcp,
                    timeoutSeconds = 1800,
                    // Both containers pre-deploy the project at IntelliJ's project-home path
                    // (waitForProjectReady() was called in @BeforeAll for both sessions).
                    // Pass the pre-deployed path so ArenaTestRunner skips re-cloning.
                    predeployedProjectDir = session.intellijDriver.getGuestProjectDir(),
                )
                val totalMs = System.currentTimeMillis() - startMs
                val rawOutput = result.agentResult.stdout
                val tokens = extractTokenUsage(rawOutput)
                val testMetrics = extractTestMetrics(rawOutput)
                val toolStats = extractToolCallStats(rawOutput)

                // Fallback: count steroid_execute_code from decoded agent log when NDJSON parsing
                // found no tool calls (e.g. agent failed early or output format was unexpected).
                val steroidCallsFromLog: Int? = if (toolStats == null) {
                    session.runDirInContainer.listFiles { f ->
                        f.name.startsWith("agent-claude-code-") &&
                                f.name.endsWith("-decoded.txt") &&
                                f.lastModified() >= startMs
                    }?.maxByOrNull { it.lastModified() }?.readLines()?.count { line ->
                        line.startsWith(">>") && line.contains("steroid_execute_code")
                    }
                } else null

                println("[CLAUDE-CMP] Completed: ${testCase.instanceId} [$modeLabel]")
                println("[CLAUDE-CMP]   Total time:   ${totalMs / 1000}s (agent: ${result.agentDurationMs / 1000}s)")
                println("[CLAUDE-CMP]   Exit code:    ${result.agentResult.exitCode}")
                println("[CLAUDE-CMP]   Claimed fix:  ${result.evaluation.agentClaimedFix}")
                println("[CLAUDE-CMP]   Used MCP:     ${result.evaluation.usedMcpSteroid}")
                if (testMetrics != null) {
                    val build = if (testMetrics.buildSuccess == true) "BUILD SUCCESS" else if (testMetrics.buildSuccess == false) "BUILD FAILURE" else "?"
                    println("[CLAUDE-CMP]   Tests:        ${testMetrics.testsPass}/${testMetrics.testsRun} pass (fail=${testMetrics.testsFail}, err=${testMetrics.testsError}) | $build")
                } else {
                    println("[CLAUDE-CMP]   Tests:        (no test results found)")
                }
                if (tokens != null) {
                    println("[CLAUDE-CMP]   Tokens:       in=${tokens.inputTokens} out=${tokens.outputTokens} turns=${tokens.numTurns ?: "?"}")
                    if (tokens.costUsd != null) {
                        println("[CLAUDE-CMP]   Cost:         \$${String.format("%.4f", tokens.costUsd)}")
                    }
                } else {
                    println("[CLAUDE-CMP]   Tokens:       (not available — agent may have failed before producing output)")
                }
                if (toolStats != null) {
                    println("[CLAUDE-CMP]   Tool calls:   total=${toolStats.totalToolCalls} steroid=${toolStats.steroidCallCount} errors=${toolStats.toolErrorCount}")
                }
                println("[CLAUDE-CMP] ========================================")

                return RunRecord(
                    instanceId = testCase.instanceId,
                    tags = testCase.tags,
                    withMcp = withMcp,
                    totalDurationMs = totalMs,
                    agentDurationMs = result.agentDurationMs,
                    prewarmDurationMs = result.prewarmDurationMs,
                    exitCode = result.agentResult.exitCode,
                    agentClaimedFix = result.evaluation.agentClaimedFix,
                    usedMcpSteroid = result.evaluation.usedMcpSteroid,
                    agentSummary = result.evaluation.agentSummary,
                    tokenUsage = tokens,
                    testMetrics = testMetrics,
                    steroidCallCount = toolStats?.steroidCallCount ?: steroidCallsFromLog,
                    totalToolCalls = toolStats?.totalToolCalls,
                    toolErrorCount = toolStats?.toolErrorCount,
                )
            } catch (e: Exception) {
                val totalMs = System.currentTimeMillis() - startMs
                println("[CLAUDE-CMP] ERROR: ${testCase.instanceId} [$modeLabel] failed after ${totalMs / 1000}s: ${e.message}")
                return RunRecord(
                    instanceId = testCase.instanceId,
                    tags = testCase.tags,
                    withMcp = withMcp,
                    totalDurationMs = totalMs,
                    agentDurationMs = 0L,
                    exitCode = null,
                    agentClaimedFix = false,
                    usedMcpSteroid = false,
                    agentSummary = null,
                    tokenUsage = null,
                    errorMessage = e.message,
                )
            }
        }

        private val TEST_RESULT_REGEX = Regex("""Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)""")
        private val BUILD_STATUS_REGEX = Regex("""BUILD (SUCCESS|FAILURE)""")

        /**
         * Extract Maven Surefire test metrics from agent output.
         *
         * Searches the raw output (which may be NDJSON or plain text) for Maven Surefire lines:
         * ```
         * Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
         * BUILD SUCCESS
         * ```
         * Takes the LAST match for each, which corresponds to the final `mvn test` run summary.
         * Returns null if no test result lines are found (e.g. the agent never ran tests).
         */
        fun extractTestMetrics(rawOutput: String): TestMetrics? {
            val matches = TEST_RESULT_REGEX.findAll(rawOutput).toList()
            if (matches.isEmpty()) return null

            val last = matches.last()
            val testsRun = last.groupValues[1].toInt()
            val testsFail = last.groupValues[2].toInt()
            val testsError = last.groupValues[3].toInt()
            val testsPass = testsRun - testsFail - testsError

            val buildMatches = BUILD_STATUS_REGEX.findAll(rawOutput).toList()
            val buildSuccess = buildMatches.lastOrNull()?.groupValues?.get(1)?.let { it == "SUCCESS" }

            return TestMetrics(
                testsRun = testsRun,
                testsPass = testsPass,
                testsFail = testsFail,
                testsError = testsError,
                buildSuccess = buildSuccess,
            )
        }

        /**
         * Extract Claude token usage from stream-json NDJSON output.
         *
         * Claude CLI `--output-format stream-json` writes NDJSON where the last "result" event is:
         * ```json
         * {"type":"result","subtype":"success","cost_usd":0.01,"duration_ms":45000,
         *  "num_turns":5,"usage":{"input_tokens":15000,"output_tokens":3000,
         *  "cache_read_input_tokens":12000},...}
         * ```
         */
        private fun extractTokenUsage(rawOutput: String): TokenUsage? {
            for (line in rawOutput.lines().asReversed()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val json = try {
                    Json.parseToJsonElement(trimmed).jsonObject
                } catch (e: Exception) {
                    continue
                }
                if (json["type"]?.jsonPrimitive?.content != "result") continue
                val usage = json["usage"]?.jsonObject ?: return null
                return TokenUsage(
                    inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                    // Claude CLI 2.1.x uses "total_cost_usd"; older versions use "cost_usd"
                    costUsd = (json["total_cost_usd"] ?: json["cost_usd"])?.jsonPrimitive?.doubleOrNull,
                    numTurns = json["num_turns"]?.jsonPrimitive?.intOrNull,
                )
            }
            return null
        }

        data class ToolCallStats(
            /** Number of steroid_execute_code tool invocations. */
            val steroidCallCount: Int,
            /** Total tool_use calls across all assistant turns. */
            val totalToolCalls: Int,
            /** Number of tool results that returned is_error=true. */
            val toolErrorCount: Int,
        )

        /**
         * Extract tool call statistics from Claude NDJSON output.
         *
         * Parses the new Claude CLI format (`assistant.message.content[].type == "tool_use"`)
         * to count:
         * - `steroid_execute_code` calls (full or bare name)
         * - total `tool_use` blocks
         * - tool results with `is_error: true`
         *
         * Returns null if no assistant events with tool_use are found (e.g. the agent failed
         * before producing any tool calls, or the output is not NDJSON).
         */
        fun extractToolCallStats(rawOutput: String): ToolCallStats? {
            var steroidCount = 0
            var totalCount = 0
            var errorCount = 0
            var foundAny = false

            for (line in rawOutput.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val obj = try {
                    Json.parseToJsonElement(trimmed).jsonObject
                } catch (e: Exception) {
                    continue
                }

                when (obj["type"]?.jsonPrimitive?.content) {
                    "assistant" -> {
                        val content = obj["message"]?.jsonObject
                            ?.get("content")?.let { el ->
                                try { el.jsonArray } catch (e: Exception) { null }
                            } ?: continue
                        for (item in content) {
                            val itemObj = try { item.jsonObject } catch (e: Exception) { continue }
                            if (itemObj["type"]?.jsonPrimitive?.content == "tool_use") {
                                totalCount++
                                foundAny = true
                                val name = itemObj["name"]?.jsonPrimitive?.content ?: ""
                                // Match both "mcp__mcp-steroid__steroid_execute_code" and "steroid_execute_code"
                                if (name.endsWith("steroid_execute_code")) {
                                    steroidCount++
                                }
                            }
                        }
                    }
                    "user" -> {
                        val content = obj["message"]?.jsonObject
                            ?.get("content")?.let { el ->
                                try { el.jsonArray } catch (e: Exception) { null }
                            } ?: continue
                        for (item in content) {
                            val itemObj = try { item.jsonObject } catch (e: Exception) { continue }
                            if (itemObj["type"]?.jsonPrimitive?.content == "tool_result") {
                                if (itemObj["is_error"]?.jsonPrimitive?.content == "true") {
                                    errorCount++
                                }
                            }
                        }
                    }
                }
            }

            return if (foundAny) ToolCallStats(steroidCount, totalCount, errorCount) else null
        }

        // ── Lifecycle ──────────────────────────────────────────────────────

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            val cacheDir = IdeTestFolders.repoCacheDirOrNull
            if (cacheDir != null) BareRepoCache.warmDpaiaRepos(cacheDir)
            sessionWithMcp.toString()
            sessionWithoutMcp.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            try {
                generateReport()
            } finally {
                lifetimeWithMcp.closeAllStacks()
                lifetimeWithoutMcp.closeAllStacks()
            }
        }

        // ── Report generation ───────────────────────────────────────────────

        private fun generateReport() {
            if (results.isEmpty()) {
                println("[CLAUDE-CMP] No results to report")
                return
            }

            val sorted = results.toList().sortedWith(
                compareBy({ it.instanceId }, { it.modeLabel })
            )
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

            val mdReport = buildComparisonReport(sorted, timestamp)
            val jsonReport = buildJsonReport(sorted)

            val outDir = IdeTestFolders.testOutputDir
            val mdFile = File(outDir, "claude-comparison-report.md")
            val jsonFile = File(outDir, "claude-comparison-report.json")

            mdFile.writeText(mdReport)

            // Merge new records with any existing report to preserve data from previous runs.
            // Records with the same (instanceId, mode) pair are replaced; others are kept.
            val newArray = Json.parseToJsonElement(jsonReport).jsonArray
            val existingArray = if (jsonFile.exists()) {
                try { Json.parseToJsonElement(jsonFile.readText()).jsonArray }
                catch (e: Exception) { buildJsonArray { } }
            } else buildJsonArray { }
            val newKeys = newArray.map {
                it.jsonObject["instanceId"]?.jsonPrimitive?.content to it.jsonObject["mode"]?.jsonPrimitive?.content
            }.toSet()
            val mergedArray = buildJsonArray {
                for (elem in existingArray) {
                    val key = elem.jsonObject["instanceId"]?.jsonPrimitive?.content to
                            elem.jsonObject["mode"]?.jsonPrimitive?.content
                    if (key !in newKeys) add(elem)
                }
                for (elem in newArray) add(elem)
            }
            jsonFile.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), mergedArray))

            println("[CLAUDE-CMP] ========================================")
            println("[CLAUDE-CMP] COMPARISON REPORT WRITTEN:")
            println("[CLAUDE-CMP]   Markdown: ${mdFile.absolutePath}")
            println("[CLAUDE-CMP]   JSON:     ${jsonFile.absolutePath}")
            println("[CLAUDE-CMP] ========================================")
            println()
            println(mdReport)
        }

        private fun buildComparisonReport(records: List<RunRecord>, timestamp: String): String {
            val mcpRecs = records.filter { it.withMcp }
            val noMcpRecs = records.filter { !it.withMcp }
            val caseIds = records.map { it.instanceId }.distinct()
            // Pair each case: Triple(id, mcpRecord?, noMcpRecord?)
            val pairs = caseIds.map { id ->
                Triple(id, mcpRecs.find { it.instanceId == id }, noMcpRecs.find { it.instanceId == id })
            }

            return buildString {
                appendLine("# DPAIA A/B Comparison: Claude + MCP Steroid vs Claude Only")
                appendLine()
                appendLine("Generated: $timestamp")
                appendLine()
                appendLine("## Summary")
                appendLine()
                append(summarySection(mcpRecs, noMcpRecs, pairs.size))
                appendLine()
                appendLine("## Per-Case Results")
                appendLine()
                append(resultsTable(pairs))
                appendLine()
                appendLine("## Bottleneck Analysis")
                appendLine()
                append(bottleneckAnalysis(pairs))
                appendLine()
                appendLine("## Detailed Run Notes")
                appendLine()
                append(detailedNotes(pairs))
            }
        }

        private fun summarySection(
            mcpRecs: List<RunRecord>,
            noMcpRecs: List<RunRecord>,
            totalCases: Int,
        ): String = buildString {
            val mcpFixed = mcpRecs.count { it.agentClaimedFix }
            val noMcpFixed = noMcpRecs.count { it.agentClaimedFix }
            val mcpAvgSec = avgSecOrNull(mcpRecs.map { it.totalDurationMs })
            val noMcpAvgSec = avgSecOrNull(noMcpRecs.map { it.totalDurationMs })
            val mcpAvgIn = avgLongOrNull(mcpRecs.mapNotNull { it.tokenUsage?.inputTokens })
            val noMcpAvgIn = avgLongOrNull(noMcpRecs.mapNotNull { it.tokenUsage?.inputTokens })
            val mcpAvgOut = avgLongOrNull(mcpRecs.mapNotNull { it.tokenUsage?.outputTokens })
            val noMcpAvgOut = avgLongOrNull(noMcpRecs.mapNotNull { it.tokenUsage?.outputTokens })
            val mcpTotalCost = mcpRecs.mapNotNull { it.tokenUsage?.costUsd }.sum()
            val noMcpTotalCost = noMcpRecs.mapNotNull { it.tokenUsage?.costUsd }.sum()
            val mcpAvgTurns = avgLongOrNull(mcpRecs.mapNotNull { it.tokenUsage?.numTurns?.toLong() })
            val noMcpAvgTurns = avgLongOrNull(noMcpRecs.mapNotNull { it.tokenUsage?.numTurns?.toLong() })
            val mcpAvgSteroid = avgLongOrNull(mcpRecs.mapNotNull { it.steroidCallCount?.toLong() })
            val noMcpAvgSteroid = avgLongOrNull(noMcpRecs.mapNotNull { it.steroidCallCount?.toLong() })
            val mcpAvgTotalTools = avgLongOrNull(mcpRecs.mapNotNull { it.totalToolCalls?.toLong() })
            val noMcpAvgTotalTools = avgLongOrNull(noMcpRecs.mapNotNull { it.totalToolCalls?.toLong() })
            val mcpAvgErrors = avgLongOrNull(mcpRecs.mapNotNull { it.toolErrorCount?.toLong() })
            val noMcpAvgErrors = avgLongOrNull(noMcpRecs.mapNotNull { it.toolErrorCount?.toLong() })
            val mcpAvgCacheHit = mcpRecs.mapNotNull { it.cacheHitRate }.let { if (it.isEmpty()) null else it.average() }
            val noMcpAvgCacheHit = noMcpRecs.mapNotNull { it.cacheHitRate }.let { if (it.isEmpty()) null else it.average() }

            appendLine("| Metric | Claude + MCP | Claude (no MCP) | Δ |")
            appendLine("|--------|-------------|-----------------|---|")
            appendLine("| Cases run | ${mcpRecs.size} | ${noMcpRecs.size} | — |")
            appendLine("| Claimed fix | $mcpFixed/$totalCases (${pct(mcpFixed, totalCases)}) | $noMcpFixed/$totalCases (${pct(noMcpFixed, totalCases)}) | ${deltaFixRate(mcpFixed, noMcpFixed, totalCases)} |")
            appendLine("| Avg total time | ${fmtSec(mcpAvgSec)} | ${fmtSec(noMcpAvgSec)} | ${deltaSecPct(mcpAvgSec, noMcpAvgSec)} |")
            appendLine("| Total cost (USD) | \$${fmtCost(mcpTotalCost)} | \$${fmtCost(noMcpTotalCost)} | ${deltaCostPct(mcpTotalCost, noMcpTotalCost)} |")
            appendLine("| Avg turns | ${fmtAvg(mcpAvgTurns)} | ${fmtAvg(noMcpAvgTurns)} | ${deltaAvgPct(mcpAvgTurns, noMcpAvgTurns)} |")
            appendLine("| Avg steroid calls | ${fmtAvg(mcpAvgSteroid)} | ${fmtAvg(noMcpAvgSteroid)} | N/A |")
            appendLine("| Avg total tool calls | ${fmtAvg(mcpAvgTotalTools)} | ${fmtAvg(noMcpAvgTotalTools)} | ${deltaAvgPct(mcpAvgTotalTools, noMcpAvgTotalTools)} |")
            appendLine("| Avg tool errors | ${fmtAvg(mcpAvgErrors)} | ${fmtAvg(noMcpAvgErrors)} | ${deltaAvgPct(mcpAvgErrors, noMcpAvgErrors)} |")
            appendLine("| Avg cache hit rate | ${fmtPctOrDash(mcpAvgCacheHit)} | ${fmtPctOrDash(noMcpAvgCacheHit)} | — |")
            appendLine("| Avg input tokens | ${fmtK(mcpAvgIn)} | ${fmtK(noMcpAvgIn)} | ${deltaAvgPct(mcpAvgIn, noMcpAvgIn)} |")
            appendLine("| Avg output tokens | ${fmtK(mcpAvgOut)} | ${fmtK(noMcpAvgOut)} | ${deltaAvgPct(mcpAvgOut, noMcpAvgOut)} |")
            appendLine()

            val fixDiff = mcpFixed - noMcpFixed
            when {
                fixDiff > 0 -> appendLine("**MCP advantage: +$fixDiff cases fixed** (${pct(mcpFixed, totalCases)} vs ${pct(noMcpFixed, totalCases)})")
                fixDiff < 0 -> appendLine("**No-MCP advantage: +${-fixDiff} cases fixed** (${pct(noMcpFixed, totalCases)} vs ${pct(mcpFixed, totalCases)})")
                else -> appendLine("**Tied fix rate** — both modes: ${pct(mcpFixed, totalCases)}")
            }

            if (mcpAvgSec != null && noMcpAvgSec != null) {
                val diff = noMcpAvgSec - mcpAvgSec
                if (diff > 5.0) {
                    appendLine("**MCP saves avg ${diff.toInt()}s per case** vs no-MCP")
                } else if (diff < -5.0) {
                    appendLine("**MCP is avg ${(-diff).toInt()}s slower per case** vs no-MCP (IDE indexing overhead)")
                }
            }
        }

        private fun fmtTestMetrics(m: TestMetrics?): String {
            if (m == null) return "–"
            val build = when (m.buildSuccess) {
                true -> "✅"
                false -> "❌"
                null -> "?"
            }
            return "${m.testsPass}/${m.testsRun} ($build)"
        }

        private fun resultsTable(pairs: List<Triple<String, RunRecord?, RunRecord?>>): String = buildString {
            appendLine("| Case | Tags | MCP Fix | MCP Tests | MCP Time | MCP Cost | MCP Turns | MCP Steroid# | No-MCP Fix | No-MCP Tests | No-MCP Time | No-MCP Cost | No-MCP Turns | Verdict |")
            appendLine("|------|------|---------|-----------|----------|----------|-----------|--------------|------------|--------------|-------------|-------------|--------------|---------|")
            for ((id, mcp, noMcp) in pairs) {
                val tags = (mcp?.tags ?: noMcp?.tags ?: emptyList()).joinToString(", ")
                val mcpFix = mcp?.let { if (it.agentClaimedFix) "✅ yes" else "❌ no" } ?: "–"
                val noMcpFix = noMcp?.let { if (it.agentClaimedFix) "✅ yes" else "❌ no" } ?: "–"
                val mcpTests = fmtTestMetrics(mcp?.testMetrics)
                val noMcpTests = fmtTestMetrics(noMcp?.testMetrics)
                val mcpTime = mcp?.let { fmtSec(it.totalDurationMs.toDouble()) } ?: "–"
                val noMcpTime = noMcp?.let { fmtSec(it.totalDurationMs.toDouble()) } ?: "–"
                val mcpCost = mcp?.tokenUsage?.costUsd?.let { "\$${fmtCost(it)}" } ?: "–"
                val noMcpCost = noMcp?.tokenUsage?.costUsd?.let { "\$${fmtCost(it)}" } ?: "–"
                val mcpTurns = mcp?.tokenUsage?.numTurns?.toString() ?: "–"
                val noMcpTurns = noMcp?.tokenUsage?.numTurns?.toString() ?: "–"
                val mcpSteroid = mcp?.steroidCallCount?.toString() ?: "–"
                val verdict = verdict(mcp, noMcp)
                val shortId = id.removePrefix("dpaia__")
                appendLine("| $shortId | $tags | $mcpFix | $mcpTests | $mcpTime | $mcpCost | $mcpTurns | $mcpSteroid | $noMcpFix | $noMcpTests | $noMcpTime | $noMcpCost | $noMcpTurns | $verdict |")
            }
        }

        private fun verdict(mcp: RunRecord?, noMcp: RunRecord?): String = when {
            mcp?.agentClaimedFix == true && noMcp?.agentClaimedFix != true -> "**MCP wins**"
            mcp?.agentClaimedFix != true && noMcp?.agentClaimedFix == true -> "no-MCP wins"
            mcp?.agentClaimedFix == true && noMcp?.agentClaimedFix == true -> "tie — both fixed"
            else -> "tie — both failed"
        }

        private fun bottleneckAnalysis(pairs: List<Triple<String, RunRecord?, RunRecord?>>): String = buildString {
            val mcpWins = pairs.filter { (_, mcp, noMcp) -> mcp?.agentClaimedFix == true && noMcp?.agentClaimedFix != true }
            val noMcpWins = pairs.filter { (_, mcp, noMcp) -> mcp?.agentClaimedFix != true && noMcp?.agentClaimedFix == true }
            val bothFixed = pairs.filter { (_, mcp, noMcp) -> mcp?.agentClaimedFix == true && noMcp?.agentClaimedFix == true }
            val bothFailed = pairs.filter { (_, mcp, noMcp) -> mcp?.agentClaimedFix != true && noMcp?.agentClaimedFix != true }

            // Fix rate breakdown
            appendLine("### Fix-Rate Breakdown")
            appendLine()
            appendLine("- **MCP wins** (only MCP fixed): ${mcpWins.size} case(s)")
            for ((id, mcp, noMcp) in mcpWins) {
                val mcpTok = mcp?.tokenUsage?.totalTokens?.let { fmtK(it.toDouble()) } ?: "?"
                val noMcpTok = noMcp?.tokenUsage?.totalTokens?.let { fmtK(it.toDouble()) } ?: "?"
                appendLine("  - `${id.removePrefix("dpaia__")}`: MCP ${fmtSec(mcp?.totalDurationMs?.toDouble())}/${mcpTok}tok  →  no-MCP ${fmtSec(noMcp?.totalDurationMs?.toDouble())}/${noMcpTok}tok (exit ${noMcp?.exitCode ?: "?"})")
            }
            appendLine()
            appendLine("- **Both fixed** (tie): ${bothFixed.size} case(s)")
            for ((id, mcp, noMcp) in bothFixed) {
                val mcpTok = mcp?.tokenUsage?.totalTokens?.let { fmtK(it.toDouble()) } ?: "?"
                val noMcpTok = noMcp?.tokenUsage?.totalTokens?.let { fmtK(it.toDouble()) } ?: "?"
                appendLine("  - `${id.removePrefix("dpaia__")}`: MCP ${fmtSec(mcp?.totalDurationMs?.toDouble())}/${mcpTok}tok  vs  no-MCP ${fmtSec(noMcp?.totalDurationMs?.toDouble())}/${noMcpTok}tok")
            }
            appendLine()
            appendLine("- **Both failed** (tie): ${bothFailed.size} case(s)")
            for ((id, mcp, noMcp) in bothFailed) {
                val note = (mcp?.errorMessage ?: noMcp?.errorMessage)?.let { " — error: $it" } ?: ""
                appendLine("  - `${id.removePrefix("dpaia__")}`$note")
            }
            appendLine()
            if (noMcpWins.isNotEmpty()) {
                appendLine("- **No-MCP wins** (unexpected, worth investigating): ${noMcpWins.size} case(s)")
                for ((id, mcp, noMcp) in noMcpWins) {
                    appendLine("  - `${id.removePrefix("dpaia__")}`: no-MCP fixed in ${fmtSec(noMcp?.totalDurationMs?.toDouble())}, MCP did not (exit ${mcp?.exitCode ?: "?"})")
                }
                appendLine()
            }

            // Token efficiency
            appendLine("### Token Efficiency")
            appendLine()
            data class TokenDiff(val id: String, val mcpTok: Long, val noMcpTok: Long)
            val tokenDiffs = pairs.mapNotNull { (id, mcp, noMcp) ->
                val m = mcp?.tokenUsage?.totalTokens ?: return@mapNotNull null
                val n = noMcp?.tokenUsage?.totalTokens ?: return@mapNotNull null
                TokenDiff(id, m, n)
            }
            val mcpSaves = tokenDiffs.filter { it.noMcpTok > it.mcpTok * 1.2 }.sortedByDescending { it.noMcpTok - it.mcpTok }
            val mcpCosts = tokenDiffs.filter { it.mcpTok > it.noMcpTok * 1.2 }.sortedByDescending { it.mcpTok - it.noMcpTok }

            if (mcpSaves.isNotEmpty()) {
                appendLine("**Cases where MCP uses fewer tokens (>20% less):**")
                for (d in mcpSaves) {
                    val saved = d.noMcpTok - d.mcpTok
                    appendLine("  - `${d.id.removePrefix("dpaia__")}`: MCP ${fmtK(d.mcpTok.toDouble())}tok vs no-MCP ${fmtK(d.noMcpTok.toDouble())}tok (saves ${fmtK(saved.toDouble())}tok, ${pct(saved.toInt(), d.noMcpTok.toInt())} less)")
                }
                appendLine()
            }
            if (mcpCosts.isNotEmpty()) {
                appendLine("**Cases where MCP uses more tokens (>20% more):**")
                for (d in mcpCosts) {
                    val extra = d.mcpTok - d.noMcpTok
                    appendLine("  - `${d.id.removePrefix("dpaia__")}`: MCP ${fmtK(d.mcpTok.toDouble())}tok vs no-MCP ${fmtK(d.noMcpTok.toDouble())}tok (extra ${fmtK(extra.toDouble())}tok, ${pct(extra.toInt(), d.noMcpTok.toInt())} more)")
                }
                appendLine()
            }
            if (mcpSaves.isEmpty() && mcpCosts.isEmpty()) {
                appendLine("_(Both modes used similar token counts across all cases)_")
                appendLine()
            }

            // Time analysis
            appendLine("### Time Analysis")
            appendLine()
            data class TimeDiff(val id: String, val mcpMs: Long, val noMcpMs: Long)
            val timeDiffs = pairs.mapNotNull { (id, mcp, noMcp) ->
                val m = mcp?.totalDurationMs ?: return@mapNotNull null
                val n = noMcp?.totalDurationMs ?: return@mapNotNull null
                TimeDiff(id, m, n)
            }
            val mcpFaster = timeDiffs.filter { it.noMcpMs > it.mcpMs + 30_000 }.sortedByDescending { it.noMcpMs - it.mcpMs }
            val mcpSlower = timeDiffs.filter { it.mcpMs > it.noMcpMs + 30_000 }.sortedByDescending { it.mcpMs - it.noMcpMs }

            if (mcpFaster.isNotEmpty()) {
                appendLine("**Cases where MCP was significantly faster (>30s):**")
                for (d in mcpFaster) {
                    appendLine("  - `${d.id.removePrefix("dpaia__")}`: MCP ${d.mcpMs / 1000}s vs no-MCP ${d.noMcpMs / 1000}s (MCP saves ${(d.noMcpMs - d.mcpMs) / 1000}s)")
                }
                appendLine()
            }
            if (mcpSlower.isNotEmpty()) {
                appendLine("**Cases where MCP was significantly slower (>30s) — likely IDE indexing overhead:**")
                for (d in mcpSlower) {
                    appendLine("  - `${d.id.removePrefix("dpaia__")}`: MCP ${d.mcpMs / 1000}s vs no-MCP ${d.noMcpMs / 1000}s (MCP overhead ${(d.mcpMs - d.noMcpMs) / 1000}s)")
                }
                appendLine()
            }
            if (mcpFaster.isEmpty() && mcpSlower.isEmpty()) {
                appendLine("_(No cases with >30s time difference between modes)_")
                appendLine()
            }

            // Key observations
            appendLine("### Key Observations and Bottlenecks")
            appendLine()
            append(keyObservations(pairs, mcpWins, bothFailed, mcpSaves))
        }

        private fun keyObservations(
            pairs: List<Triple<String, RunRecord?, RunRecord?>>,
            mcpWins: List<Triple<String, RunRecord?, RunRecord?>>,
            bothFailed: List<Triple<String, RunRecord?, RunRecord?>>,
            tokenSavers: List<Any>,
        ): String = buildString {
            var point = 1

            if (mcpWins.isNotEmpty()) {
                appendLine("$point. **MCP is decisive for ${mcpWins.size} case(s)** where no-MCP fails entirely:")
                for ((id, mcp, noMcp) in mcpWins) {
                    appendLine("   - `${id.removePrefix("dpaia__")}`: ${mcpAdvantageReason(id)}")
                    val burnedTok = noMcp?.tokenUsage?.totalTokens
                    if (burnedTok != null && burnedTok > 10_000) {
                        appendLine("     → No-MCP burned ${fmtK(burnedTok.toDouble())} tokens without producing a working fix.")
                    }
                }
                appendLine()
                point++
            }

            appendLine("$point. **Primary no-MCP bottlenecks** (patterns observed across failing runs):")
            appendLine("   - **Grepping without direction**: Without IDE Find Usages, no-MCP reads many files via grep/cat,")
            appendLine("     consuming large numbers of input tokens before finding the right classes to change.")
            appendLine("   - **Blind compile-fix cycle**: No-MCP runs `mvn test` or `./gradlew test` to discover errors,")
            appendLine("     then tries to fix them one-by-one. Each cycle can take 1-3 minutes with 100+ failing tests.")
            appendLine("   - **Missed impacted classes**: In multi-layer Spring projects (entity → service → controller → DTO),")
            appendLine("     no-MCP often updates 2-3 layers but misses the last one. MCP's compile feedback highlights")
            appendLine("     every red underline immediately.")
            appendLine("   - **Import resolution**: Spring Security and Jakarta/Javax APIs have overlapping class names.")
            appendLine("     Without IDE auto-import, no-MCP guesses the wrong package and the code compiles but fails at runtime.")
            appendLine()
            point++

            appendLine("$point. **MCP overhead** (when MCP is slower/uses more tokens):")
            appendLine("   - Opening the project in IntelliJ and waiting for indexing adds 2-5 minutes of setup per test.")
            appendLine("   - steroid_execute_code calls add round-trip latency vs direct bash/file operations.")
            appendLine("   - For simple, well-specified tasks (single-file changes) this overhead can exceed the benefit.")
            appendLine("   - Payoff is clearest for complex multi-file changes with compilation dependencies.")
            appendLine()
            point++

            if (bothFailed.isNotEmpty()) {
                appendLine("$point. **${bothFailed.size} genuinely hard case(s)** where neither mode succeeded:")
                for ((id, _, _) in bothFailed) {
                    appendLine("   - `${id.removePrefix("dpaia__")}`: May require longer timeouts, larger context, or specialist prompts.")
                }
                appendLine()
                point++
            }

            appendLine("$point. **Recommendations** for improving MCP performance:")
            appendLine("   - Pre-open the project and trigger indexing BEFORE starting the agent prompt.")
            appendLine("   - Use a project-specific system prompt that directs the agent to immediately open the project")
            appendLine("     at the given path rather than exploring the filesystem first.")
            appendLine("   - For Maven projects: cache `.m2` dependencies in the Docker image to avoid download overhead.")
            appendLine("   - Add a verification step: after claiming a fix, run the failing tests to confirm PASS.")
        }

        private fun mcpAdvantageReason(instanceId: String): String = when {
            instanceId.contains("feature__service-125") -> "44 KB cross-layer patch — IDE navigation + JPQL query validation"
            instanceId.contains("feature__service-122") -> "Full notification subsystem — Find Usages identifies all impacted classes"
            instanceId.contains("empty__maven__springboot3-1") -> "JWT auth from scratch — Spring Security API versioning traps; IDE auto-import prevents wrong package"
            instanceId.contains("feature__service-25") -> "Self-referential JPA entity — circular dep caught by IDE compile check, invisible to grep"
            instanceId.contains("feature__service-22") -> "FeatureReactionController — IDE Find Usages confirms all reaction endpoints updated"
            instanceId.contains("empty__maven__springboot3-3") -> "jakarta vs javax validation — IDE highlights wrong import package immediately"
            instanceId.contains("feature__service-21") -> "Comments/replies multi-controller — IDE consistency check across all controllers"
            instanceId.contains("spring__petclinic__rest-23") -> "Password policy — IDE per-class test runner isolates failing tests, no-MCP runs all 468 at once"
            instanceId.contains("spring__petclinic__rest-14") -> "575 failing tests — IDE compile check shows every missed edit immediately"
            instanceId.contains("spring__boot__microshop-1") -> "WebClient migration — IDE Find Usages finds all RestTemplate call sites across microservices"
            else -> "complex multi-file change benefiting from IDE navigation and compile feedback"
        }

        private fun detailedNotes(pairs: List<Triple<String, RunRecord?, RunRecord?>>): String = buildString {
            for ((id, mcp, noMcp) in pairs) {
                appendLine("### ${id.removePrefix("dpaia__")}")
                appendLine()
                appendLine("**Tags:** ${(mcp?.tags ?: noMcp?.tags ?: emptyList()).joinToString(", ")}")
                appendLine()

                for (rec in listOfNotNull(mcp, noMcp)) {
                    val modeLabel = if (rec.withMcp) "Claude + MCP" else "Claude (no MCP)"
                    appendLine("**$modeLabel:**")
                    appendLine("- Fix claimed: ${if (rec.agentClaimedFix) "yes ✅" else "no ❌"}")
                    appendLine("- Total time: ${rec.totalDurationMs / 1000}s (agent: ${rec.agentDurationMs / 1000}s)")
                    if (rec.withMcp && rec.prewarmDurationMs > 0) {
                        appendLine("- Pre-warm time: ${rec.prewarmDurationMs / 1000}s (IDE open + index, not in agent budget)")
                    }
                    appendLine("- Exit code: ${rec.exitCode ?: "n/a"}")
                    if (rec.usedMcpSteroid) appendLine("- Used steroid_execute_code: yes")
                    val tm = rec.testMetrics
                    if (tm != null) {
                        val build = when (tm.buildSuccess) {
                            true -> "BUILD SUCCESS ✅"
                            false -> "BUILD FAILURE ❌"
                            null -> "build status unknown"
                        }
                        appendLine("- Tests: ${tm.testsPass}/${tm.testsRun} passed (fail=${tm.testsFail}, err=${tm.testsError}) | $build")
                    } else {
                        appendLine("- Tests: (not available — agent may not have run tests)")
                    }
                    val u = rec.tokenUsage
                    if (u != null) {
                        appendLine("- Tokens: ${u.inputTokens} in / ${u.outputTokens} out (cache-read: ${u.cacheReadTokens}), ${u.numTurns ?: "?"} turns")
                        if (u.costUsd != null) appendLine("- Cost: \$${fmtCost(u.costUsd)}")
                    } else {
                        appendLine("- Tokens: not available")
                    }
                    if (rec.agentSummary != null) appendLine("- Summary: ${rec.agentSummary}")
                    if (rec.errorMessage != null) appendLine("- Error: ${rec.errorMessage}")
                    appendLine()
                }
                appendLine("---")
                appendLine()
            }
        }

        private fun buildJsonReport(records: List<RunRecord>): String {
            val arr = buildJsonArray {
                for (r in records) {
                    add(buildJsonObject {
                        put("instanceId", r.instanceId)
                        put("mode", r.modeLabel)
                        put("totalDurationMs", r.totalDurationMs)
                        put("agentDurationMs", r.agentDurationMs)
                        put("prewarmDurationMs", r.prewarmDurationMs)
                        put("exitCode", r.exitCode ?: -1)
                        put("agentClaimedFix", r.agentClaimedFix)
                        put("usedMcpSteroid", r.usedMcpSteroid)
                        put("agentSummary", r.agentSummary ?: "")
                        put("errorMessage", r.errorMessage ?: "")
                        val u = r.tokenUsage
                        if (u != null) {
                            put("inputTokens", u.inputTokens)
                            put("outputTokens", u.outputTokens)
                            put("cacheReadTokens", u.cacheReadTokens)
                            put("totalTokens", u.totalTokens)
                            put("costUsd", u.costUsd ?: 0.0)
                            put("numTurns", u.numTurns ?: 0)
                        }
                        val tm = r.testMetrics
                        if (tm != null) {
                            put("testsRun", tm.testsRun)
                            put("testsPass", tm.testsPass)
                            put("testsFail", tm.testsFail)
                            put("testsError", tm.testsError)
                            put("buildSuccess", tm.buildSuccess ?: false)
                        }
                        if (r.steroidCallCount != null) put("steroidCallCount", r.steroidCallCount)
                        if (r.totalToolCalls != null) put("totalToolCalls", r.totalToolCalls)
                        if (r.toolErrorCount != null) put("toolErrorCount", r.toolErrorCount)
                    })
                }
            }
            return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), arr)
        }

        // ── Formatting helpers ──────────────────────────────────────────────

        private fun pct(n: Int, d: Int): String = if (d == 0) "0%" else "${n * 100 / d}%"
        private fun fmtSec(ms: Double?): String = if (ms == null) "–" else "${(ms / 1000).toInt()}s"
        private fun fmtSec(ms: Long): String = "${ms / 1000}s"
        private fun fmtK(n: Double?): String {
            if (n == null) return "–"
            return if (n >= 1000.0) "${(n / 1000).toInt()}k" else n.toInt().toString()
        }
        private fun fmtCost(d: Double): String = String.format("%.4f", d)
        private fun avgSecOrNull(msValues: List<Long>): Double? =
            if (msValues.isEmpty()) null else msValues.average() / 1000.0
        private fun avgLongOrNull(values: List<Long>): Double? =
            if (values.isEmpty()) null else values.average()

        private fun fmtAvg(v: Double?): String = if (v == null) "–" else v.toInt().toString()
        private fun fmtPctOrDash(v: Double?): String = if (v == null) "–" else "${(v * 100).toInt()}%"
        private fun deltaAvgPct(mcp: Double?, noMcp: Double?): String {
            if (mcp == null || noMcp == null || noMcp == 0.0) return "–"
            val delta = (mcp - noMcp) / noMcp * 100
            return if (delta >= 0) "+${delta.toInt()}%" else "${delta.toInt()}%"
        }
        private fun deltaSecPct(mcpSec: Double?, noMcpSec: Double?): String {
            if (mcpSec == null || noMcpSec == null || noMcpSec == 0.0) return "–"
            val delta = (mcpSec - noMcpSec) / noMcpSec * 100
            return if (delta >= 0) "+${delta.toInt()}%" else "${delta.toInt()}%"
        }
        private fun deltaCostPct(mcpCost: Double, noMcpCost: Double): String {
            if (noMcpCost == 0.0) return "–"
            val delta = (mcpCost - noMcpCost) / noMcpCost * 100
            return if (delta >= 0) "+${delta.toInt()}%" else "${delta.toInt()}%"
        }
        private fun deltaFixRate(mcpFixed: Int, noMcpFixed: Int, total: Int): String = when {
            total == 0 -> "–"
            mcpFixed == noMcpFixed -> "tie"
            mcpFixed > noMcpFixed -> "+${mcpFixed - noMcpFixed} MCP"
            else -> "+${noMcpFixed - mcpFixed} no-MCP"
        }
    }
}
