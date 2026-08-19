/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.asDockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
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
 * [arm] must name a captured arm and [index] one of the pilot's checkpoint ordinals — a fourth arm or a
 * sixth checkpoint has no committed state to start from, and discovering that after the container, the
 * clone and the Maven import would waste most of a build. WHICH step an ordinal refers to is not known
 * here: the positions belong to the capture run and are read from its `checkpoints.json` in
 * [loadCheckpoint]. [replicate] is bounded too, because a replicate outside
 * `1..RIPPLE_CHECKPOINT_REPLICATES` means the operator queued more runs than the aggregator will ever
 * fold into a `V`.
 */
fun probeCoordinates(arm: String?, index: String?, replicate: String?): ProbeCoordinates {
    require(arm != null && arm in RIPPLE_CHECKPOINT_ARMS) {
        "the probe arm must be one of $RIPPLE_CHECKPOINT_ARMS, got '$arm' — no other arm was captured"
    }
    val checkpoint = index?.toIntOrNull()
    require(checkpoint != null && checkpoint in 1..RIPPLE_CHECKPOINT_COUNT) {
        "the checkpoint index must be in 1..$RIPPLE_CHECKPOINT_COUNT, got '$index' — the pilot measures " +
            "$RIPPLE_CHECKPOINT_COUNT checkpoints per arm"
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
 * `V(s_i)` — the fraction of such runs that finish — is the pilot's whole measurement, so the probe runs
 * the SAME flow the graded case runs: it holds an anonymous [DpaiaScenarioBaseTest] for
 * [RippleCheckpointCase.INSTANCE_ID] and drives it through [DpaiaScenarioBaseTest.runAgent], reaching
 * into it only through [DpaiaRunSeams]. Grading is deliberately NOT one of the differences —
 * [ArenaVerifier.verify] decides `Y` exactly as it decides a graded arm, over the same FAIL_TO_PASS
 * classes, the same measured whole-suite regression baseline and the same tamper check, because a probe
 * graded any other way could not be compared to the capture it came from.
 *
 * The five differences, all of them inside the seams:
 *
 * 1. The model is a haiku, asserted rather than assumed.
 * 2. The checkpoint patch is applied — after the pre-agent baseline and before the tamper snapshot, see
 *    [DpaiaRunSeams.prepareTree] for why that position is load-bearing.
 * 3. No MCP in EITHER arm's probe: `V` must measure how far along the SOLUTION the state is, not how
 *    good the probe's own tooling is, so the same bare agent reads every state. `withMcp = false` is
 *    what the flow already means by that — `AiMode.NONE` plus `McpConnectionMode.None`.
 * 4. The brief is [buildDpaiaCheckpointProbePrompt], which never names the arm the state came from.
 * 5. One extra log line, `[CHECKPOINT-PROBE] … Y=<0|1>`, which is what the aggregator reads.
 *
 * Nothing re-ingests the patched tree into the IDE, and nothing has to: the probe's agent is bare and the
 * grade comes from Maven over the files on disk, so no step of this flow reads the tree through PSI. The
 * ripple probe needed that refresh because its oracle was an IDE query.
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
     * The arm directories of every case the layout serves are asserted to exist and every patch found in
     * them must be a readable diff that its own `checkpoints.json` accounts for.
     *
     * It deliberately does NOT assert five patches per arm. Before the capture runs land, the
     * directories hold only their README, and a hard count here would either be a red build for weeks
     * or — far worse — invite a skip-on-missing branch that would keep passing after a capture silently
     * failed to produce a state. The five-patch requirement lives where it can be checked against a
     * real need: [probe]'s own precondition, which fails loudly when the state it must start from is
     * absent. What this test guarantees at every point in time is that whatever IS committed is a valid
     * checkpoint of one run — see [checkpointResourceProblems] for the mismatches it refuses.
     *
     * The FAIL_TO_PASS oracle is NOT inspected here, and that is a deliberate move rather than a gap:
     * the DPAIA case's oracle class names live in the dataset this module downloads at run time, and a
     * unit test must not depend on the network to be able to reject a rigged state. The check moved to
     * [probe], which loads the case anyway and refuses a patch touching an oracle file before it spends
     * a container on it.
     */
    @Test
    fun `every committed checkpoint patch is a readable diff its metadata accounts for`() {
        RIPPLE_CHECKPOINT_CASE_DIRS.forEach { caseDir ->
            RIPPLE_CHECKPOINT_ARMS.forEach { arm ->
                val dir = checkpointResourceDir(caseDir, arm)
                assertTrue(dir.isDirectory) {
                    "$dir is missing. The probe reads its starting states from there, so the layout is " +
                        "part of the instrument and not something a capture run creates on the fly."
                }
                val patches = patchFilesIn(dir)
                println("[CHECKPOINT-RESOURCES] $caseDir/$arm: ${patches.size} committed patch(es) of " +
                    "$RIPPLE_CHECKPOINT_COUNT — ${patches.map { it.name }}")
                val problems = checkpointResourceProblems(
                    location = dir.path,
                    patchFileNames = patches.map { it.name },
                    metadataJson = dir.resolve(RippleCheckpointRecorder.METADATA_FILE_NAME)
                        .takeIf { it.isFile }?.readText(),
                )
                assertTrue(problems.isEmpty()) { problems.joinToString("\n") }

                patches.forEach { patch ->
                    val text = patch.readText()
                    // A blank patch is a real measurement — the first checkpoint of a trajectory that
                    // had not written anything yet — and must not be mistaken for a broken export, which
                    // RippleCheckpointRecorder.exportPatch rejects at capture time instead.
                    assertTrue(text.contains("diff --git") || text.isBlank()) { "${patch.name} is not a diff" }
                }
            }
        }
    }

    /**
     * An empty arm directory is a valid state of this instrument, and a half-copied one is not.
     *
     * The pilot has not captured on this case yet, so "no patches committed" must stay green — the
     * alternative was a red build for weeks, which trains everyone to ignore it. Everything else about
     * the pair (patches, metadata) is refused, because patches from one capture next to metadata from
     * another describe a trajectory that never existed: the metadata is the ONLY source of `n`, so every
     * position a probe publishes would be normalized by the wrong run's length.
     */
    @Test
    fun `nothing committed yet is a valid checkpoint directory`() {
        assertEquals(emptyList<String>(), checkpointResourceProblems("dir", emptyList(), null))
    }

    @Test
    fun `patches without their metadata are refused`() {
        val problems = checkpointResourceProblems("dir", listOf("step-2.patch"), null)
        assertEquals(1, problems.size) { problems.toString() }
        assertTrue(problems.single().contains(RippleCheckpointRecorder.METADATA_FILE_NAME)) {
            problems.toString()
        }
    }

    @Test
    fun `metadata without the patches it describes is refused`() {
        val problems = checkpointResourceProblems("dir", emptyList(), metadataJson(steps = listOf(2, 6)))
        assertEquals(1, problems.size) { problems.toString() }
    }

    @Test
    fun `a patch set the metadata does not account for is refused`() {
        val problems = checkpointResourceProblems(
            location = "dir",
            patchFileNames = listOf("step-2.patch", "step-9.patch"),
            metadataJson = metadataJson(steps = listOf(2, 6)),
        )
        assertEquals(1, problems.size) { problems.toString() }
        assertTrue(problems.single().contains("step-6.patch") && problems.single().contains("step-9.patch")) {
            problems.toString()
        }
    }

    @Test
    fun `a file that is not a step patch is refused`() {
        val problems = checkpointResourceProblems("dir", listOf("final.patch"), metadataJson(listOf(2)))
        assertTrue(problems.any { it.contains("final.patch") }) { problems.toString() }
    }

    @Test
    fun `a capture whose patches match its metadata is accepted`() {
        assertEquals(
            emptyList<String>(),
            checkpointResourceProblems(
                location = "dir",
                patchFileNames = listOf("step-2.patch", "step-6.patch"),
                metadataJson = metadataJson(steps = listOf(2, 6)),
            ),
        )
    }

    /**
     * `Y=0` and `LOST` are different measurements, and the aggregator's regex is what separates them:
     * `Y=([01])` matches a graded cell only, so an instrument failure stays out of `V` instead of
     * pulling it down. This asserts the whole round trip — the line the probe prints, read back by
     * [parseProbeVerdicts] — because the two have to agree and they live in different files.
     */
    @Test
    fun `a lost measurement never reaches the aggregator as a zero`() {
        val coordinates = ProbeCoordinates("mcp", checkpoint = 3, replicate = 2)
        val graded = checkpointProbeLine(coordinates, step = 14, position = 0.4375, verdict = checkpointProbeVerdict(true))
        val failed = checkpointProbeLine(coordinates, step = 14, position = 0.4375, verdict = checkpointProbeVerdict(false))
        val lost = checkpointProbeLine(coordinates, step = 14, position = 0.4375, verdict = checkpointProbeVerdict(null))

        assertEquals(
            listOf(ProbeVerdict("mcp", 3, 14, 0.4375, 2, success = true)),
            parseProbeVerdicts(graded),
        )
        assertEquals(
            listOf(ProbeVerdict("mcp", 3, 14, 0.4375, 2, success = false)),
            parseProbeVerdicts(failed),
        )
        assertTrue(lost.contains("LOST")) { lost }
        assertEquals(emptyList<ProbeVerdict>(), parseProbeVerdicts(lost)) {
            "an ungraded cell must not be foldable into V at all"
        }
    }

    private fun metadataJson(steps: List<Int>): String = RippleCheckpointRecorder.metadataJson(
        case = RippleCheckpointCase.INSTANCE_ID,
        arm = "mcp",
        model = "claude-opus-5",
        plan = CheckpointPlan(
            n = 30,
            checkpoints = steps.mapIndexed { index, step ->
                CheckpointSelection(
                    index = index + 1,
                    nominalStep = step,
                    step = step,
                    position = step / 30.0,
                )
            },
            corrections = emptyList(),
        ),
    )

    /**
     * One probe cell, end to end: the recorded state, restored into the case's own run flow.
     *
     * Everything the cell needs is decided BEFORE the container exists — the coordinates, the committed
     * patch and its position — so a mis-addressed cell costs seconds instead of a whole build. The rest
     * is [DpaiaScenarioBaseTest.runAgent], unmodified, with the seams doing the five things a probe does
     * differently; see this class's KDoc for the list and [DpaiaRunSeams] for where each one attaches.
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

        // The resolved model can only be steered through this property (DockerClaudeSession reads it when
        // the agent is created), so a probe DEFAULTS it to the pilot's cheap model instead of trusting
        // every one of 50 build configurations to pass it. An explicit value is left alone and judged by
        // the assertion in the tree seam — that is what makes a wrong one loud instead of expensive.
        // Restored in the `finally`, because a Gradle test JVM is shared and a capture run in the same
        // JVM must not inherit a haiku.
        val previousModel = System.getProperty(CLAUDE_MODEL_PROPERTY)
        if (previousModel == null) System.setProperty(CLAUDE_MODEL_PROPERTY, PROBE_MODEL)
        try {
            checkpointProbeScenario(coordinates, checkpoint).runAgent("claude", withMcp = false)
        } finally {
            if (previousModel == null) System.clearProperty(CLAUDE_MODEL_PROPERTY)
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
    }
}

/**
 * The probed cell as an ANONYMOUS [DpaiaScenarioBaseTest], so JUnit can never discover it.
 *
 * Anonymity is the mechanism, not an accident: Jupiter's `IsPotentialTestContainer` rejects anonymous
 * classes, so the four graded `@Test` methods this object inherits cannot be collected and run
 * alongside the probe — which would spend four full arena runs per probe build. A named subclass, even
 * a private top-level one (Kotlin compiles it to a package-private JVM class that JUnit's private-class
 * filter does NOT exclude), would be picked up by classpath scanning.
 */
private fun checkpointProbeScenario(
    coordinates: ProbeCoordinates,
    checkpoint: LoadedCheckpoint,
): DpaiaScenarioBaseTest = object : DpaiaScenarioBaseTest() {
    override val instanceId: String = RippleCheckpointCase.INSTANCE_ID

    override val seams: DpaiaRunSeams = object : DpaiaRunSeams {
        override fun prepareTree(session: IntelliJContainer, projectDir: String, testCase: DpaiaTestCase) {
            // The earliest seam that can see the resolved agent, and the last moment before the run is
            // paid for. A probe cell that came up on an Opus would measure the right state with the
            // wrong continuation — and 50 of them are the pilot's entire budget.
            val model = session.aiAgents.claude.asDockerClaudeSession().model
            println("[CHECKPOINT-PROBE] resolved agent model: $model")
            check(model.contains("haiku", ignoreCase = true)) {
                "The probe resolved the model '$model'. Every probe cell must run on a haiku — 50 cells " +
                    "on an Opus is the pilot's entire budget spent on the cheapest part of it. Set " +
                    "-D${RippleCheckpointProbeTest.CLAUDE_MODEL_PROPERTY} (or CLAUDE_MODEL) to a haiku, " +
                    "or leave it unset to get ${RippleCheckpointProbeTest.PROBE_MODEL}."
            }

            // A capture whose agent had rewritten the test that grades it would hand every probe of that
            // arm a rigged starting state, and the probe's own tamper check cannot see it: the pre-agent
            // snapshot below is taken AFTER this patch, on purpose, so the probe is blamed for its own
            // edits only. This is where the capture's contribution is judged, against the oracle files of
            // the very case the run grades.
            val oracleFiles = failToPassFilePaths(testCase.testPatch, testCase.failToPass)
            val rigged = extractPatchFilePaths(checkpoint.patchText).filter { it in oracleFiles }
            check(rigged.isEmpty()) {
                "the committed state ${RippleCheckpointRecorder.patchFileName(checkpoint.step)} of the " +
                    "${coordinates.arm} arm edits the FAIL_TO_PASS oracle ($rigged) — the capture run " +
                    "tampered, so every probe started from it would grade tests the capture wrote"
            }

            // A non-zero `git apply` is an instrument failure and must never be reported as `Y=0`: a zero
            // says the agent could not finish from that state, and an unapplied patch means it was never
            // given that state at all.
            try {
                GitDriver(session.scope).applyPatch(projectDir, checkpoint.patchText)
            } catch (e: Exception) {
                println("[CHECKPOINT-PROBE] MEASUREMENT LOST: the checkpoint patch did not apply to the")
                println("[CHECKPOINT-PROBE]   deployed tree, so this cell never received the state it was")
                println("[CHECKPOINT-PROBE]   supposed to be graded from. Cause: ${e::class.simpleName}: ${e.message}")
                println(checkpoint.probeLine(coordinates, verdict = CHECKPOINT_PROBE_LOST))
                throw IllegalStateException(
                    "MEASUREMENT LOST: the ${coordinates.arm} arm's checkpoint ${coordinates.checkpoint} " +
                        "(step ${checkpoint.step}) did not apply to ${RippleCheckpointCase.INSTANCE_ID}. " +
                        "This is an instrument failure — the probe never received the state — and is not " +
                        "a verdict on the agent.",
                    e,
                )
            }
        }

        override fun decoratePrompt(prompt: String): String = buildDpaiaCheckpointProbePrompt(prompt)

        override fun afterAgentRun(outcome: DpaiaRunOutcome) {
            val verdict = checkpointProbeVerdict(outcome.objectiveSuccess)
            println(checkpoint.probeLine(coordinates, verdict = verdict))
            if (outcome.objectiveSuccess != null) return
            println("[CHECKPOINT-PROBE] MEASUREMENT LOST: the verifier produced no grade for this cell,")
            println("[CHECKPOINT-PROBE]   so its readiness is UNKNOWN rather than zero.")
            fail<Unit>(
                "MEASUREMENT LOST: the ${coordinates.arm} arm's checkpoint ${coordinates.checkpoint} " +
                    "replicate ${coordinates.replicate} could not be graded — ArenaVerifier.verify did " +
                    "not return a result. This is an instrument failure, not a verdict on the agent."
            )
        }
    }
}

/** The state one probe starts from, together with where along the capture's trajectory it was taken. */
private data class LoadedCheckpoint(val step: Int, val position: Double, val patchText: String) {
    fun formattedPosition(): String = String.format(Locale.ROOT, "%.4f", position)

    /** The one line [parseProbeVerdicts] reads, built through the shared formatter. */
    fun probeLine(coordinates: ProbeCoordinates, verdict: String): String =
        checkpointProbeLine(coordinates, step = step, position = position, verdict = verdict)
}

/** What a cell prints when it could not be graded at all — see [checkpointProbeVerdict]. */
private const val CHECKPOINT_PROBE_LOST: String = "LOST"

/**
 * The verdict token of one cell: `Y=1`, `Y=0`, or [CHECKPOINT_PROBE_LOST] when it was never graded.
 *
 * The null branch is the whole reason this is a function. `V` is the fraction of probes that FINISHED,
 * counted over the cells that were graded; a lost cell printed as `Y=0` would pull every readiness value
 * down while looking like a complete measurement, and `parseProbeVerdicts` matches `Y=([01])` precisely
 * so that an ungraded cell stays out of the aggregate instead.
 */
private fun checkpointProbeVerdict(objectiveSuccess: Boolean?): String = when (objectiveSuccess) {
    true -> "Y=1"
    false -> "Y=0"
    null -> CHECKPOINT_PROBE_LOST
}

/**
 * The single line the aggregator reads. Built in one place so the shape `parseProbeVerdicts` requires
 * cannot drift between the graded and the lost path.
 */
private fun checkpointProbeLine(
    coordinates: ProbeCoordinates,
    step: Int,
    position: Double,
    verdict: String,
): String =
    "[CHECKPOINT-PROBE] arm=${coordinates.arm} checkpoint=${coordinates.checkpoint} step=$step " +
        "position=${String.format(Locale.ROOT, "%.4f", position)} replicate=${coordinates.replicate} " +
        verdict

/**
 * Every case directory under `ripple-checkpoints/` whose layout has to stay valid.
 *
 * Two, and both on purpose: the case this pilot probes today, and the keycloak case that was already
 * measured. A published readiness curve is only readable next to the trajectory it came from, so the
 * measured case's states stay committed and checked even though no probe cell addresses them any more.
 */
private val RIPPLE_CHECKPOINT_CASE_DIRS: List<String> = listOf(
    RippleCheckpointCase.RESOURCE_DIR,
    RippleCases.renameMethodWide.instanceId.substringAfterLast("__"),
)

/**
 * Where the committed checkpoints of one case's arm live.
 *
 * A plain relative path because Gradle runs a test with the module directory as its working directory,
 * and the probe needs the SOURCE tree rather than the processed resources: a patch is data an operator
 * copies in from a capture run's artifacts, and reading it where it was committed keeps that copy
 * verifiable with `git diff`.
 */
private fun checkpointResourceDir(caseDir: String, arm: String): File =
    File("src/test/resources/ripple-checkpoints/$caseDir/$arm")

/** Every committed patch of one arm, in schedule order (`step-2` before `step-11`, not lexicographic). */
private fun patchFilesIn(dir: File): List<File> =
    (dir.listFiles { file -> file.isFile && file.name.endsWith(".patch") } ?: emptyArray())
        .sortedBy { it.nameWithoutExtension.substringAfter("step-").toIntOrNull() ?: Int.MAX_VALUE }

/**
 * Everything wrong with one arm directory's committed contents, or an empty list when it is usable.
 *
 * "Usable" includes EMPTY: the pilot commits patches only after a capture has run and been admitted, so
 * a directory holding nothing but its README is the normal state before that and must not redden a
 * build. What is refused is every half-state, because each of them publishes a number nobody measured:
 * patches without `checkpoints.json` have no `n` to normalize their positions by, metadata without
 * patches names states no probe can start from, and a patch set the metadata does not account for means
 * the two came from different capture runs — a trajectory that never existed.
 *
 * Pure, and separate from the directory walk, because these are the mistakes a human makes while copying
 * artifacts out of a build. A unit test can enumerate them; a probe build discovering one costs an hour.
 */
private fun checkpointResourceProblems(
    location: String,
    patchFileNames: List<String>,
    metadataJson: String?,
): List<String> = buildList {
    val metadataFile = RippleCheckpointRecorder.METADATA_FILE_NAME
    val malformed = patchFileNames.filter { name ->
        name.removeSuffix(".patch").substringAfter("step-").toIntOrNull() == null ||
            !name.startsWith("step-")
    }
    if (malformed.isNotEmpty()) {
        add("$location holds $malformed, which is not a step-<n>.patch, so no probe cell can address it")
    }
    if (patchFileNames.size > RIPPLE_CHECKPOINT_COUNT) {
        add(
            "$location holds ${patchFileNames.size} patches, more than the $RIPPLE_CHECKPOINT_COUNT " +
                "checkpoints a probe can address — states of two capture runs are mixed in one directory"
        )
    }
    if (metadataJson == null) {
        if (patchFileNames.isNotEmpty()) {
            add(
                "$location holds $patchFileNames but no $metadataFile, so the trajectory length those " +
                    "states came from is unknown and no position can be published for them"
            )
        }
        return@buildList
    }
    val described = checkpointPatchNames(metadataJson)
    if (described.toSet() != patchFileNames.toSet()) {
        add(
            "$location holds $patchFileNames but its $metadataFile describes $described. Commit a " +
                "capture's patches and its metadata together — a mismatch means they come from " +
                "different runs, and a position normalized by the wrong run's n is not a measurement."
        )
    }
}

/** The patch file names one `checkpoints.json` describes, in its own order. */
private fun checkpointPatchNames(metadataJson: String): List<String> =
    checkpointEntries(metadataJson).map { entry ->
        entry["patch"]?.jsonPrimitive?.content
            ?: error("a checkpoint entry of ${RippleCheckpointRecorder.METADATA_FILE_NAME} names no patch")
    }

private fun checkpointEntries(metadataJson: String) =
    Json.parseToJsonElement(metadataJson).jsonObject["checkpoints"]?.jsonArray?.map { it.jsonObject }
        ?: error("${RippleCheckpointRecorder.METADATA_FILE_NAME} carries no checkpoints array")

/**
 * Load the one state a cell probes, refusing to run at all when it is not there.
 *
 * Fails loudly rather than skipping, in both directions: a missing patch means the capture never reached
 * that position, and a missing `checkpoints.json` means nobody can say what fraction of a trajectory the
 * patch represents — a probe published without that number would report a readiness at an unknown place
 * on the curve.
 */
private fun loadCheckpoint(coordinates: ProbeCoordinates): LoadedCheckpoint {
    val dir = checkpointResourceDir(RippleCheckpointCase.RESOURCE_DIR, coordinates.arm)
    val metadataFile = dir.resolve(RippleCheckpointRecorder.METADATA_FILE_NAME)
    check(metadataFile.isFile) {
        "${metadataFile.absolutePath} is missing, so neither the step this checkpoint sits at nor the " +
            "trajectory length its position is normalized by is known"
    }
    val metadata = metadataFile.readText()

    val problems = checkpointResourceProblems(
        location = dir.path,
        patchFileNames = patchFilesIn(dir).map { it.name },
        metadataJson = metadata,
    )
    check(problems.isEmpty()) { problems.joinToString("\n") }

    // The capture's own metadata is the ONLY source of the positions. They are derived from the length
    // that run actually reached, so no constant in this repository can name them: a fixed schedule was
    // what left the pilot's first mcp capture without a fifth state at all.
    val entries = checkpointEntries(metadata)
    val entry = entries.firstOrNull {
        it["index"]?.jsonPrimitive?.content?.toIntOrNull() == coordinates.checkpoint
    } ?: error(
        "${metadataFile.absolutePath} describes no checkpoint ${coordinates.checkpoint} — that capture " +
            "carries ${entries.size} checkpoint(s), see its corrections for why"
    )
    val step = entry["step"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: error("${metadataFile.absolutePath} has no step for checkpoint ${coordinates.checkpoint}")
    val position = entry["position"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: error("${metadataFile.absolutePath} has no numeric position for step $step")

    val patch = dir.resolve(RippleCheckpointRecorder.patchFileName(step))
    check(patch.isFile) { "no committed state for checkpoint ${coordinates.checkpoint} at ${patch.absolutePath}" }

    return LoadedCheckpoint(step = step, position = position, patchText = patch.readText())
}
