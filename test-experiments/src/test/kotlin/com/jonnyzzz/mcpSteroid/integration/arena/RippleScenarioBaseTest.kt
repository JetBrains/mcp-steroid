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
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One arm of one keycloak-semantic-ripple case, for one agent, with and without MCP.
 *
 * Every case runs the same way — the same container, the same pre-agent gate, the same gold capture,
 * the same post-condition reading, the same reporting — and differs only in its [case]. That is the
 * conclusion three cases produced: finding the target and reading P1 back are per-kind, and
 * everything around them is not.
 *
 * A sibling of [DpaiaScenarioBaseTest] rather than a subclass — that class loads its case from the
 * dpaia dataset and takes a whole-suite regression baseline, and neither applies here. Regression
 * evidence is the scoped compile gate instead, which for a behaviour-preserving transformation is a
 * complete invariant: a missed call site is a compile error by construction.
 *
 * Reporting goes through [collectRunMetrics] and [writeArenaRunSummary], the same code the DPAIA
 * cases use, so the two tracks' numbers stay comparable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RippleScenarioBaseTest {

    abstract val case: RippleCase

    /**
     * The prompt this harness sends, and the only place it is built.
     *
     * Public and non-`@Test` so the contract tests can read the string the agent really receives
     * instead of a builder output that `ArenaTestRunner` used to replace. That substitution was the
     * defect: `runTest` built a dpaia brief around the case's `problemStatement`, so
     * [buildRipplePrompt]'s environment paragraphs — verify by compiling the changed modules, never
     * launch a reactor-wide test run — never reached any agent, while a contract test asserted they
     * did.
     */
    fun promptFor(projectDir: String, withMcp: Boolean): String =
        buildRipplePrompt(case, projectDir, withMcp)

    // The agent's own budget is 90 min (SemanticRippleSpec.agentTimeoutSeconds). A cold CI agent
    // additionally pays Docker image build (measured 34 min for the image build alone on a developer
    // machine) plus a cold Keycloak clone and Maven import. After the agent returns, grading adds the
    // post-condition query, the scoped compile gate, and the FAIL_TO_PASS verification. 90 + 34 min of
    // fixed setup plus grading overhead already exceeds 124 minutes before any headroom; 180 minutes
    // covers that with headroom, and the TeamCity cap for a two-arm build is derived from this number
    // and must stay above it.
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = runArm("claude", withMcp = true)

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = runArm("claude", withMcp = false)

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = runArm("codex", withMcp = true)

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = runArm("codex", withMcp = false)

    /**
     * One arm, unchanged, optionally RECORDED for the solution-readiness pilot.
     *
     * [recorderFactory] is a factory and not a ready [RippleCheckpointRecorder] because everything a
     * recorder addresses — the container and the guest project dir it snapshots — is created inside this
     * method. It is called once, after the gold capture (which is the harness's work, not the agent's,
     * and must not be counted as a step) and before the agent's first tool call.
     *
     * With no factory, every statement below is the run this family has always performed: the two blocks
     * the pilot adds are both inside `recorder`-guarded scopes, and `null` is the default so no existing
     * case had to be touched.
     *
     * Public rather than protected because the capture test HOLDS an arm flow instead of extending it —
     * a subclass would inherit this class's four graded `@Test` methods, and `--tests
     * '*CheckpointCaptureTest*'` would then spend four extra Opus runs that record nothing.
     */
    fun runArm(
        agentName: String,
        withMcp: Boolean,
        recorderFactory: ((session: IntelliJContainer, projectDir: String) -> RippleCheckpointRecorder)? = null,
    ) {
        val rippleCase = case
        val testCase = rippleCase.dpaiaCase()
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ripple-${rippleCase.target.kindId}-$modeLabel",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = SemanticRippleSpec.cloneUrl,
                    repoOwnerAndName = SemanticRippleSpec.repoOwnerAndName,
                    baseCommit = testCase.baseCommit,
                    testPatch = testCase.testPatch,
                    displayName = testCase.instanceId,
                    buildSystem = testCase.buildSystem,
                ),
                aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
                mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                timeoutMillis = SemanticRippleSpec.projectReadyTimeoutMs,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                // The patched tree deliberately does not compile: the hidden consumer names something
                // that does not exist yet. The prewarm build is a warm-up, never an assertion.
                requireCleanCompile = false,
            )

            val projectDir = session.intellijDriver.getGuestProjectDir()

            // Nothing else installs this reactor, and `~/.m2` is shared by every container of every
            // run, so an arm otherwise begins either against nothing or against the PREVIOUS arm's
            // transformed API — under which the pristine tree it was handed does not even compile. The
            // gate run inside this call is the proof, on this machine, that the environment is sound.
            prepareAndProveGateEnvironment(session.scope, rippleCase, projectDir)

            // Gold BEFORE the agent. The IDE runs in both arms — withMcp only controls whether the
            // AGENT may reach it — so the shell arm is measured without being given any access.
            val goldOutput = session.mcpSteroid.mcpExecuteCode(
                code = RippleOracleScripts.capture(rippleCase),
                reason = "Capture the pre-agent resolved reference set for the semantic-ripple oracle",
                taskId = "${rippleCase.target.kindId}-gold",
                timeout = 900,
            ).stdout
            // The consumer names the untransformed declaration by reflection too, so without this the
            // gold set is the repository's own count plus our overlay's — which is what the pilot's
            // tripwire caught the moment the consumer's imports began to resolve.
            val gold = parseSemanticGold(goldOutput, rippleCase.hiddenConsumerFiles())
            gold.checkTripwires(rippleCase)
            println("[RIPPLE] gold: ${gold.totalReferences} references " +
                "(${gold.countedReferences} graded, ${gold.importReferences} in imports), " +
                "${gold.files} files, ${gold.decoyReferences.size} decoys")

            val verifier = ArenaVerifier(session.scope, projectDir, testCase.buildSystem)
            // Before the snapshot, never after: the project's own formatter rewrites a patch file that
            // is not already in its style on the FIRST build anyone runs, and the resulting hash change
            // was charged to the agent as tampering with the oracle — flagged in build 1028521545's mcp
            // arm, whose transcript contains nothing but a Read of that file.
            verifier.normalizeFormattingBeforeSnapshot(SemanticRippleSpec.projectJdkVersion)
            val preAgentSnapshot = verifier.snapshotTestFiles(testCase.testPatch)
            // Kept so a tamper verdict — which voids the arm — can print what actually changed. Build
            // 1029045444 lost a perfect mcp arm to a hash change no transcript accounted for.
            val preAgentOracle = verifier.snapshotOracleContents(testCase.testPatch, testCase.failToPass)

            // The hook is registered on the very session the runner is about to drive, and only ever on
            // Claude: the recorder's seam is a Claude Code `--settings` file, and a recorder handed to
            // another agent would leave a run that is indistinguishable from an unrecorded one.
            val recorder = recorderFactory?.let { factory ->
                check(agentName == "claude") {
                    "the checkpoint recorder registers a Claude Code PostToolUse hook, and $agentName has " +
                        "no such seam — a recorded run of it would count nothing"
                }
                factory(session, projectDir).also { it.install(session.aiAgents.claude.asDockerClaudeSession()) }
            }

            val runner = ArenaTestRunner(container = session.scope, projectGuestDir = projectDir)
            val result = runner.runTest(
                testCase = testCase,
                agent = when (agentName) {
                    "claude" -> session.aiAgents.claude
                    "codex" -> session.aiAgents.codex
                    else -> error("Unknown agent: $agentName")
                },
                withMcp = withMcp,
                timeoutSeconds = SemanticRippleSpec.agentTimeoutSeconds,
                predeployedProjectDir = projectDir,
                logDir = session.runDirInContainer,
                promptBuilder = { dir -> promptFor(dir, withMcp) },
                // The #251 gate checks that the agent obeyed a first-call recipe only the dpaia brief
                // gives, and printed `base: <dir>` from it. This family's prompt cannot ask for that
                // without naming the very tool the experiment tests whether the agent finds; IDE use
                // is proven below by `usedMcpSteroid` instead.
                enforceFirstCallProjectMarker = false,
            )

            // The IDE that ran the gold capture can be dead by the time we get here — e.g. an agent
            // that OOM-killed it with a self-verification build outside our control. That is an
            // instrument failure, not evidence the agent got the transformation wrong, so it must be
            // legible as a LOST MEASUREMENT rather than as a graded (and possibly zero) result.
            val postOutput = try {
                session.mcpSteroid.mcpExecuteCode(
                    code = RippleOracleScripts.postcondition(rippleCase),
                    reason = "Grade the post-agent semantic state for the semantic-ripple oracle",
                    taskId = "${rippleCase.target.kindId}-post",
                    timeout = 900,
                ).stdout
            } catch (e: Exception) {
                println("[RIPPLE] MEASUREMENT LOST: could not reach the IDE to take the post-condition")
                println("[RIPPLE]   reading. The grade for this run is UNKNOWN, not zero — this is an")
                println("[RIPPLE]   instrument failure, not a verdict on the agent.")
                println("[RIPPLE]   gold: ${gold.totalReferences} references, ${gold.files} files, " +
                    "${gold.decoyReferences.size} decoys")
                println("[RIPPLE]   agent time: ${result.agentDurationMs / 1000}s, " +
                    "exit code: ${result.agentResult.exitCode}")
                println("[RIPPLE]   cause: ${e::class.simpleName}: ${e.message}")
                fail(
                    "[$agentName+$modeLabel] MEASUREMENT LOST: the post-condition read failed " +
                        "(${e::class.simpleName}: ${e.message}). This is an instrument failure — the " +
                        "IDE could not be reached to grade the run — and is not a verdict on the agent."
                )
            }
            val grade = parseSemanticPostcondition(
                postOutput,
                gold,
                hiddenConsumerFiles = rippleCase.hiddenConsumerFiles(),
                extraPredicates = rippleCase.target.extraPredicates(postOutput),
                expectedPostKey = rippleCase.target::expectedPostKey,
                importCountIsInvariant = rippleCase.target.importCountIsInvariant,
            )

            // The layer that covers every call site: a site the agent missed still names a declaration
            // that no longer exists in that form, so it cannot compile.
            val gate = runCompileGate(session.scope, rippleCase, projectDir)

            val verification = verifier.verify(
                failToPass = testCase.failToPass,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                testPatch = testCase.testPatch,
                preAgentSnapshot = preAgentSnapshot,
                // No regression baseline for ripple: the whole-reactor suite this would trigger costs
                // 40 minutes on Keycloak, is cut short by the harness timeout anyway, and was compared
                // against a synthetic empty snapshot that could never report a regression. The missed
                // call sites this scenario is about are caught by the compile gate instead, which sees
                // every one of them. Regressions are reported as UNKNOWN, which is what they are.
                baseline = null,
                mavenProjectSelector = rippleCase.gradingScopeSelector(),
                preAgentOracleContents = preAgentOracle,
                // This family's oracles resolve names reflectively, so they must read the tree the
                // agent produced and not the class files of the build before it: a moved or renamed
                // type leaves its old `.class` behind, Maven answers "Nothing to compile - all classes
                // are up to date", and `Class.forName` keeps finding a name the sources no longer
                // declare. Measured on build 1032547532, where the semantic oracle graded f1 = 1.0 with
                // P1_MOVED true and the compile gate PASS while the very same run graded 0/1 on
                // `MoveClassWideContractTest.oldFqnIsGone`.
                purgeScopedBuildOutput = true,
            )

            val metrics = collectRunMetrics(
                runDir = session.runDirInContainer,
                agentName = agentName,
                fallbackStdout = result.agentResult.stdout,
            )
            // A measurement-quality verdict, printed and persisted for every run: an mcp arm that
            // barely touched the IDE still ran, still graded, and still cost money — but its dollars
            // measure the overhead of HAVING the IDE rather than of using it, and an aggregate needs
            // to be able to hold them out. Counts come from the decoded transcript, the same source
            // the run summary already persists, so the log and the aggregate cannot disagree.
            val comparability = rippleArmComparability(
                withMcp = withMcp,
                decoded = metrics.decodedLogMetrics,
                toolStats = metrics.toolCallStats,
            )
            val success = gate.passed && verification.objectiveSuccess && grade.allPassed
            val rippleSummary = RippleRunSummary(
                comparability = comparability,
                compileGatePassed = gate.passed,
                allPredicatesPassed = grade.allPassed,
                rippleSuccess = success,
                recall = grade.recall,
                precision = grade.precision,
                f1 = grade.f1,
                missedSiteCount = grade.missedSites.size,
                overReachedDecoyCount = grade.overReachedDecoys.size,
                p1NoAliasAndNewNameDeclared = grade.p1NoAliasAndNewNameDeclared,
                p2AllSitesConverted = grade.p2AllSitesConverted,
                p3DecoysUnchanged = grade.p3DecoysUnchanged,
                p4Conserved = grade.p4Conserved,
                p6ImportCountUnchanged = grade.p6ImportCountUnchanged,
                extraPredicates = grade.extraPredicates,
                goldReferences = gold.totalReferences,
                goldFiles = gold.files,
                goldDecoys = gold.decoyReferences.size,
            )
            val record = DpaiaScenarioBaseTest.RunRecord(
                instanceId = testCase.instanceId,
                agentName = agentName,
                withMcp = withMcp,
                agentDurationMs = result.agentDurationMs,
                prewarmMs = 0L,
                exitCode = result.agentResult.exitCode,
                claimedFix = result.evaluation.agentClaimedFix,
                usedMcpSteroid = result.evaluation.usedMcpSteroid,
                summary = result.evaluation.agentSummary,
                tokenUsage = metrics.tokenUsage,
                testMetrics = metrics.testMetrics,
                decodedLogMetrics = metrics.decodedLogMetrics,
                verification = verification,
                runDirPath = session.runDirInContainer.absolutePath,
                rippleSummary = rippleSummary,
            )
            writeArenaRunSummary(
                testCase.instanceId,
                agentName,
                modeLabel,
                record,
                runDir = session.runDirInContainer,
            )

            println("[RIPPLE] ════════════════════════════════════════")
            println("[RIPPLE] $agentName+$modeLabel — ${testCase.instanceId}")
            println("[RIPPLE]   P1 no alias:     ${grade.p1NoAliasAndNewNameDeclared}")
            println("[RIPPLE]   P2 all sites:    ${grade.p2AllSitesConverted}")
            println("[RIPPLE]   P3 decoys kept:  ${grade.p3DecoysUnchanged}")
            println("[RIPPLE]   P4 conserved:    ${grade.p4Conserved}")
            println("[RIPPLE]   P6 imports kept: " + (grade.p6ImportCountUnchanged?.toString()
                ?: "n/a for this kind (delta ${grade.importReferenceDelta} is expected to move)"))
            grade.extraPredicates.toSortedMap().forEach { (id, passed) ->
                println("[RIPPLE]   $id: $passed")
            }
            println("[RIPPLE]   recall:          ${"%.4f".format(grade.recall)}")
            println("[RIPPLE]   precision:       ${"%.4f".format(grade.precision)}")
            println("[RIPPLE]   f1:              ${"%.4f".format(grade.f1)}")
            println("[RIPPLE]   missed sites:    ${grade.missedSites.size}")
            rippleFailedPredicateDetail(grade).forEach { println(it) }
            rippleStructuralPredicateDetail(postOutput).forEach { println(it) }
            println("[RIPPLE]   consumer refs excluded from conservation: ${grade.excludedConsumerReferences}")
            println("[RIPPLE]   import refs excluded from conservation:   " +
                "${grade.excludedImportReferences} (gold held ${gold.importReferences}, " +
                "delta ${grade.importReferenceDelta})")
            println("[RIPPLE]   over-reached:    ${grade.overReachedDecoys}")
            println("[RIPPLE]   compile gate:    ${if (gate.passed) "PASS" else "FAIL (exit ${gate.exitCode})"}")
            println("[RIPPLE]   verified FTP:    ${verification.classesPassed}/${verification.classesTotal}")
            rippleAgentCostLines(record.agentDurationMs, record.tokenUsage).forEach { println(it) }
            rippleToolUsageLines(comparability, metrics.decodedLogMetrics).forEach { println(it) }
            println("[RIPPLE]   SUCCESS:         $success")
            println("[RIPPLE] ════════════════════════════════════════")
            if (!gate.passed) {
                println("[RIPPLE] compile gate tail:\n${gate.tail}")
            }

            // Recorded runs only. The verdict is PRINTED and never asserted: a capture that misses the
            // representativeness band is a real measurement of this arm, and the operator — not the test
            // — decides whether to spend another Opus run. The patches are exported after the [RIPPLE]
            // block so the numbers are in the log even if a whole-tree git diff later fails.
            recorder?.let { rec ->
                val nActual = rec.stepCount()
                val steps = rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS.getValue(modeLabel))
                val admission = admitCapture(
                    reference = v3RenameMethodWideReference.getValue(modeLabel),
                    success = success,
                    steps = nActual,
                    seconds = result.agentDurationMs / 1000,
                    endContextTokens = metrics.tokenUsage?.totalTokens ?: 0L,
                    lastCheckpointStep = steps.last(),
                )
                println("[CHECKPOINT] n=$nActual steps=$steps admitted=${admission.admitted}")
                admission.reasons.forEach { println("[CHECKPOINT]   rejected: $it") }
                // Only the positions the trajectory really passed through. Asking for a later tag would
                // fail the build on a legitimately short run, which the admission verdict above already
                // reports — and would throw away the patches of the states that WERE captured.
                val reached = steps.filter { it <= nActual }
                if (reached != steps) {
                    println(
                        "[CHECKPOINT] the run ended at $nActual steps, so ${steps - reached} were never " +
                            "snapshotted — this capture cannot carry the full curve"
                    )
                }
                reached.forEach { step -> rec.exportPatch(step) }
                rec.exportMetadata(nActual)
            }

            // The run is a measurement, not a pass/fail on the agent's competence: a shell arm scoring
            // 0.0 recall is the expected positive-control outcome, not a broken test. Only an invalid
            // MEASUREMENT fails the test.
            assertTrue(!verification.failToPassTampered) {
                "[$agentName+$modeLabel] the agent modified the FAIL_TO_PASS file, so the grade measures " +
                    "tests it rewrote. Run invalid."
            }
            if (withMcp) {
                assertTrue(result.evaluation.usedMcpSteroid) {
                    "[$agentName+mcp] never called steroid_execute_code, so this is not an mcp-arm run"
                }
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }
}

/**
 * The KEYS behind a failed P2 or P3, not just how many there were.
 *
 * A bare count cannot be read. Build 1031008889 (rename-type wide, claude+mcp) printed
 * `missed sites: 1` at recall 0.9814 after three of the four keys that the type-level remapping was
 * built for started matching — and with the round stopped there was no second arm to compare against,
 * so nothing in the log could say whether that one site was a residual oracle artifact or a genuine
 * miss by the agent. A key answers it immediately: an artifact sits inside the transformed type's own
 * file, a real miss does not.
 *
 * Bounded, because P2 can fail with hundreds of sites in a shell arm that did nothing at all, and a
 * run summary is not a place to print a gold set. The cap keeps the shape readable and always states
 * how many were withheld.
 */
fun rippleFailedPredicateDetail(
    grade: SemanticPostconditionResult,
    limit: Int = 15,
): List<String> = buildList {
    if (grade.missedSites.isNotEmpty()) {
        add("[RIPPLE]   missed site keys (file|enclosing declaration|references the gold set held):")
        grade.missedSites.take(limit).forEach {
            add("[RIPPLE]     ${it.file}|${it.enclosingDeclaration}|${it.references}")
        }
        if (grade.missedSites.size > limit) {
            add("[RIPPLE]     ... ${grade.missedSites.size - limit} more not printed")
        }
    }
    if (grade.overReachedDecoys.isNotEmpty()) {
        add("[RIPPLE]   over-reached decoy keys:")
        grade.overReachedDecoys.take(limit).forEach { add("[RIPPLE]     $it") }
        if (grade.overReachedDecoys.size > limit) {
            add("[RIPPLE]     ... ${grade.overReachedDecoys.size - limit} more not printed")
        }
    }
}

/**
 * The KEYS behind a failed `P7_RECEIVER` or `P8_NO_SHIM`, read straight off the post-condition output.
 *
 * The same argument [rippleFailedPredicateDetail] makes for missed sites, applied to the rename
 * predicates: `P7_RECEIVER: false` alone cannot be told from an oracle artifact, and the family has
 * already spent one graded round on a predicate whose failing keys nobody could see. The owner of every
 * foreign reference and the qualified name of every surviving old-name declaration are what settle it.
 *
 * The receiver counts are printed whenever the script reported them, passing or not — a passing P7 over
 * three checked references is a different fact from a passing P7 over sixty, and only the printed
 * counts distinguish them. Kinds that emit no such lines (a move, a signature change) print nothing.
 */
fun rippleStructuralPredicateDetail(postOutput: String, limit: Int = 15): List<String> = buildList {
    val lines = postOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun keys(prefix: String): List<String> =
        lines.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).trim() }

    val checked = lines.firstOrNull { it.startsWith("POST_RECEIVER_CHECKED ") }
    if (checked != null) {
        fun count(prefix: String): String =
            lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim() ?: "?"
        add(
            "[RIPPLE]   receivers:       ${count("POST_RECEIVER_CHECKED ")} checked " +
                "(${count("POST_RECEIVER_FOREIGN ")} foreign, " +
                "${count("POST_RECEIVER_UNQUALIFIED ")} anonymous or local, " +
                "${count("POST_RECEIVER_UNRESOLVED ")} unresolved)"
        )
    }
    val foreign = keys("POST_RECEIVER_FOREIGN_SITE ")
    if (foreign.isNotEmpty()) {
        add("[RIPPLE]   foreign receiver owners (the new name resolves outside the target's hierarchy):")
        foreign.take(limit).forEach { add("[RIPPLE]     $it") }
        if (foreign.size > limit) add("[RIPPLE]     ... ${foreign.size - limit} more not printed")
    }
    val shims = keys("POST_SHIM_DECL ")
    if (shims.isNotEmpty()) {
        add("[RIPPLE]   surviving old-name declarations:")
        shims.take(limit).forEach { add("[RIPPLE]     $it") }
        if (shims.size > limit) add("[RIPPLE]     ... ${shims.size - limit} more not printed")
    }
}

/**
 * The cost of the run, as `[RIPPLE]` lines, next to the time it took.
 *
 * Collected metrics are unchanged — [TokenUsage] was already gathered and already written into the
 * run-summary JSON. That JSON used to land ONLY in `IdeTestFolders.testOutputDir`, which is not among
 * the published TeamCity artifacts, so the six-case smoke round's dollar figures had to be scraped back
 * out of raw agent NDJSON echoed into the build log; [writeArenaRunSummary] now also drops a copy into
 * the per-run directory that does get bundled. These lines stay regardless: cost is the headline of
 * this family — the separating case measured $1.21 with the IDE against $86.84 without — and a
 * headline a reader has to download an artifact to see is a reporting gap of its own.
 *
 * A missing [TokenUsage] prints as UNAVAILABLE rather than as a zero: no usage event in the agent's
 * output means the figure is unknown, and a printed 0 would read as a free run. A null [TokenUsage.
 * costUsd] is the normal Codex case — that CLI reports no dollar figure, and deriving one from a
 * hardcoded price would silently go stale — so it says so instead of inventing a number.
 */
fun rippleAgentCostLines(agentDurationMs: Long, tokens: TokenUsage?): List<String> {
    fun line(label: String, value: String) = "[RIPPLE]   ${(label + ":").padEnd(17)}$value"
    val time = line("agent time", "${agentDurationMs / 1000}s")
    if (tokens == null) {
        return listOf(
            time,
            line("tokens/cost", "UNAVAILABLE — the agent's output carried no usage event"),
        )
    }
    return listOf(
        time,
        line(
            "tokens in/out",
            "${tokens.inputTokens}/${tokens.outputTokens} (total ${tokens.totalTokens}, " +
                "cache read ${tokens.cacheReadTokens}, cache created ${tokens.cacheCreationTokens})",
        ),
        line("turns", tokens.numTurns?.toString() ?: "not reported by this agent CLI"),
        line(
            "cost",
            tokens.costUsd?.let { "$" + String.format(Locale.ROOT, "%.4f", it) }
                ?: "not reported by this agent CLI",
        ),
    )
}
