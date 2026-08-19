/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.asDockerClaudeSession
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Abstract base class for dedicated DPAIA scenario tests — **Claude Code and Codex**.
 *
 * Each subclass overrides [instanceId] to select a specific dpaia arena scenario.
 * Four test methods are inherited: "claude with mcp", "claude without mcp",
 * "codex with mcp", and "codex without mcp".
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

    /**
     * What the solution-readiness pilot attaches to this run; [DpaiaRunSeams.NONE] for a scenario test.
     *
     * Overridable rather than a parameter of [runAgent] because a recorded or probed run needs the same
     * flow an ordinary scenario runs — see [DpaiaRunSeams] for why a second copy of it was not an
     * option. With the default set, every statement of [runAgent] is the run this suite always
     * performed: the seam calls are four no-ops.
     */
    open val seams: DpaiaRunSeams = DpaiaRunSeams.NONE

    // The method budget must clear the agent's own budget plus TWO whole-suite runs (the pre-agent
    // regression baseline and the post-agent comparison) plus container startup and indexing. The
    // heaviest cases already allow the agent 90 minutes on its own, so 60 here used to cap the agent
    // rather than the test; a method killed mid-verification loses every artifact it had gathered.
    // Nothing inside this budget is measured — the agent timer covers the agent run alone.

    // ── Claude ───────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() {
        runAgent("claude", withMcp = true)
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() {
        runAgent("claude", withMcp = false)
    }

    // ── Codex ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() {
        runAgent("codex", withMcp = true)
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() {
        runAgent("codex", withMcp = false)
    }

    // ── Test execution ───────────────────────────────────────────────────────

    /**
     * One scenario arm, unchanged, optionally recorded / patched / read through [seams].
     *
     * Public rather than private because the pilot's capture and probe tests HOLD a scenario flow instead
     * of extending it: a named subclass would inherit this class's four graded `@Test` methods, so
     * `--tests '*CheckpointCaptureTest*'` would spend four extra Opus runs that record nothing at all.
     */
    fun runAgent(agentName: String, withMcp: Boolean) {
        val datasetCase = resolvedTestCase
        val modeLabel = if (withMcp) "mcp" else "none"
        val caseConfig = DpaiaCuratedCases.CASE_CONFIGS[datasetCase.instanceId]
            ?: DpaiaCuratedCases.CaseConfig()

        // Everything below — deploy, oracle, verification, reports — runs against the overlay-augmented
        // case, so a locally authored test class counts as a FAIL_TO_PASS class like any dataset one.
        // CASE_CONFIGS is keyed on the dataset id and must be looked up before this point.
        val testCase = DpaiaCuratedCases.applyOverlay(datasetCase, caseConfig) { resourcePath ->
            checkNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
                "Overlay patch resource not found: $resourcePath"
            }.use { it.readBytes().decodeToString() }
        }

        val consoleTitle = instanceId.take(40)

        val lifetime = CloseableStackHost()
        try {
            val aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE
            val mcpMode = if (withMcp) null else McpConnectionMode.None

            println("[ARENA] Creating container for [$agentName+$modeLabel] ${testCase.instanceId} ...")

            val buildSystem = when (testCase.buildSystem) {
                "maven" -> BuildSystem.MAVEN
                "gradle" -> BuildSystem.GRADLE
                else -> BuildSystem.NONE
            }

            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
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
            )).waitForProjectReady(
                timeoutMillis = caseConfig.projectReadyTimeoutMs,
                projectJdkVersion = caseConfig.projectJdkVersion,
                buildSystem = buildSystem,
                compileProject = true,
                // The arena project is expected NOT to compile here: the dataset's test patch is the
                // task, and its tests call production code the agent has yet to write. The compile is a
                // warm-up (dependencies, indexes) before the agent's timer starts, never an assertion.
                requireCleanCompile = false,
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

            // Snapshot every test-patch file's hash BEFORE the agent runs, so verify() below can detect
            // whether the agent (or the prompt itself) tampered with the FAIL_TO_PASS test definitions
            // instead of fixing production code.
            val verifier = ArenaVerifier(session.scope, ideProjectDir, testCase.buildSystem)

            // Baseline whole-suite state, taken BEFORE the agent and outside its timer. This is the only
            // regression evidence available: 149 of the 154 dataset cases ship an empty PASS_TO_PASS, so
            // "did the agent break anything" is otherwise unanswerable, and a suite that was already red
            // (unrelated module, or tests needing an absent Docker socket) reads as the agent's fault.
            val baselineSuite = verifier.baselineSnapshotAtBaseCommit(
                baseCommit = testCase.baseCommit,
                projectJdkVersion = caseConfig.projectJdkVersion,
            )

            // SEAM — the work tree, after the deploy and the baseline and before anything the agent is
            // held responsible for. The readiness probe applies its checkpoint patch here: the baseline
            // must describe the case's own pristine state (it is the regression reference for every arm
            // and every probe cell alike), while the tamper snapshot below must describe the state the
            // agent actually inherited, or every probe would be charged with whatever the capture did.
            seams.prepareTree(session, ideProjectDir, testCase)

            // Hashed AFTER the baseline suite, not before: anything the build itself rewrites (a
            // formatter plugin bound to a lifecycle phase, a code generator) would otherwise be charged
            // to the agent as tampering with the FAIL_TO_PASS oracle. The baseline runs in a detached
            // worktree, so it cannot normalize this tree — the formatter has to be invoked here.
            verifier.normalizeFormattingBeforeSnapshot(caseConfig.projectJdkVersion)
            val preAgentSnapshot = verifier.snapshotTestFiles(testCase.testPatch)

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

            // SEAM — the recorder is installed on the very session the runner is about to drive, and
            // only ever on Claude: its seam is a Claude Code `--settings` hook file, so a recorder handed
            // to another agent would leave a run indistinguishable from an unrecorded one. Installed here
            // and not earlier, because the harness's own pre-agent work (deploy gate, baseline suite,
            // formatting, snapshots) is not part of the agent's trajectory and must not be counted as
            // tool calls — `n` normalizes every published checkpoint position.
            val recorder = seams.recorderFor(session, ideProjectDir)?.also { rec ->
                check(agentName == "claude") {
                    "the checkpoint recorder registers a Claude Code PostToolUse hook, and $agentName " +
                        "has no such seam — a recorded run of it would count nothing"
                }
                rec.install(session.aiAgents.claude.asDockerClaudeSession())
            }

            val result = runner.runTest(
                testCase = testCase,
                agent = agent,
                withMcp = withMcp,
                timeoutSeconds = caseConfig.agentTimeoutSeconds,
                predeployedProjectDir = ideProjectDir,
                logDir = session.runDirInContainer,
                // SEAM — the brief the graded scenario sends, decorated. The default decorator is the
                // identity, so an ordinary scenario still sends exactly `ArenaTestRunner.buildPrompt`.
                promptBuilder = { dir -> seams.decoratePrompt(runner.buildPrompt(testCase, dir, withMcp)) },
            )

            // ── Objective FAIL_TO_PASS verification (outside the agent timer) ────
            // Independent of whatever the agent claimed: re-runs the FAIL_TO_PASS classes and grades
            // them from surefire XML. Infra failure here (container died, Maven unreachable, ...) must
            // not fail the whole arena run — it degrades to an unverified record instead.
            val verification = try {
                verifier.verify(
                    failToPass = testCase.failToPass,
                    projectJdkVersion = caseConfig.projectJdkVersion,
                    testPatch = testCase.testPatch,
                    preAgentSnapshot = preAgentSnapshot,
                    baseline = baselineSuite,
                )
            } catch (e: Exception) {
                System.err.println(
                    "[ARENA] Objective FAIL_TO_PASS verification failed for ${testCase.instanceId} " +
                        "[$agentName+$modeLabel]: ${e.message}"
                )
                e.printStackTrace()
                null
            }

            // ── Extract metrics from agent NDJSON ────────────────────────────────
            val metrics = collectRunMetrics(
                runDir = session.runDirInContainer,
                agentName = agentName,
                fallbackStdout = result.agentResult.stdout,
            )

            val record = RunRecord(
                instanceId = testCase.instanceId,
                agentName = agentName,
                withMcp = withMcp,
                agentDurationMs = result.agentDurationMs,
                prewarmMs = 0L, // Prewarm is now inside waitForProjectReady
                exitCode = result.agentResult.exitCode,
                claimedFix = result.evaluation.agentClaimedFix,
                usedMcpSteroid = result.evaluation.usedMcpSteroid,
                summary = result.evaluation.agentSummary,
                tokenUsage = metrics.tokenUsage,
                testMetrics = metrics.testMetrics,
                decodedLogMetrics = metrics.decodedLogMetrics,
                verification = verification,
                baselinePassing = baselineSuite.passing.size,
                baselineAlreadyFailing = baselineSuite.failing.size,
                runDirPath = session.runDirInContainer.absolutePath,
            )
            results.add(record)

            // Write JSON summary
            writeArenaRunSummary(testCase.instanceId, agentName, modeLabel, record)

            // Print summary
            println("[ARENA] ════════════════════════════════════════")
            println("[ARENA] $agentName+$modeLabel — ${testCase.instanceId}")
            println("[ARENA]   Claimed fix:    ${record.claimedFix}")
            println("[ARENA]   Used MCP:       ${record.usedMcpSteroid}")
            println("[ARENA]   Exit code:      ${record.exitCode}")
            println("[ARENA]   Agent time:     ${record.agentDurationMs / 1000}s")
            println("[ARENA]   Prewarm time:   ${record.prewarmMs / 1000}s")
            if (metrics.tokenUsage != null) {
                println("[ARENA]   Tokens in/out:  ${metrics.tokenUsage.inputTokens}/${metrics.tokenUsage.outputTokens}")
                println("[ARENA]   Cache create:   ${metrics.tokenUsage.cacheCreationTokens}")
                println("[ARENA]   Cache read:     ${metrics.tokenUsage.cacheReadTokens}")
                println("[ARENA]   Cost:           $${metrics.tokenUsage.costUsd ?: "?"}")
                println("[ARENA]   Turns:          ${metrics.tokenUsage.numTurns ?: "?"}")
                println("[ARENA]   API duration:   ${metrics.tokenUsage.durationApiMs?.let { "${it / 1000}s" } ?: "?"}")
            } else {
                // Codex sometimes ends its stream on `item.completed` without the closing
                // `turn.completed` that carries usage. Nothing in the transcript can reconstruct it, so
                // say so instead of printing no token line at all — an absent line reads as an oversight.
                println("[ARENA]   Tokens:         MISSING — the agent CLI emitted no usage event; cost is unrecoverable for this arm")
            }
            if (metrics.testMetrics != null) {
                println("[ARENA]   Tests:          ${metrics.testMetrics.testsRun} run, ${metrics.testMetrics.testsFail} fail, BUILD ${if (metrics.testMetrics.buildSuccess == true) "SUCCESS" else "FAILURE"}")
            }
            if (verification != null) {
                println("[ARENA]   Verified FTP:   ${verification.classesPassed}/${verification.classesTotal}")
                println("[ARENA]   Objective:      ${verification.objectiveSuccess} (regressions: ${verification.regressions.size}${if (verification.regressionScanTruncated) ", scan TRUNCATED — lower bound" else ""})")
                if (verification.regressions.isNotEmpty()) {
                    println("[ARENA]   Regressed:      ${verification.regressions.joinToString()}")
                }
                if (verification.collateralTestFilesEdited.isNotEmpty()) {
                    println("[ARENA]   Collateral tests edited: ${verification.collateralTestFilesEdited.joinToString()}")
                }
            }
            println("[ARENA]   Baseline suite: ${baselineSuite.passing.size} passing, ${baselineSuite.failing.size} already failing")
            if (baselineSuite.failing.isNotEmpty()) {
                println("[ARENA]   Already red before the agent: ${baselineSuite.failing.sorted().joinToString()}")
            }
            if (metrics.decodedLogMetrics != null) {
                println("[ARENA]   exec_code:      ${metrics.decodedLogMetrics.execCodeCalls}")
                println("[ARENA]   Read/Edit/Write: ${metrics.decodedLogMetrics.readCalls}/${metrics.decodedLogMetrics.editCalls}/${metrics.decodedLogMetrics.writeCalls}")
                println("[ARENA]   Glob/Grep/Bash: ${metrics.decodedLogMetrics.globCalls}/${metrics.decodedLogMetrics.grepCalls}/${metrics.decodedLogMetrics.bashCalls}")
            }
            println("[ARENA]   Summary:        ${record.summary ?: "(none)"}")
            println("[ARENA] ════════════════════════════════════════")

            // SEAM — the finished run, read. Before the assertions below so a verdict line reaches the
            // build log even when the run is later ruled invalid, and after every artifact is on disk so
            // a seam that fails cannot cost the run its report.
            seams.afterAgentRun(DpaiaRunOutcome(
                instanceId = testCase.instanceId,
                agentName = agentName,
                modeLabel = modeLabel,
                agentDurationMs = result.agentDurationMs,
                endContextTokens = metrics.endContextTokens,
                costUsd = metrics.tokenUsage?.costUsd,
                verification = verification,
                recorder = recorder,
            ))

            // Lenient assertion
            check(result.evaluation.agentExitedSuccessfully || result.evaluation.agentClaimedFix) {
                "${agentName.replaceFirstChar { it.uppercase() }} [$agentName+$modeLabel] neither exited successfully (exit=${result.agentResult.exitCode}) " +
                        "nor claimed a fix for ${testCase.instanceId}."
            }

            if (withMcp) {
                check(result.evaluation.usedMcpSteroid) {
                    "${agentName.replaceFirstChar { it.uppercase() }} [$agentName+mcp] did not use steroid_execute_code for ${testCase.instanceId}."
                }
            }

            // Last, so every artifact above is already on disk: an agent that rewrote the FAIL_TO_PASS
            // files rewrote its own oracle, so its grade measures nothing. Recording that as data next to
            // a green build (the old behaviour) publishes a number we know is meaningless.
            check(verification == null || !verification.failToPassTampered) {
                "[$agentName+$modeLabel] ${testCase.instanceId}: the agent modified FAIL_TO_PASS test " +
                    "files, so the ${verification?.classesPassed}/${verification?.classesTotal} grade is " +
                    "not a measurement of the fix — it graded tests the agent rewrote. Run invalid."
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

        for (r in results.sortedWith(compareBy({ it.agentName }, { !it.withMcp }))) {
            val mode = if (r.withMcp) "${r.agentName}+mcp" else "${r.agentName}+none"
            println("║ ${mode.padEnd(16)}                                                                       ║")
            println("║   Fix: ${if (r.claimedFix) "YES" else "NO "}  Exit: ${(r.exitCode?.toString() ?: "?").padStart(3)}  " +
                    "Agent: ${(r.agentDurationMs / 1000).toString().padStart(4)}s  " +
                    "Prewarm: ${(r.prewarmMs / 1000).toString().padStart(4)}s                              ║")
            val t = r.tokenUsage
            if (t != null) {
                // Only Claude self-reports a dollar figure. Rendering a missing cost as $0.00 would
                // read as "this run was free" now that Codex token counts are populated.
                val cost = t.costUsd?.let { "$" + String.format("%.2f", it) } ?: "n/a"
                println("║   Tokens: ${t.inputTokens}in/${t.outputTokens}out  " +
                        "Cache: ${t.cacheCreationTokens}c/${t.cacheReadTokens}r  " +
                        "Cost: $cost  " +
                        "Turns: ${t.numTurns ?: "?"}".padEnd(56) + "║")
            }
            val m = r.testMetrics
            if (m != null) {
                println("║   Tests: ${m.testsRun} run, ${m.testsPass} pass, ${m.testsFail} fail  " +
                        "BUILD ${if (m.buildSuccess == true) "SUCCESS" else "FAILURE"}".padEnd(49) + "║")
            }
            val v = r.verification
            println("║   Verified: ${(v?.let { "${it.classesPassed}/${it.classesTotal}" } ?: "?")}  " +
                    "Claim matches reality: ${if (v != null) claimMatchesReality(r).toString() else "?"}  " +
                    "Tamper: ${v?.failToPassTampered?.toString() ?: "?"}".padEnd(37) + "║")
            if (v != null) {
                println("║   Objective success: ${v.objectiveSuccess}  " +
                        "Regressions: ${if (v.baselineAvailable) v.regressions.size.toString() else "?"}  " +
                        "Collateral tests edited: ${v.collateralTestFilesEdited.size}".padEnd(34) + "║")
            }
            println("║   ${(r.summary ?: "(no summary)").take(72).padEnd(72)}      ║")
        }

        println("╚═══════════════════════════════════════════════════════════════════════════════════════╝")
        println()
    }

    // ── Dataset loading ──────────────────────────────────────────────────────

    private val resolvedTestCase: DpaiaTestCase by lazy {
        DpaiaDatasetLoader.findById(dataset, instanceId)
    }

    data class RunRecord(
        val instanceId: String,
        val agentName: String,
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
        /** Objective FAIL_TO_PASS grade from [ArenaVerifier.verify]; null when verification itself failed. */
        val verification: ArenaVerificationResult? = null,
        /** Test classes green in the pre-agent whole-suite baseline. */
        val baselinePassing: Int? = null,
        /** Test classes ALREADY failing before the agent ran — the pre-existing failures it is not to blame for. */
        val baselineAlreadyFailing: Int? = null,
        val runDirPath: String = "",
        /**
         * The semantic-ripple track's own grade, or null for a DPAIA run.
         *
         * Carried on the shared record because the two tracks deliberately share one summary shape —
         * see [RippleRunSummary] for why `objective_success` alone cannot stand in for it.
         */
        val rippleSummary: RippleRunSummary? = null,
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

/**
 * Whether the agent's own claim agrees with the harness's measurement.
 *
 * Compared against [ArenaVerificationResult.objectiveSuccess] — FAIL_TO_PASS green AND no regression —
 * rather than against a green whole-suite build. An agent that fixed the task but refused the success
 * marker because of failures it did not cause is a **conservative agent**, not a wrong one, and it shows
 * up here as `false` with `objective_success=true`, which is a different finding from a false claim.
 */
fun claimMatchesReality(rec: DpaiaScenarioBaseTest.RunRecord): Boolean =
    rec.claimedFix == (rec.verification?.objectiveSuccess == true)

/**
 * Pure JSON-summary builder for one arena run — no I/O, no container access — so the shape of the
 * summary (including the objective verification fields) is unit-testable without Docker.
 */
fun buildRunSummaryJson(rec: DpaiaScenarioBaseTest.RunRecord): JsonObject = buildJsonObject {
    put("instance_id", rec.instanceId)
    put("agent", rec.agentName)
    put("mode", if (rec.withMcp) "mcp" else "none")
    put("run_dir", rec.runDirPath)
    put("exit_code", rec.exitCode ?: -1)
    put("agent_claimed_fix", rec.claimedFix)
    put("used_mcp_steroid", rec.usedMcpSteroid)
    put("agent_duration_ms", rec.agentDurationMs)
    put("prewarm_ms", rec.prewarmMs)
    rec.tokenUsage?.let { t ->
        put("input_tokens", t.inputTokens)
        put("output_tokens", t.outputTokens)
        put("cache_read_tokens", t.cacheReadTokens)
        put("cache_creation_tokens", t.cacheCreationTokens)
        t.costUsd?.let { put("cost_usd", it) }
        t.numTurns?.let { put("num_turns", it) }
        t.durationApiMs?.let { put("duration_api_ms", it) }
    }
    rec.testMetrics?.let { m ->
        put("tests_run", m.testsRun)
        put("tests_pass", m.testsPass)
        put("tests_fail", m.testsFail)
        m.buildSuccess?.let { put("build_success", it) }
    }
    rec.decodedLogMetrics?.let { d ->
        put("exec_code_calls", d.execCodeCalls)
        put("read_calls", d.readCalls)
        put("write_calls", d.writeCalls)
        put("edit_calls", d.editCalls)
        put("bash_calls", d.bashCalls)
        put("glob_calls", d.globCalls)
        put("grep_calls", d.grepCalls)
    }
    // Objective FAIL_TO_PASS grade from ArenaVerifier.verify(), independent of the agent's own claim.
    // Kept present (as JSON null) even when verification itself failed, so downstream tooling never
    // has to distinguish "verifier said 0/0" from "verifier didn't run" by a missing key.
    put("verified_ftp_passed", rec.verification?.classesPassed)
    put("verified_ftp_total", rec.verification?.classesTotal)
    put("verified_ftp_rate", rec.verification?.failToPassRate)
    put("objective_success", rec.verification?.objectiveSuccess)
    put("claim_matches_reality", claimMatchesReality(rec))
    put("fail_to_pass_tampered", rec.verification?.failToPassTampered)
    put("collateral_test_files_edited", rec.verification?.collateralTestFilesEdited?.joinToString(";"))
    put("regressions", rec.verification?.takeIf { it.baselineAvailable }?.regressions?.joinToString(";"))
    put("regression_count", rec.verification?.takeIf { it.baselineAvailable }?.regressions?.size)
    put("regression_scan_truncated", rec.verification?.regressionScanTruncated)
    put("baseline_passing", rec.baselinePassing)
    put("baseline_already_failing", rec.baselineAlreadyFailing)
    rec.rippleSummary?.let { put("ripple", buildRippleRunSummaryJson(it)) }
    put("agent_summary", rec.summary ?: "")
    put("timestamp", java.time.Instant.now().toString())
}
