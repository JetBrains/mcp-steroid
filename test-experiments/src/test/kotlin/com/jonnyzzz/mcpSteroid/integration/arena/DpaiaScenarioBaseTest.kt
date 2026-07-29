/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.server.ExecCodeDescriptionVariant
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Per-test-method ceiling for a scenario arm.
 *
 * One arm pays, in sequence: container start + clone + import + full compile (bounded by
 * `CaseConfig.projectReadyTimeoutMs`, plus startup that timeout does not cover), the agent run
 * (`CaseConfig.agentTimeoutSeconds`, up to 90 min for the slowest curated cases), and the
 * out-of-timer FAIL_TO_PASS verification ([VERIFICATION_MAVEN_TIMEOUT_SECONDS] plus hashing).
 * The value must exceed the sum of those ceilings, otherwise JUnit kills a run that is still
 * inside its own budget and the arm is lost after an hour of real API spend.
 * `DpaiaConfigTest` asserts the arithmetic against the curated cases.
 */
const val ARENA_ARM_TIMEOUT_MINUTES = 150L

/** Headroom for what no inner timeout covers: image pull/build, IDE startup, teardown, artifact copy. */
const val ARENA_ARM_TIMEOUT_HEADROOM_MINUTES = 15L

/**
 * Which MCP transport (if any) the agent gets for this arena run, and which `steroid_execute_code`
 * tool description the container serves it.
 *
 * [execDescription] defaults to the repo default so every historical arm keeps being measured against
 * the same text it always was; [MCP_HTTP_SLIM] is the same MCP arm reading the router variant, which
 * is what makes the two descriptions comparable within one run of a scenario.
 */
enum class ArenaMode(val label: String, val execDescription: ExecCodeDescriptionVariant) {
    /** MCP Steroid over direct HTTP. */
    MCP_HTTP("mcp", ExecCodeDescriptionVariant.FULL),
    /** MCP Steroid over direct HTTP, served the slim router tool description. */
    MCP_HTTP_SLIM("mcp-slim", ExecCodeDescriptionVariant.SLIM),
    /** MCP Steroid over the devrig stdio bridge. */
    DEVRIG("devrig", ExecCodeDescriptionVariant.FULL),
    /** No MCP registered — shell-only baseline. */
    NONE("none", ExecCodeDescriptionVariant.FULL),
}

/**
 * Abstract base class for dedicated DPAIA scenario tests — **Claude Code and Codex**.
 *
 * Each subclass overrides [instanceId] to select a specific dpaia arena scenario.
 * Eleven test methods are inherited. Claude runs only its two reference arms, four times each, to
 * measure their own spread: "claude with mcp" plus "repeat 2/3/4", and "claude without mcp" plus
 * "repeat 2/3/4". Codex keeps one run per arm: "codex with mcp", "codex with devrig",
 * "codex without mcp".
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

    // ── Claude ───────────────────────────────────────────────────────────────

    // Claude runs the two reference arms only, four times each: their own spread has to be known before
    // any single-run difference elsewhere can be read as an effect. The `devrig` and `mcp-slim` arms stay
    // available through [ArenaMode] (Codex still runs devrig) but are not wired to a Claude test method,
    // so the TC filter `*<TestClass>.claude*` selects exactly these eight runs. Repeats are
    // configuration-identical — only the report label carries `-rN`.

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = runAgent("claude", ArenaMode.MCP_HTTP)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `claude with mcp repeat 2`() = runAgent("claude", ArenaMode.MCP_HTTP, repeat = 2)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `claude with mcp repeat 3`() = runAgent("claude", ArenaMode.MCP_HTTP, repeat = 3)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `claude with mcp repeat 4`() = runAgent("claude", ArenaMode.MCP_HTTP, repeat = 4)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = runAgent("claude", ArenaMode.NONE)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `claude without mcp repeat 2`() = runAgent("claude", ArenaMode.NONE, repeat = 2)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `claude without mcp repeat 3`() = runAgent("claude", ArenaMode.NONE, repeat = 3)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `claude without mcp repeat 4`() = runAgent("claude", ArenaMode.NONE, repeat = 4)

    // ── Codex ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = runAgent("codex", ArenaMode.MCP_HTTP)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `codex with devrig`() = runAgent("codex", ArenaMode.DEVRIG)

    @Test
    @Timeout(value = ARENA_ARM_TIMEOUT_MINUTES, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = runAgent("codex", ArenaMode.NONE)

    // ── Test execution ───────────────────────────────────────────────────────

    /**
     * @param repeat 1-based index for arms that run several times in one build to measure their own
     * spread. It only widens the report label, so each repeat gets its own container title, JSON summary
     * and CSV row instead of overwriting the previous one; the arm's configuration stays identical.
     */
    private fun runAgent(agentName: String, mode: ArenaMode, repeat: Int = 1) {
        val baseCase = resolvedTestCase
        val modeLabel = if (repeat == 1) mode.label else "${mode.label}-r$repeat"
        val withMcp = mode != ArenaMode.NONE
        val caseConfig = DpaiaCuratedCases.CASE_CONFIGS[baseCase.instanceId]
            ?: DpaiaCuratedCases.CaseConfig()

        // A per-case overlay applies a local Docker-free test patch AFTER the dataset test patch,
        // so it can add its own FAIL_TO_PASS class without touching the dataset's own patch content.
        val overlayPatch = caseConfig.overlayTestPatch?.let { resourcePath ->
            checkNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
                "Overlay patch resource not found: $resourcePath"
            }.readBytes().decodeToString()
        }
        val effectiveCase = if (overlayPatch == null) baseCase else baseCase.copy(
            testPatch = baseCase.testPatch + "\n" + overlayPatch,
            failToPass = baseCase.failToPass + caseConfig.overlayFailToPass,
        )
        // Distinguishes an overlay-augmented run in reports; CASE_CONFIGS lookups always key on the
        // original instanceId (effectiveCase.instanceId is left untouched by the copy above).
        val reportInstanceId = if (overlayPatch == null) baseCase.instanceId else "${baseCase.instanceId}x"

        val consoleTitle = instanceId.take(40)

        val lifetime = CloseableStackHost()
        try {
            val aiMode = when (mode) {
                ArenaMode.MCP_HTTP, ArenaMode.MCP_HTTP_SLIM -> AiMode.AI_MCP
                ArenaMode.DEVRIG -> AiMode.AI_DEVRIG
                ArenaMode.NONE -> AiMode.NONE
            }
            val mcpMode = if (mode == ArenaMode.NONE) McpConnectionMode.None else null

            println("[ARENA] Creating container for [$agentName+$modeLabel] ${effectiveCase.instanceId} ...")

            val buildSystem = when (effectiveCase.buildSystem) {
                "maven" -> BuildSystem.MAVEN
                "gradle" -> BuildSystem.GRADLE
                else -> BuildSystem.NONE
            }

            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "$consoleTitle-$modeLabel",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = effectiveCase.cloneUrl,
                    repoOwnerAndName = effectiveCase.repo.removeSuffix(".git"),
                    baseCommit = effectiveCase.baseCommit,
                    testPatch = effectiveCase.testPatch,
                    displayName = effectiveCase.instanceId,
                    buildSystem = effectiveCase.buildSystem,
                ),
                aiMode = aiMode,
                mcpConnectionMode = mcpMode,
                mountDockerSocket = true,
                // Set for every arm, including the ones taking the default: an arm's tool-description
                // variant is part of what the run measures, so it belongs in the container config
                // explicitly rather than being inferred from an absent variable.
                extraEnv = mapOf(ExecCodeDescriptionVariant.ENV_VAR to mode.execDescription.wire),
            )).waitForProjectReady(
                timeoutMillis = caseConfig.projectReadyTimeoutMs,
                projectJdkVersion = caseConfig.projectJdkVersion,
                buildSystem = buildSystem,
                compileProject = true,
            )

            val ideProjectDir = session.intellijDriver.getGuestProjectDir()

            // #251 Part C: lock the existing waitForMcpReady path-guarantee explicitly at the arena layer.
            // Path check only — it does NOT prove the repo content/identity (a wrong project sharing the
            // path would still pass); the FAIL_TO_PASS + green build in the run establish content.
            val openProjects = session.mcpSteroid.mcpListProjects()
            check(openProjects.any { it.path == ideProjectDir }) {
                "[ARENA] No IDE project open at the arena deploy path $ideProjectDir before the agent run " +
                    "(open: ${openProjects.joinToString { "${it.name}@${it.path}" }}). Deploy/open regression (#251)."
            }

            // The arm is only comparable if the container really serves the description the arm stands
            // for — assert on the served text, before any API spend, so a broken env plumbing fails the
            // run instead of producing a mislabelled data point.
            val servedExecDescription = session.mcpSteroid.mcpToolDescription("steroid_execute_code")
            val expectedVariant = mode.execDescription
            check(servedExecDescription.contains(expectedVariant.marker)) {
                "[ARENA] Arm '$modeLabel' expects the ${expectedVariant.wire} steroid_execute_code " +
                    "description (${ExecCodeDescriptionVariant.ENV_VAR}=${expectedVariant.wire}), but the " +
                    "served text does not carry its marker '${expectedVariant.marker}' " +
                    "(${servedExecDescription.length} chars)."
            }
            println("[ARENA] steroid_execute_code description: ${expectedVariant.wire}, " +
                "${servedExecDescription.length} chars")

            // Snapshot the test-patch files BEFORE the agent runs, so [ArenaVerifier.verify] can
            // detect tampering afterward. Excluded from the agent's timed budget. An infra hiccup here
            // must not abort the arena run — a null snapshot just disables tamper detection at verify time.
            val verifier = ArenaVerifier(session.scope, ideProjectDir)
            val preAgentSnapshot = try {
                verifier.snapshotTestFiles(effectiveCase.testPatch)
            } catch (e: Exception) {
                System.err.println("[ARENA] Pre-agent test-file snapshot failed: $e")
                null
            }

            // ── Agent run (TIMED) ────────────────────────────────────────────────
            val agent: AiAgentSession = when (agentName) {
                "claude" -> session.aiAgents.claude
                "codex" -> session.aiAgents.codex
                "gemini" -> session.aiAgents.gemini
                else -> error("Unknown agent: $agentName")
            }
            val runner = ArenaTestRunner(
                container = session.scope,
                projectGuestDir = ideProjectDir,
            )

            val result = runner.runTest(
                testCase = effectiveCase,
                agent = agent,
                withMcp = withMcp,
                timeoutSeconds = caseConfig.agentTimeoutSeconds,
                predeployedProjectDir = ideProjectDir,
                logDir = session.runDirInContainer
            )

            // ── Objective FAIL_TO_PASS verification (OUTSIDE the agent timer) ───────
            val verification = if (effectiveCase.buildSystem == "maven") {
                println("[ARENA] Verifying FAIL_TO_PASS via surefire (outside the agent timer) ...")
                try {
                    verifier.verify(
                        failToPass = effectiveCase.failToPass,
                        projectJdkVersion = caseConfig.projectJdkVersion,
                        testPatch = effectiveCase.testPatch,
                        preAgentSnapshot = preAgentSnapshot,
                    )
                } catch (e: Exception) {
                    System.err.println("[ARENA] Verification infrastructure failed: $e")
                    null
                }
            } else {
                println("[ARENA] Skipping FAIL_TO_PASS verification — build system is '${effectiveCase.buildSystem}', not maven")
                null
            }

            // ── Extract metrics from agent NDJSON ────────────────────────────────
            val rawOutput = result.agentResult.stdout
            val tokens = extractTokenUsage(rawOutput)
            val testMetrics = extractTestMetrics(rawOutput)
            val decodedLogName = when (agentName) {
                "claude" -> "claude-code"
                "codex" -> "codex"
                "gemini" -> "gemini"
                else -> agentName
            }
            val decodedLogMetrics = findDecodedLogFile(session.runDirInContainer, agentName = decodedLogName)
                ?.let { extractDecodedLogMetrics(it.readText()) }

            val record = RunRecord(
                instanceId = reportInstanceId,
                agentName = agentName,
                mode = mode,
                modeLabel = modeLabel,
                agentDurationMs = result.agentDurationMs,
                prewarmMs = 0L, // Prewarm is now inside waitForProjectReady
                exitCode = result.agentResult.exitCode,
                claimedFix = result.evaluation.agentClaimedFix,
                usedMcpSteroid = result.evaluation.usedMcpSteroid,
                summary = result.evaluation.agentSummary,
                tokenUsage = tokens,
                testMetrics = testMetrics,
                decodedLogMetrics = decodedLogMetrics,
                verification = verification,
                execDescriptionChars = servedExecDescription.length,
            )
            results.add(record)

            // Write JSON summary
            writeRunSummary(reportInstanceId, agentName, modeLabel, result, record, session.runDirInContainer)

            // Print summary
            println("[ARENA] ════════════════════════════════════════")
            println("[ARENA] $agentName+$modeLabel — ${effectiveCase.instanceId}")
            println("[ARENA]   Claimed fix:    ${record.claimedFix}")
            println("[ARENA]   Used MCP:       ${record.usedMcpSteroid}")
            println("[ARENA]   Exit code:      ${record.exitCode}")
            println("[ARENA]   exec_code desc: ${mode.execDescription.wire} (${record.execDescriptionChars} chars)")
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
            if (verification != null) {
                println("[ARENA]   Verified FTP:   ${verification.classesPassed}/${verification.classesTotal} " +
                    "(${String.format("%.0f%%", verification.failToPassRate * 100)})  tests_tampered=${verification.testsTampered}")
                verification.perClass.forEach { c ->
                    println("[ARENA]     ${if (c.passed) "PASS" else "FAIL"} ${c.className} " +
                        "(run=${c.testsRun}, failures=${c.failures}, errors=${c.errors}, skipped=${c.skipped})")
                }
            }
            println("[ARENA]   Summary:        ${record.summary ?: "(none)"}")
            println("[ARENA] ════════════════════════════════════════")

            // Lenient assertion
            check(result.evaluation.agentExitedSuccessfully || result.evaluation.agentClaimedFix) {
                "${agentName.replaceFirstChar { it.uppercase() }} [$agentName+$modeLabel] neither exited successfully (exit=${result.agentResult.exitCode}) " +
                        "nor claimed a fix for ${effectiveCase.instanceId}."
            }

            if (withMcp) {
                check(result.evaluation.usedMcpSteroid) {
                    "${agentName.replaceFirstChar { it.uppercase() }} [$agentName+$modeLabel] did not use steroid_execute_code for ${effectiveCase.instanceId}."
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
        println("║              DPAIA ARENA — AGENT COMPARISON (${instanceId.take(37).padEnd(37)})  ║")
        println("╠═══════════════════════════════════════════════════════════════════════════════════════╣")

        for (r in results.sortedWith(compareBy({ it.agentName }, { it.mode }, { it.modeLabel }))) {
            val mode = "${r.agentName}+${r.modeLabel}"
            println("║ ${mode.padEnd(16)}                                                                       ║")
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
            val v = r.verification
            if (v != null) {
                println(("║   Verified FTP: ${v.classesPassed}/${v.classesTotal} " +
                        "(${String.format("%.0f%%", v.failToPassRate * 100)})  tests_tampered=${v.testsTampered}").padEnd(91) + "║")
            }
            println("║   ${(r.summary ?: "(no summary)").take(72).padEnd(72)}      ║")
        }

        println("╚═══════════════════════════════════════════════════════════════════════════════════════╝")
        println()
    }

    private fun writeRunSummary(
        reportInstanceId: String,
        agentName: String,
        modeLabel: String,
        result: ArenaTestResult,
        record: RunRecord,
        runDir: File,
    ) {
        val summary = buildJsonObject {
            put("instance_id", reportInstanceId)
            put("agent", agentName)
            put("mode", modeLabel)
            put("run_dir", runDir.absolutePath)
            put("exit_code", result.agentResult.exitCode ?: -1)
            put("agent_claimed_fix", record.claimedFix)
            put("used_mcp_steroid", record.usedMcpSteroid)
            put("agent_duration_ms", record.agentDurationMs)
            put("prewarm_ms", record.prewarmMs)
            put("exec_description_variant", record.mode.execDescription.wire)
            put("exec_description_chars", record.execDescriptionChars)
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
            record.verification?.let { v ->
                put("verified_ftp_passed", v.classesPassed)
                put("verified_ftp_total", v.classesTotal)
                put("verified_ftp_rate", v.failToPassRate)
                put("tests_tampered", v.testsTampered)
                put("claim_matches_reality", record.claimedFix == (v.failToPassRate == 1.0))
                put("verification_ms", v.verificationDurationMs)
                putJsonArray("verified_classes") {
                    v.perClass.forEach { c ->
                        addJsonObject {
                            put("class", c.className); put("tests", c.testsRun)
                            put("failures", c.failures); put("errors", c.errors); put("passed", c.passed)
                        }
                    }
                }
            }
            put("agent_summary", record.summary ?: "")
            put("timestamp", java.time.Instant.now().toString())
        }
        val summaryFile = IdeTestFolders.testOutputDir
            .resolve("dpaia-arena-run-$reportInstanceId-$agentName-$modeLabel.json")
        summaryFile.parentFile.mkdirs()
        summaryFile.writeText(summary.toString())
        println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")

        // Append to comparison CSV
        val passLabel = System.getProperty("arena.pass.label", "")
        val csvFile = IdeTestFolders.testOutputDir.resolve("arena-comparison.csv")
        appendComparisonCsv(
            csvFile = csvFile,
            instanceId = reportInstanceId,
            passLabel = passLabel,
            mode = modeLabel,
            execDescriptionVariant = record.mode.execDescription.wire,
            execDescriptionChars = record.execDescriptionChars,
            claimedFix = record.claimedFix,
            durationS = record.agentDurationMs / 1000,
            tokens = record.tokenUsage,
            testMetrics = record.testMetrics,
            decoded = record.decodedLogMetrics,
            verification = record.verification,
        )
        println("[ARENA] Comparison CSV appended to: ${csvFile.absolutePath}")
    }

    // ── Dataset loading ──────────────────────────────────────────────────────

    private val resolvedTestCase: DpaiaTestCase by lazy {
        DpaiaDatasetLoader.findById(dataset, instanceId)
    }

    data class RunRecord(
        val instanceId: String,
        val agentName: String,
        val mode: ArenaMode,
        /** [ArenaMode.label], suffixed `-rN` for the 2nd and later repeats of the same arm. */
        val modeLabel: String,
        val agentDurationMs: Long,
        val prewarmMs: Long,
        val exitCode: Int?,
        val claimedFix: Boolean,
        val usedMcpSteroid: Boolean,
        val summary: String?,
        val tokenUsage: TokenUsage?,
        val testMetrics: TestMetrics?,
        val decodedLogMetrics: DecodedLogMetrics? = null,
        val verification: ArenaVerificationResult? = null,
        /**
         * Length of the `steroid_execute_code` description the container actually served this arm — the
         * measured counterpart of `mode.execDescription`, and the per-request tool-definition cost the
         * arm's token numbers have to be read against.
         */
        val execDescriptionChars: Int,
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
