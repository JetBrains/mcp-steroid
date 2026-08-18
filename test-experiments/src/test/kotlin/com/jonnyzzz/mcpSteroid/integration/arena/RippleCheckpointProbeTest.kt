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
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Which single cell of the pilot's grid one probe build measures.
 *
 * A build cannot infer its own coordinates: the 50 probe builds differ ONLY by these three numbers, so
 * they arrive from outside and every one of them is validated. A silently defaulted coordinate would
 * not fail a build — it would measure the same cell fifty times and publish a curve made of one point.
 */
data class ProbeCoordinates(val arm: String, val checkpoint: Int, val replicate: Int)

/**
 * Read one probe cell's coordinates, rejecting anything the pilot did not capture.
 *
 * [arm] must name a captured arm and [index] an existing snapshot position — a fourth arm or a sixth
 * checkpoint has no committed state to start from, and discovering that after the container, the clone
 * and the Maven import would waste most of a build. [replicate] is bounded too, because a replicate
 * outside `1..RIPPLE_CHECKPOINT_REPLICATES` means the operator queued more runs than the aggregator
 * will ever fold into a `V`.
 */
fun probeCoordinates(arm: String?, index: String?, replicate: String?): ProbeCoordinates {
    require(arm != null && arm in RIPPLE_EXPECTED_STEPS.keys) {
        "the probe arm must be one of ${RIPPLE_EXPECTED_STEPS.keys}, got '$arm' — no other arm was captured"
    }
    val checkpointCount = rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS.getValue(arm)).size
    val checkpoint = index?.toIntOrNull()
    require(checkpoint != null && checkpoint in 1..checkpointCount) {
        "the checkpoint index must be in 1..$checkpointCount, got '$index' — the capture took exactly " +
            "$checkpointCount snapshots"
    }
    val replicateNumber = replicate?.toIntOrNull()
    require(replicateNumber != null && replicateNumber in 1..RIPPLE_CHECKPOINT_REPLICATES) {
        "the replicate must be in 1..$RIPPLE_CHECKPOINT_REPLICATES, got '$replicate' — the aggregator " +
            "folds exactly $RIPPLE_CHECKPOINT_REPLICATES runs into one readiness value"
    }
    return ProbeCoordinates(arm = arm, checkpoint = checkpoint, replicate = replicateNumber)
}

/**
 * A bare Haiku, handed the tree as one recorded Opus trajectory left it, asked to finish the job.
 *
 * `V(s_i)` — the fraction of such runs that finish — is the pilot's whole measurement, so the probe is
 * an exact copy of [RippleScenarioBaseTest]'s arm flow with six deliberate differences, and grading is
 * NOT among them: the same oracle post-condition, the same scoped compile gate and the same FAIL_TO_PASS
 * verification decide `Y`, because a probe graded any other way could not be compared to the capture it
 * came from.
 *
 * 1. The model is a haiku, asserted rather than assumed.
 * 2. The checkpoint patch is applied — AFTER the gold capture, see [probe] for why that order is
 *    load-bearing.
 * 3. No MCP in EITHER arm's probe: `V` must measure how far along the SOLUTION the state is, not how
 *    good the probe's own tooling is, so the same bare agent reads every state.
 * 4. The prompt is [buildCheckpointProbePrompt], which never names the arm the state came from.
 * 5. No `usedMcpSteroid` assertion — a bare probe is expected never to touch the IDE.
 * 6. One extra log line, `[CHECKPOINT-PROBE] … Y=<0|1>`, which is what the aggregator reads.
 */
class RippleCheckpointProbeTest {

    @Test
    fun `probe coordinates come from system properties and reject an unknown checkpoint`() {
        assertEquals(ProbeCoordinates("mcp", 3, 2), probeCoordinates("mcp", "3", "2"))
        assertThrows(IllegalArgumentException::class.java) { probeCoordinates("mcp", "6", "1") }
        assertThrows(IllegalArgumentException::class.java) { probeCoordinates("shell", "1", "1") }
    }

    /**
     * The committed states, checked as ARTIFACTS rather than as a count.
     *
     * The arm directories are asserted to exist, every patch found in them must be a readable diff, and
     * none of them may touch the FAIL_TO_PASS oracle — a capture whose agent had rewritten the test
     * that grades it would hand every probe a rigged starting state, and the tamper check inside a
     * probe run cannot see it because the probe's own snapshots are taken after the patch.
     *
     * It deliberately does NOT assert five patches per arm. Before the capture runs land, the
     * directories hold only their README, and a hard count here would either be a red build for weeks
     * or — far worse — invite a skip-on-missing branch that would keep passing after a capture silently
     * failed to produce a state. The five-patch requirement lives where it can be checked against a
     * real need: [probe]'s own precondition, which fails loudly when the state it must start from is
     * absent. What this test guarantees at every point in time is that whatever IS committed is a valid
     * checkpoint at a valid schedule position.
     */
    @Test
    fun `every committed checkpoint patch is a readable diff that spares the oracle`() {
        val oracleFileNames = RippleCases.renameMethodWide.dpaiaCase().failToPass
            .map { it.substringAfterLast('.') + ".java" }

        RIPPLE_EXPECTED_STEPS.keys.forEach { arm ->
            val dir = checkpointResourceDir(arm)
            assertTrue(dir.isDirectory) {
                "$dir is missing. The probe reads its starting states from there, so the layout is part " +
                    "of the instrument and not something a capture run creates on the fly."
            }
            val scheduledNames = rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS.getValue(arm))
                .map { step -> RippleCheckpointRecorder.patchFileName(step) }
            val patches = patchFilesIn(dir)
            println("[CHECKPOINT-RESOURCES] $arm: ${patches.size} committed patch(es) of " +
                "${scheduledNames.size} scheduled — ${patches.map { it.name }}")

            patches.forEach { patch ->
                assertTrue(patch.name in scheduledNames) {
                    "${patch.name} is not one of the $arm arm's snapshot positions $scheduledNames, so " +
                        "no probe cell will ever address it"
                }
                val text = patch.readText()
                // A blank patch is a real measurement — the first checkpoint of a trajectory that had
                // not written anything yet — and must not be mistaken for a broken export, which
                // RippleCheckpointRecorder.exportPatch rejects at capture time instead.
                assertTrue(text.contains("diff --git") || text.isBlank()) { "${patch.name} is not a diff" }
                oracleFileNames.forEach { oracle ->
                    assertFalse(text.contains(oracle)) {
                        "${patch.name} touches the FAIL_TO_PASS oracle — the capture run tampered"
                    }
                }
            }
        }
    }

    /**
     * One probe cell, end to end.
     *
     * **The order below is the instrument, not a style choice.**
     *
     * 1. The gold reference set is captured on the PRISTINE tree, before the checkpoint patch. Gold is
     *    the set of references the transformation has to reach, and the checkpoint state has already
     *    converted some of them; capturing gold after the patch would silently shrink the reference set
     *    to whatever was still untouched, and every probe would then be graded against an easier task
     *    the further along its starting state was — turning `V` into a measurement of the checkpoint's
     *    depth rather than of the agent's ability to finish.
     * 2. Only then is the patch applied, and the IDE forced to re-ingest the changed files.
     * 3. The tamper snapshots are taken AFTER the patch. They exist to catch the PROBE rewriting the
     *    oracle; the capture's own contribution to those files is checked separately, by
     *    `every committed checkpoint patch is a readable diff that spares the oracle`. Taking them
     *    before the patch would charge every probe with whatever the capture did.
     *
     * A patch that fails to apply aborts the run through [GitDriver.applyPatch]'s non-zero exit check.
     * That is an instrument failure and must never be reported as `Y=0`: a `Y=0` says the agent could not
     * finish from that state, and an unapplied patch means it was never given that state at all.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun probe() {
        val coordinates = probeCoordinates(
            System.getProperty(PROBE_ARM_PROPERTY),
            System.getProperty(PROBE_INDEX_PROPERTY),
            System.getProperty(PROBE_REPLICATE_PROPERTY),
        )
        val checkpoint = loadCheckpoint(coordinates)
        println(
            "[CHECKPOINT-PROBE] cell arm=${coordinates.arm} checkpoint=${coordinates.checkpoint} " +
                "step=${checkpoint.step} position=${checkpoint.formattedPosition()} " +
                "replicate=${coordinates.replicate} patch=${checkpoint.patchText.length} chars"
        )

        val rippleCase = RippleCases.renameMethodWide
        val testCase = rippleCase.dpaiaCase()
        val agentName = "claude"
        val modeLabel = "probe-${coordinates.arm}-c${coordinates.checkpoint}-r${coordinates.replicate}"
        // The resolved model can only be steered through this property (DockerClaudeSession reads it when
        // the agent is created), so a probe DEFAULTS it to the pilot's cheap model instead of trusting
        // every one of 50 build configurations to pass it. An explicit value is left alone and judged by
        // the assertion below — that is what makes a wrong one loud instead of expensive. Restored in the
        // `finally`, because a Gradle test JVM is shared and a capture run in the same JVM must not
        // inherit a haiku.
        val previousModel = System.getProperty(CLAUDE_MODEL_PROPERTY)
        if (previousModel == null) System.setProperty(CLAUDE_MODEL_PROPERTY, PROBE_MODEL)
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ripple-checkpoint-probe-$modeLabel",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = SemanticRippleSpec.cloneUrl,
                    repoOwnerAndName = SemanticRippleSpec.repoOwnerAndName,
                    baseCommit = testCase.baseCommit,
                    testPatch = testCase.testPatch,
                    displayName = testCase.instanceId,
                    buildSystem = testCase.buildSystem,
                ),
                // Bare in BOTH arms: the probe measures the STATE, so giving one arm's probe the IDE
                // would make its readiness a statement about the probe's tooling instead.
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                timeoutMillis = SemanticRippleSpec.projectReadyTimeoutMs,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                requireCleanCompile = false,
            )

            val projectDir = session.intellijDriver.getGuestProjectDir()

            val model = session.aiAgents.claude.asDockerClaudeSession().model
            println("[CHECKPOINT-PROBE] resolved agent model: $model")
            assertTrue(model.contains("haiku", ignoreCase = true)) {
                "The probe resolved the model '$model'. Every probe cell must run on a haiku — 50 cells " +
                    "on an Opus is the pilot's entire budget spent on the cheapest part of it. Set " +
                    "-D$CLAUDE_MODEL_PROPERTY (or CLAUDE_MODEL) to a haiku, or leave it unset to get " +
                    "$PROBE_MODEL."
            }

            prepareAndProveGateEnvironment(session.scope, rippleCase, projectDir)

            // ORDER STEP 1 — gold on the PRISTINE tree. See this method's kdoc: taking it after the
            // patch would shrink the reference set by exactly the work the checkpoint had already done.
            val goldOutput = session.mcpSteroid.mcpExecuteCode(
                code = RippleOracleScripts.capture(rippleCase),
                reason = "Capture the pre-agent resolved reference set for the semantic-ripple oracle",
                taskId = "${rippleCase.target.kindId}-gold",
                timeout = 900,
            ).stdout
            val gold = parseSemanticGold(goldOutput, rippleCase.hiddenConsumerFiles())
            gold.checkTripwires(rippleCase)
            println("[RIPPLE] gold: ${gold.totalReferences} references " +
                "(${gold.countedReferences} graded, ${gold.importReferences} in imports), " +
                "${gold.files} files, ${gold.decoyReferences.size} decoys")

            // ORDER STEP 2 — the tree becomes the recorded intermediate state. A non-zero `git apply`
            // aborts here rather than grading a run that never received the state.
            GitDriver(session.scope).applyPatch(projectDir, checkpoint.patchText)
            // The IDE indexed the pristine tree, and the patch was written by a process it does not
            // watch. Every steroid_execute_code awaits a full VFS refresh before it compiles
            // (CodeEvalManager), so this call is how the IDE re-ingests the checkpoint before anything
            // reads the tree through PSI.
            session.mcpSteroid.mcpExecuteCode(
                code = CHECKPOINT_VFS_REFRESH_SCRIPT,
                reason = "Re-ingest the checkpoint patch into the IDE's virtual file system",
                taskId = "${rippleCase.target.kindId}-checkpoint-refresh",
                timeout = 300,
            )

            val verifier = ArenaVerifier(session.scope, projectDir, testCase.buildSystem)
            verifier.normalizeFormattingBeforeSnapshot(SemanticRippleSpec.projectJdkVersion)
            // ORDER STEP 3 — tamper detection is scoped to the PROBE, so the baseline is the patched tree.
            val preAgentSnapshot = verifier.snapshotTestFiles(testCase.testPatch)
            val preAgentOracle = verifier.snapshotOracleContents(testCase.testPatch, testCase.failToPass)

            val runner = ArenaTestRunner(container = session.scope, projectGuestDir = projectDir)
            val result = runner.runTest(
                testCase = testCase,
                agent = session.aiAgents.claude,
                withMcp = false,
                // The same budget the graded arms get: a probe cut shorter would report a readiness that
                // is partly a statement about its own clock.
                timeoutSeconds = SemanticRippleSpec.agentTimeoutSeconds,
                predeployedProjectDir = projectDir,
                logDir = session.runDirInContainer,
                promptBuilder = { dir -> buildCheckpointProbePrompt(rippleCase, dir) },
                enforceFirstCallProjectMarker = false,
            )

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
                println("[RIPPLE]   cause: ${e::class.simpleName}: ${e.message}")
                // LOST, never Y=0: the aggregator's verdict pattern matches Y=<0|1> only, so an
                // ungraded cell stays out of V instead of biasing it downwards.
                println(checkpoint.probeLine(coordinates, verdict = "LOST"))
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

            val gate = runCompileGate(session.scope, rippleCase, projectDir)

            val verification = verifier.verify(
                failToPass = testCase.failToPass,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                testPatch = testCase.testPatch,
                preAgentSnapshot = preAgentSnapshot,
                baseline = null,
                mavenProjectSelector = rippleCase.gradingScopeSelector(),
                preAgentOracleContents = preAgentOracle,
                purgeScopedBuildOutput = true,
            )

            val metrics = collectRunMetrics(
                runDir = session.runDirInContainer,
                agentName = agentName,
                fallbackStdout = result.agentResult.stdout,
            )
            val comparability = rippleArmComparability(
                withMcp = false,
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
                withMcp = false,
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
            // The mode label carries the cell, so 50 probe summaries never overwrite each other or the
            // graded arm's summary for the same instance.
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
            println("[RIPPLE]   compile gate:    ${if (gate.passed) "PASS" else "FAIL (exit ${gate.exitCode})"}")
            println("[RIPPLE]   verified FTP:    ${verification.classesPassed}/${verification.classesTotal}")
            rippleAgentCostLines(record.agentDurationMs, record.tokenUsage).forEach { println(it) }
            rippleToolUsageLines(comparability, metrics.decodedLogMetrics).forEach { println(it) }
            println("[RIPPLE]   SUCCESS:         $success")
            println("[RIPPLE] ════════════════════════════════════════")
            if (!gate.passed) {
                println("[RIPPLE] compile gate tail:\n${gate.tail}")
            }

            println(checkpoint.probeLine(coordinates, verdict = "Y=${if (success) 1 else 0}"))

            // Same as the graded arms: only an invalid MEASUREMENT fails the test. A probe that could
            // not finish the task is the pilot's expected outcome at the early checkpoints.
            assertTrue(!verification.failToPassTampered) {
                "[$agentName+$modeLabel] the probe modified the FAIL_TO_PASS file, so the grade measures " +
                    "tests it rewrote. Run invalid."
            }
        } finally {
            if (previousModel == null) System.clearProperty(CLAUDE_MODEL_PROPERTY)
            lifetime.closeAllStacks()
        }
    }

    companion object {
        /** The three coordinates `test-experiments/build.gradle.kts` forwards from TeamCity. */
        const val PROBE_ARM_PROPERTY: String = "ripple.checkpoint.arm"
        const val PROBE_INDEX_PROPERTY: String = "ripple.checkpoint.index"
        const val PROBE_REPLICATE_PROPERTY: String = "ripple.checkpoint.replicate"

        /** The property [DockerClaudeSession] resolves its model from. */
        const val CLAUDE_MODEL_PROPERTY: String = "claude.model"

        /**
         * The pilot's probe model. Cheap on purpose: `V` is defined as the readiness of a state to a
         * WEAKER continuation, and 50 cells at Opus prices would cost more than the experiment answers.
         */
        const val PROBE_MODEL: String = "claude-haiku-4-5"

        /**
         * The IDE-side half of applying a checkpoint: this script's own body barely matters, its VALUE is
         * that every `steroid_execute_code` blocks on a full VFS refresh before compiling, so the files
         * `git apply` wrote outside IntelliJ's watcher are re-read here and not during grading.
         */
        val CHECKPOINT_VFS_REFRESH_SCRIPT: String = """
            val base = project.basePath ?: error("the project has no base path")
            println("[CHECKPOINT-PROBE] the IDE re-ingested ${'$'}base after the checkpoint patch")
            "refreshed"
        """.trimIndent()
    }
}

/** The state one probe starts from, together with where along the capture's trajectory it was taken. */
private data class LoadedCheckpoint(val step: Int, val position: Double, val patchText: String) {
    fun formattedPosition(): String = String.format(Locale.ROOT, "%.4f", position)

    /**
     * The single line [parseProbeVerdicts] reads. Built in one place so the shape the aggregator's regex
     * requires cannot drift between the graded and the lost path.
     */
    fun probeLine(coordinates: ProbeCoordinates, verdict: String): String =
        "[CHECKPOINT-PROBE] arm=${coordinates.arm} checkpoint=${coordinates.checkpoint} step=$step " +
            "position=${formattedPosition()} replicate=${coordinates.replicate} $verdict"
}

/**
 * Where the committed checkpoints of `rename-method-wide` live, per arm.
 *
 * A plain relative path because Gradle runs a test with the module directory as its working directory,
 * and the probe needs the SOURCE tree rather than the processed resources: a patch is data an operator
 * copies in from a capture run's artifacts, and reading it where it was committed keeps that copy
 * verifiable with `git diff`.
 */
private fun checkpointResourceDir(arm: String): File =
    File("src/test/resources/ripple-checkpoints/rename-method-wide/$arm")

/** Every committed patch of one arm, in schedule order (`step-2` before `step-11`, not lexicographic). */
private fun patchFilesIn(dir: File): List<File> =
    (dir.listFiles { file -> file.isFile && file.name.endsWith(".patch") } ?: emptyArray())
        .sortedBy { it.nameWithoutExtension.substringAfter("step-").toIntOrNull() ?: Int.MAX_VALUE }

/**
 * Load the one state a cell probes, refusing to run at all when it is not there.
 *
 * Fails loudly rather than skipping, in both directions: a missing patch means the capture never reached
 * that position, and a missing `checkpoints.json` means nobody can say what fraction of a trajectory the
 * patch represents — a probe published without that number would report a readiness at an unknown place
 * on the curve.
 */
private fun loadCheckpoint(coordinates: ProbeCoordinates): LoadedCheckpoint {
    val dir = checkpointResourceDir(coordinates.arm)
    val steps = rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS.getValue(coordinates.arm))
    val committed = patchFilesIn(dir)
    check(committed.size == steps.size) {
        "${dir.absolutePath} holds ${committed.size} patch(es) ${committed.map { it.name }} but the " +
            "${coordinates.arm} arm's curve needs all ${steps.size} of $steps. Run the capture for this " +
            "arm and commit its admitted step-*.patch files plus " +
            "${RippleCheckpointRecorder.METADATA_FILE_NAME} before queueing probe cells."
    }

    val step = steps[coordinates.checkpoint - 1]
    val patch = dir.resolve(RippleCheckpointRecorder.patchFileName(step))
    check(patch.isFile) { "no committed state for checkpoint ${coordinates.checkpoint} at ${patch.absolutePath}" }

    val metadataFile = dir.resolve(RippleCheckpointRecorder.METADATA_FILE_NAME)
    check(metadataFile.isFile) {
        "${metadataFile.absolutePath} is missing, so the measured step count this checkpoint's position " +
            "is normalized by is unknown"
    }
    val metadata = Json.parseToJsonElement(metadataFile.readText()).jsonObject
    val entry = metadata["checkpoints"]?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it["step"]?.jsonPrimitive?.content?.toIntOrNull() == step }
        ?: error(
            "${metadataFile.absolutePath} describes no checkpoint at step $step — the committed patches " +
                "and the metadata come from different capture runs"
        )
    val position = entry["position"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: error("${metadataFile.absolutePath} has no numeric position for step $step")

    return LoadedCheckpoint(step = step, position = position, patchText = patch.readText())
}
