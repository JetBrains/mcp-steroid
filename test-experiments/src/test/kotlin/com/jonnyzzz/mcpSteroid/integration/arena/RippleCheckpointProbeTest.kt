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
 * [arm] must name a captured arm and [index] one of the checkpoints that arm really committed — a third
 * arm or a checkpoint past the end of the committed set has no state to start from, and discovering
 * that after the container, the clone and the Maven import would waste most of a build.
 *
 * The upper bound comes from [checkpointsInArm], i.e. from the arm's own `checkpoints.json`, and is not
 * a constant. It cannot be one: the number of checkpoints follows the axis, which has already changed
 * twice, and a hardcoded five would have rejected every cell of a ten-fraction grid while looking like
 * a validation. WHICH step an ordinal refers to is still not decided here — that is [loadCheckpoint]'s
 * job, from the same file. [replicate] is bounded too, because a replicate outside
 * `1..RIPPLE_CHECKPOINT_REPLICATES` means the operator queued more runs than the aggregator will ever
 * fold into a `V`.
 */
fun probeCoordinates(
    arm: String?,
    index: String?,
    replicate: String?,
    checkpointsInArm: (String) -> Int = ::committedCheckpointCount,
): ProbeCoordinates {
    val arms = rippleCheckpointArms(RippleCheckpointCase.RESOURCE_DIR)
    require(arm != null && arm in arms) {
        "the probe arm must be one of $arms, got '$arm' — no other arm was captured"
    }
    val available = checkpointsInArm(arm)
    val checkpoint = index?.toIntOrNull()
    require(checkpoint != null && checkpoint in 1..available) {
        "the checkpoint index must be in 1..$available, got '$index' — the $arm capture committed " +
            "$available checkpoint(s) and no other state exists to start a probe from"
    }
    val replicateNumber = replicate?.toIntOrNull()
    require(replicateNumber != null && replicateNumber in 1..RIPPLE_CHECKPOINT_REPLICATES) {
        "the replicate must be in 1..$RIPPLE_CHECKPOINT_REPLICATES, got '$replicate' — the aggregator " +
            "folds exactly $RIPPLE_CHECKPOINT_REPLICATES runs into one readiness value"
    }
    return ProbeCoordinates(arm = arm, checkpoint = checkpoint, replicate = replicateNumber)
}

/**
 * How many checkpoints the [arm] capture of this pilot's case committed.
 *
 * Read from the artifact rather than computed, because the committed set is what a probe can actually
 * be started from: a plan may name ten fractions while the directory holds the states of nine distinct
 * steps, and it is the directory that decides which cells are addressable.
 */
fun committedCheckpointCount(arm: String): Int = committedCheckpointCountOrNull(arm)
    ?: error(
        "${checkpointResourceDir(RippleCheckpointCase.RESOURCE_DIR, arm)}/" +
            "${RippleCheckpointRecorder.METADATA_FILE_NAME} is missing, so nothing says how many " +
            "checkpoints the $arm arm committed and no probe cell can be addressed at all"
    )

/**
 * The same count, or `null` for a registered arm whose capture has not landed yet.
 *
 * An arm can legitimately exist in the registry with an empty directory — `mcp2` and `none2` did,
 * between the commit that named them and the capture that filled them. That state must stay green in
 * the resource tests and must still refuse every probe cell, which is exactly the difference between
 * this function and [committedCheckpointCount].
 */
fun committedCheckpointCountOrNull(arm: String): Int? {
    val metadataFile = checkpointResourceDir(RippleCheckpointCase.RESOURCE_DIR, arm)
        .resolve(RippleCheckpointRecorder.METADATA_FILE_NAME)
    if (!metadataFile.isFile) return null
    return checkpointEntries(metadataFile.readText()).size
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
 * 5. One extra log line, `[CHECKPOINT-PROBE] … Y=<0|1>`, which is what the aggregator reads — or
 *    `… LOST reason=<why>` for a cell the instrument, not the agent, failed.
 *
 * Nothing re-ingests the patched tree into the IDE, and nothing has to: the probe's agent is bare and the
 * grade comes from Maven over the files on disk, so no step of this flow reads the tree through PSI. The
 * ripple probe needed that refresh because its oracle was an IDE query.
 */
class RippleCheckpointProbeTest {

    @Test
    fun `probe coordinates come from system properties and reject an unknown checkpoint`() {
        val committed = { arm: String -> if (arm == "mcp") 6 else 8 }

        assertEquals(ProbeCoordinates("mcp", 3, 2), probeCoordinates("mcp", "3", "2", committed))
        assertEquals(ProbeCoordinates("none", 8, 5), probeCoordinates("none", "8", "5", committed))
        assertThrows(IllegalArgumentException::class.java) { probeCoordinates("mcp", "7", "1", committed) }
        assertThrows(IllegalArgumentException::class.java) { probeCoordinates("mcp", "0", "1", committed) }
        assertThrows(IllegalArgumentException::class.java) { probeCoordinates("shell", "1", "1", committed) }
        assertThrows(IllegalArgumentException::class.java) { probeCoordinates("mcp", "1", "6", committed) }
    }

    /**
     * The guard against the REAL committed set, which is the only thing that can prove the bound moves
     * with the axis. A hardcoded number here would have to be edited every time the grid changes, and
     * would be edited to whatever makes the build green rather than to what was captured.
     */
    @Test
    fun `the last committed checkpoint of each arm is addressable and the next one is not`() {
        rippleCheckpointArms(RippleCheckpointCase.RESOURCE_DIR).forEach { arm ->
            val committed = committedCheckpointCountOrNull(arm)
            if (committed == null) {
                // A registered arm whose capture has not landed. Every cell of it must be refused, and
                // loudly: a probe queued against an empty arm would otherwise fail an hour in, inside
                // the container, instead of in the first milliseconds of the build.
                assertThrows(IllegalStateException::class.java) { probeCoordinates(arm, "1", "1") }
                return@forEach
            }
            assertTrue(committed > 0) { "the $arm arm committed no checkpoint at all" }
            assertEquals(
                ProbeCoordinates(arm, committed, 1),
                probeCoordinates(arm, committed.toString(), "1"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                probeCoordinates(arm, (committed + 1).toString(), "1")
            }
        }
    }

    /**
     * The round is encoded in the arm token, so the registry is what decides which rounds are
     * addressable at all — and it is per case, because the discarded keycloak case has no second
     * capture and must not be asked for one.
     */
    @Test
    fun `the second capture is addressable on the measured case and unknown to the discarded one`() {
        assertEquals(
            listOf("mcp", "none", "mcp2", "none2"),
            rippleCheckpointArms(RippleCheckpointCase.RESOURCE_DIR),
        )
        assertEquals(
            listOf("mcp", "none"),
            rippleCheckpointArms(RippleCases.renameMethodWide.instanceId.substringAfterLast("__")),
        )
        assertThrows(IllegalStateException::class.java) { rippleCheckpointArms("a-case-nobody-captured") }

        val committed = { _: String -> 4 }
        assertEquals(ProbeCoordinates("mcp2", 3, 2), probeCoordinates("mcp2", "3", "2", committed))
        assertEquals(ProbeCoordinates("none2", 4, 5), probeCoordinates("none2", "4", "5", committed))
        assertThrows(IllegalArgumentException::class.java) { probeCoordinates("mcp3", "1", "1", committed) }
    }

    /**
     * The committed states, checked as ARTIFACTS rather than as a count.
     *
     * The arm directories of every case the layout serves are asserted to exist and every patch found in
     * them must be a readable diff that its own `checkpoints.json` accounts for.
     *
     * It deliberately does NOT assert a fixed number of patches per arm. Before the capture runs land,
     * the directories hold only their README, and a hard count here would either be a red build for
     * weeks or — far worse — invite a skip-on-missing branch that would keep passing after a capture
     * silently failed to produce a state. Nor is a ceiling meaningful any more: the grid is ten
     * fractions of an edit phase, so how many patches an arm carries is a property of the capture and
     * not of the instrument. What is required instead is an exact MATCH — the committed patch set must
     * be the set of steps the arm's own `checkpoints.json` names. What this test guarantees at every
     * point in time is that whatever IS committed is a valid checkpoint of one run — see
     * [checkpointResourceProblems] for the mismatches it refuses.
     *
     * The FAIL_TO_PASS oracle is NOT inspected here, and that is a deliberate move rather than a gap:
     * the DPAIA case's oracle class names live in the dataset this module downloads at run time, and a
     * unit test must not depend on the network to be able to reject a rigged state. The check moved to
     * [probe], which loads the case anyway and refuses a patch touching an oracle file before it spends
     * a container on it.
     */
    @Test
    fun `every committed checkpoint patch is a readable diff its metadata accounts for`() {
        RIPPLE_CHECKPOINT_CASE_ARMS.forEach { (caseDir, arms) ->
            arms.forEach { arm ->
                val dir = checkpointResourceDir(caseDir, arm)
                assertTrue(dir.isDirectory) {
                    "$dir is missing. The probe reads its starting states from there, so the layout is " +
                        "part of the instrument and not something a capture run creates on the fly."
                }
                val patches = patchFilesIn(dir)
                println(
                    "[CHECKPOINT-RESOURCES] $caseDir/$arm: ${patches.size} committed patch(es) — " +
                        "${patches.map { it.name }}"
                )
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
     *
     * The graded lines carry the cost the aggregator's median columns are built from; the lost one
     * carries none, because a cell whose patch never applied never ran an agent and has no price. That
     * asymmetry is exactly what the parser's optional groups exist for.
     */
    @Test
    fun `a lost measurement never reaches the aggregator as a zero`() {
        val coordinates = ProbeCoordinates("mcp", checkpoint = 4, replicate = 2)
        val cost = ProbeRunCost(usd = 0.3278, agentSeconds = 613, tokens = 152_414)
        fun line(objectiveSuccess: Boolean?, price: ProbeRunCost?) = checkpointProbeLine(
            coordinates = coordinates,
            step = 18,
            editFraction = 0.3,
            position = 0.6923,
            verdict = checkpointProbeVerdict(objectiveSuccess),
            cost = price,
        )

        val graded = line(true, cost)
        assertEquals(
            "[CHECKPOINT-PROBE] arm=mcp checkpoint=4 step=18 editFraction=0.300 position=0.6923 " +
                "replicate=2 Y=1 usd=0.3278 agentSeconds=613 tokens=152414",
            graded,
        )
        assertEquals(
            listOf(
                ProbeVerdict(
                    "mcp", 4, 18, 0.6923, 2, success = true,
                    editFraction = 0.3, usd = 0.3278, agentSeconds = 613, tokens = 152_414,
                )
            ),
            parseProbeVerdicts(graded),
        )
        assertEquals(
            listOf(
                ProbeVerdict(
                    "mcp", 4, 18, 0.6923, 2, success = false,
                    editFraction = 0.3, usd = 0.3278, agentSeconds = 613, tokens = 152_414,
                )
            ),
            parseProbeVerdicts(line(false, cost)),
        )

        val lost = line(null, null)
        assertTrue(lost.contains("LOST reason=not-graded")) { lost }
        assertFalse(lost.contains("usd=")) { "a cell that never ran an agent has no price: $lost" }
        assertEquals(emptyList<ProbeVerdict>(), parseProbeVerdicts(lost)) {
            "an ungraded cell must not be foldable into V at all"
        }
    }

    /**
     * The measured defect: TeamCity build 1035679682 published `Y=0 usd=0.0672 agentSeconds=26
     * tokens=0` for `arm=none checkpoint=5 step=33 replicate=1` after the Claude CLI's connection to
     * Anthropic was closed mid-response — 26 seconds, 9 Reads, 0 Edits, exit 1. That zero says the
     * recorded state was not ready enough for a haiku to finish from, which is not what happened: the
     * probe never got a working agent. This pins the line such a cell prints instead.
     *
     * The cost fields stay ON the lost line even though the cell is not folded into `V`: the money was
     * really spent and an operator re-queueing the cell needs to see it. It cannot leak into the cost
     * medians, because [parseProbeVerdicts] requires a `Y=0|1` token that a lost line never carries.
     */
    @Test
    fun `a transport abort prints LOST with its reason instead of a graded zero`() {
        val coordinates = ProbeCoordinates("none", checkpoint = 5, replicate = 1)
        val line = checkpointProbeLine(
            coordinates = coordinates,
            step = 33,
            editFraction = 0.889,
            position = 0.8250,
            verdict = checkpointProbeVerdictFor(
                probeOutcome(
                    objectiveSuccess = false,
                    apiTransportError = "API Error: Connection closed mid-response. The response " +
                        "above may be incomplete.",
                )
            ),
            cost = ProbeRunCost(usd = 0.0672, agentSeconds = 26, tokens = 0),
        )

        assertEquals(
            "[CHECKPOINT-PROBE] arm=none checkpoint=5 step=33 editFraction=0.889 position=0.8250 " +
                "replicate=1 LOST reason=api-transport-error usd=0.0672 agentSeconds=26 tokens=0",
            line,
        )
        assertEquals(emptyList<ProbeVerdict>(), parseProbeVerdicts(line)) {
            "a cell whose agent lost its connection must never be folded into V"
        }
    }

    /**
     * The rule is NOT widened to every unhappy run. A probe that received its state, edited files and
     * then ran out of its own budget failed at the task, and that is precisely the measurement `V` is
     * made of — see [AgentOutputTransportErrorTest] for the transcript shapes that are refused.
     */
    @Test
    fun `a run that simply did not solve the task is still a graded zero`() {
        val verdict = checkpointProbeVerdictFor(probeOutcome(objectiveSuccess = false))
        assertEquals("Y=0", verdict)
    }

    /**
     * A transport abort outranks the grade, including a passing one.
     *
     * A stream that died mid-response is a TRUNCATED run: whatever the verifier then found on disk is
     * not the outcome of the trajectory the pilot meant to measure, and publishing it would put a
     * one-off `Y=1` next to four full-length replicates in the same group. Re-queueing costs one cell
     * and biases nothing in either direction.
     */
    @Test
    fun `a passing grade under a broken connection is re-queued, not published`() {
        val verdict = checkpointProbeVerdictFor(
            probeOutcome(objectiveSuccess = true, apiTransportError = "API Error: Connection error.")
        )
        assertEquals("LOST reason=api-transport-error", verdict)
    }

    /** The verifier producing no grade at all keeps its own, distinguishable reason. */
    @Test
    fun `an ungraded cell names its own loss`() {
        assertEquals("LOST reason=not-graded", checkpointProbeVerdictFor(probeOutcome(null)))
    }

    /**
     * An agent that ran out of its own budget is an UNSUCCESS, even when nothing graded it.
     *
     * The measured cell is TeamCity build 1035674856 (`arm=mcp checkpoint=2 replicate=3`): 1800 s, exit
     * -1, no grade, published as `LOST reason=not-graded`. Withholding that cell is wrong — spending the
     * whole 30-minute budget every replicate shares WITHOUT finishing is exactly the outcome `V` is a
     * fraction of, so it belongs in the denominator and in the numerator's complement.
     */
    @Test
    fun `an agent that ran out of its own budget is a zero even without a grade`() {
        assertEquals("Y=0", checkpointProbeVerdictFor(probeOutcome(null, agentTimedOut = true)))
    }

    /**
     * The zero of the budget branch is an ORDINARY verdict: it folds into `V` and into the group's
     * `runs` count exactly like a graded zero, which is what makes the re-queue unnecessary.
     *
     * The dollar figure is absent on purpose — a killed CLI never emits its terminal `result` event —
     * and the parser must read that back as "never reported" while still counting the run.
     */
    @Test
    fun `a budget-exhausted zero folds into V like any other verdict`() {
        val line = checkpointProbeLine(
            coordinates = ProbeCoordinates("mcp", checkpoint = 2, replicate = 3),
            step = 11,
            editFraction = 0.2,
            position = 0.3667,
            verdict = checkpointProbeVerdictFor(probeOutcome(null, agentTimedOut = true)),
            cost = ProbeRunCost(usd = null, agentSeconds = 1800, tokens = null),
        )

        assertEquals(
            "[CHECKPOINT-PROBE] arm=mcp checkpoint=2 step=11 editFraction=0.200 position=0.3667 " +
                "replicate=3 Y=0 agentSeconds=1800",
            line,
        )
        assertEquals(
            listOf(
                ProbeVerdict(
                    "mcp", 2, 11, 0.3667, 3, success = false,
                    editFraction = 0.2, usd = null, agentSeconds = 1800, tokens = null,
                )
            ),
            parseProbeVerdicts(line),
        )
    }

    /**
     * A grade, once it exists, is not overruled by the budget having run out: an agent that edited its
     * way to green and was killed a second later still left a tree the verifier measured.
     */
    @Test
    fun `a graded run keeps its grade when the budget ran out`() {
        assertEquals(
            "Y=1",
            checkpointProbeVerdictFor(probeOutcome(objectiveSuccess = true, agentTimedOut = true)),
        )
        assertEquals(
            "Y=0",
            checkpointProbeVerdictFor(probeOutcome(objectiveSuccess = false, agentTimedOut = true)),
        )
    }

    /**
     * A transport abort outranks the budget too. The two can co-occur — a dead connection leaves the CLI
     * hanging until the harness kills it — and then the run measured the transport, not the budget.
     */
    @Test
    fun `a transport abort outranks the agent running out of budget`() {
        assertEquals(
            "LOST reason=api-transport-error",
            checkpointProbeVerdictFor(
                probeOutcome(
                    objectiveSuccess = null,
                    apiTransportError = "API Error: Connection error.",
                    agentTimedOut = true,
                )
            ),
        )
    }

    /**
     * One finished run as a seam sees it, with only the three fields these verdict tests turn on.
     *
     * The grade is built through a real [ArenaVerificationResult] rather than injected, because
     * [DpaiaRunOutcome.objectiveSuccess] derives it — a hand-set boolean would pass while the
     * derivation the probe actually reads did something else.
     */
    private fun probeOutcome(
        objectiveSuccess: Boolean?,
        apiTransportError: String? = null,
        agentTimedOut: Boolean = false,
    ): DpaiaRunOutcome = DpaiaRunOutcome(
        instanceId = RippleCheckpointCase.INSTANCE_ID,
        agentName = "claude",
        modeLabel = "none",
        agentDurationMs = 26_000,
        endContextTokens = 0,
        costUsd = 0.0672,
        apiTransportError = apiTransportError,
        agentTimedOut = agentTimedOut,
        verification = objectiveSuccess?.let { success ->
            ArenaVerificationResult(
                perClass = listOf(
                    SurefireClassResult(
                        className = "org.example.OracleTest",
                        testsRun = 1,
                        failures = if (success) 0 else 1,
                        errors = 0,
                        skipped = 0,
                    )
                ),
                failToPassTampered = false,
                collateralTestFilesEdited = emptyList(),
                regressions = emptyList(),
                baselineAvailable = true,
                verificationDurationMs = 0,
            )
        },
        recorder = null,
    )

    /**
     * A plan of [steps] as the recorder would publish it, used to build the metadata half of the
     * resource-shape checks. The trees are made distinct per step so no entry is dropped as a repetition
     * — these tests are about the patch/metadata PAIRING and not about what the states hold.
     */
    private fun metadataJson(steps: List<Int>): String = RippleCheckpointRecorder.metadataJson(
        case = RippleCheckpointCase.INSTANCE_ID,
        arm = "mcp",
        model = "claude-opus-5",
        plan = CheckpointPlan(
            n = 30,
            firstWriteStep = steps.first(),
            fractions = steps.size,
            checkpoints = steps.mapIndexed { index, step ->
                CheckpointSelection(
                    index = index + 1,
                    step = step,
                    editFraction = index.toDouble() / steps.size,
                    position = step / 30.0,
                    stateId = "tree-$step",
                    sameStateAs = null,
                )
            },
        ),
        patchChars = steps.associateWith { 0 },
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
                "step=${checkpoint.step} editFraction=${checkpoint.formattedEditFraction()} " +
                "position=${checkpoint.formattedPosition()} replicate=${coordinates.replicate} " +
                "patch=${checkpoint.patchText.length} chars"
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
                // No cost: the agent never started, so every price this cell could report is unknown —
                // and unknown must reach the aggregator as an absent field, never as a zero.
                println(checkpoint.probeLine(
                    coordinates,
                    verdict = checkpointProbeLostVerdict(LOST_PATCH_NOT_APPLIED),
                    cost = null,
                ))
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
            // The three prices the arena already measured for this run — the agent's own timer, the
            // dollar figure Claude Code reports on its terminal `result` event, and the end-of-run
            // context. Read off the outcome rather than recomputed, so the probe's cost columns and the
            // arena's own run summary can never disagree about the same run. They stay on a LOST line
            // too: the money was really spent, and only a `Y=0|1` line can reach the cost medians.
            println(checkpoint.probeLine(
                coordinates,
                verdict = checkpointProbeVerdictFor(outcome),
                cost = ProbeRunCost(
                    usd = outcome.costUsd,
                    agentSeconds = outcome.agentDurationMs / 1000,
                    tokens = outcome.endContextTokens,
                ),
            ))

            val transportError = outcome.apiTransportError
            if (transportError != null) {
                println("[CHECKPOINT-PROBE] MEASUREMENT LOST: the agent's own connection to the model")
                println("[CHECKPOINT-PROBE]   was aborted mid-run, so this cell measured the transport")
                println("[CHECKPOINT-PROBE]   and not the state it was supposed to be graded from.")
                println("[CHECKPOINT-PROBE]   Cause: $transportError")
                fail<Unit>(
                    "MEASUREMENT LOST: the ${coordinates.arm} arm's checkpoint ${coordinates.checkpoint} " +
                        "replicate ${coordinates.replicate} lost its API connection ($transportError), " +
                        "so the run it published was never the run this cell paid for. Re-queue the " +
                        "cell — the group stays INCOMPLETE until five graded replicates exist. This is " +
                        "an instrument failure and not a verdict on the agent; a run that edited files " +
                        "and then hit its OWN timeout is a real Y=0 and never reaches this branch."
                )
            }

            if (outcome.objectiveSuccess != null) return

            if (outcome.agentTimedOut) {
                // An unsuccess, not a loss: the agent worked inside the same budget every replicate
                // shares and had nothing to show when it ran out — see [checkpointProbeVerdictFor]. Said
                // out loud because the run flow's own "neither exited successfully nor claimed a fix"
                // check reddens this cell moments later, and an operator reading that failure must not
                // re-queue a cell whose verdict is already in the log.
                println("[CHECKPOINT-PROBE] PUBLISHED AS Y=0: the agent spent its whole time budget")
                println("[CHECKPOINT-PROBE]   without finishing, so nothing was left for the verifier to")
                println("[CHECKPOINT-PROBE]   grade. That is the task failing, not the instrument.")
                println("[CHECKPOINT-PROBE]   Do NOT re-queue this cell — its verdict counts towards V.")
                return
            }

            println("[CHECKPOINT-PROBE] MEASUREMENT LOST: the verifier produced no grade for this cell,")
            println("[CHECKPOINT-PROBE]   and the agent did not run out of its budget either, so its")
            println("[CHECKPOINT-PROBE]   readiness is UNKNOWN rather than zero.")
            fail<Unit>(
                "MEASUREMENT LOST: the ${coordinates.arm} arm's checkpoint ${coordinates.checkpoint} " +
                    "replicate ${coordinates.replicate} could not be graded — ArenaVerifier.verify did " +
                    "not return a result, and the agent still had budget left. This is an instrument " +
                    "failure, not a verdict on the agent; an agent that DID exhaust its budget " +
                    "publishes a real Y=0 and never reaches this branch."
            )
        }
    }
}

/** The state one probe starts from, together with where along the capture's trajectory it was taken. */
private data class LoadedCheckpoint(
    val step: Int,
    val editFraction: Double,
    val position: Double,
    val patchText: String,
) {
    fun formattedEditFraction(): String = String.format(Locale.ROOT, "%.3f", editFraction)

    fun formattedPosition(): String = String.format(Locale.ROOT, "%.4f", position)

    /** The one line [parseProbeVerdicts] reads, built through the shared formatter. */
    fun probeLine(coordinates: ProbeCoordinates, verdict: String, cost: ProbeRunCost?): String =
        checkpointProbeLine(
            coordinates = coordinates,
            step = step,
            editFraction = editFraction,
            position = position,
            verdict = verdict,
            cost = cost,
        )
}

/**
 * What one probe run cost: dollars, the agent's own wall seconds, and the end-of-run context size.
 *
 * Every field is nullable and each one independently, because the harness really does report them
 * separately — Claude Code's terminal `result` event carries the price and a stream that ends without
 * it carries none, while the duration is always measured. Null is printed by omitting the field, so the
 * aggregator reads it back as "never reported" instead of as a free, instant run.
 *
 * These three are the pilot's second signal. `V` saturates: the probe's base rate on the pristine tree
 * is already around two thirds, so past the middle of the edit phase the only thing that still
 * distinguishes two states is how much a weak agent must spend to finish from each.
 */
private data class ProbeRunCost(val usd: Double?, val agentSeconds: Long?, val tokens: Long?)

/** What a cell prints when it could not be graded at all — see [checkpointProbeVerdictFor]. */
private const val CHECKPOINT_PROBE_LOST: String = "LOST"

/** The token of a cell whose probe finished the task from the recorded state. */
private const val CHECKPOINT_PROBE_SUCCESS: String = "Y=1"

/**
 * The token of a cell whose probe did NOT finish the task — a full measurement, folded into `V`.
 *
 * Printed for a graded failure AND for an agent that spent its whole budget without being graded; see
 * [checkpointProbeVerdictFor] for why the second one is an unsuccess rather than a loss.
 */
private const val CHECKPOINT_PROBE_UNSUCCESS: String = "Y=0"

/** The cell never received its recorded state: `git apply` refused the committed patch. */
private const val LOST_PATCH_NOT_APPLIED: String = "patch-not-applied"

/** The agent's own connection to the model died mid-run — see [extractApiTransportError]. */
private const val LOST_API_TRANSPORT: String = "api-transport-error"

/** The run happened, but [ArenaVerifier.verify] produced no grade for it. */
private const val LOST_NOT_GRADED: String = "not-graded"

/**
 * The token a cell prints instead of a verdict, naming WHY it was lost.
 *
 * The reason is on the line rather than only in the prose above it because the line is the machine-
 * readable record: an operator re-queues by grepping the build logs, and `LOST` alone cannot tell a
 * cell that needs its patch repaired from one that only needs running again on a healthy connection.
 * It is safe to append here — `parseProbeVerdicts` keys on `Y=([01])`, which no lost line carries.
 */
private fun checkpointProbeLostVerdict(reason: String): String = "$CHECKPOINT_PROBE_LOST reason=$reason"

/**
 * The verdict token of one cell: `Y=1`, `Y=0`, or a [checkpointProbeLostVerdict] when it was never
 * graded.
 *
 * The null branch is the whole reason this is a function. `V` is the fraction of probes that FINISHED,
 * counted over the cells that were graded; a lost cell printed as `Y=0` would pull every readiness value
 * down while looking like a complete measurement, and `parseProbeVerdicts` matches `Y=([01])` precisely
 * so that an ungraded cell stays out of the aggregate instead.
 */
private fun checkpointProbeVerdict(objectiveSuccess: Boolean?): String = when (objectiveSuccess) {
    true -> CHECKPOINT_PROBE_SUCCESS
    false -> CHECKPOINT_PROBE_UNSUCCESS
    null -> checkpointProbeLostVerdict(LOST_NOT_GRADED)
}

/**
 * The verdict of one FINISHED run — the grade, unless the run itself never really happened.
 *
 * Four branches, in this order, each one pinned by a test in [RippleCheckpointProbeTest]:
 *  1. [DpaiaRunOutcome.apiTransportError] → `LOST reason=api-transport-error`;
 *  2. a grade exists → `Y=1` / `Y=0`;
 *  3. no grade, but [DpaiaRunOutcome.agentTimedOut] → `Y=0`;
 *  4. no grade and no budget exhaustion → `LOST reason=not-graded`.
 *
 * Branch 1 outranks the grade, including a passing one, because a stream cut mid-response is a TRUNCATED
 * run: the tree the verifier then graded is not the end of the trajectory the cell was paid for. The
 * measured case is TeamCity build 1035679682 (`arm=none checkpoint=5 step=33 replicate=1`), which
 * published `Y=0 usd=0.0672 agentSeconds=26 tokens=0` — 26 seconds, 9 Reads, ZERO Edits, exit 1 — after
 * Anthropic closed the connection. Read as a zero, that says the recorded state was too far from a
 * solution; it says nothing of the sort.
 *
 * Branch 3 is the opposite ruling, and the reason `LOST` cannot simply mean "no grade". An agent that
 * spent the whole budget every replicate shares and still had nothing to show FAILED AT THE TASK, which
 * is precisely the outcome `V` is a fraction of — so the cell is an unsuccess and folds into the
 * aggregate, no re-queue needed. Its measured case is TeamCity build 1035674856 (`arm=mcp checkpoint=2
 * replicate=3`): 1800 s of budget spent, exit -1, no grade, published as `LOST reason=not-graded` and
 * thereby dropped out of the sample for that checkpoint without leaving a hole anyone could see. Note
 * the cell's Gradle test still fails afterwards, on the flow's own "neither exited successfully nor
 * claimed a fix" check — the `Y=0` line is already in the log by then and an operator must NOT re-queue
 * a cell whose log carries a `Y=` token.
 *
 * `LOST` therefore stays reserved for the INSTRUMENT failing: the patch not applying, the container or
 * the verifier dying (branch 4), and the transport abort. A `tool_result` marked `is_error` is not one
 * either — a continuation that kept breaking its own commands failed at the task. See
 * [extractApiTransportError] for the two transcript shapes that qualify and the ones that never will,
 * and [agentRunTimedOut] for why the budget signal is the runner's own report and not a wall-clock guess.
 */
private fun checkpointProbeVerdictFor(outcome: DpaiaRunOutcome): String = when {
    outcome.apiTransportError != null -> checkpointProbeLostVerdict(LOST_API_TRANSPORT)
    outcome.objectiveSuccess != null -> checkpointProbeVerdict(outcome.objectiveSuccess)
    outcome.agentTimedOut -> CHECKPOINT_PROBE_UNSUCCESS
    else -> checkpointProbeLostVerdict(LOST_NOT_GRADED)
}

/**
 * The single line the aggregator reads. Built in one place so the shape `parseProbeVerdicts` requires
 * cannot drift between the graded and the lost path.
 *
 * A price that was not measured is OMITTED rather than printed as a zero or a dash: the parser's
 * optional groups then read it back as null, which is the same thing the 38 verdicts recorded before
 * these fields existed report. One shape for "nobody measured it", whatever the reason.
 */
private fun checkpointProbeLine(
    coordinates: ProbeCoordinates,
    step: Int,
    editFraction: Double,
    position: Double,
    verdict: String,
    cost: ProbeRunCost?,
): String = buildString {
    append("[CHECKPOINT-PROBE] arm=${coordinates.arm} checkpoint=${coordinates.checkpoint} step=$step ")
    append("editFraction=${String.format(Locale.ROOT, "%.3f", editFraction)} ")
    append("position=${String.format(Locale.ROOT, "%.4f", position)} ")
    append("replicate=${coordinates.replicate} ")
    append(verdict)
    cost?.usd?.let { append(" usd=${String.format(Locale.ROOT, "%.4f", it)}") }
    cost?.agentSeconds?.let { append(" agentSeconds=$it") }
    cost?.tokens?.let { append(" tokens=$it") }
}

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

/**
 * The patch file names one `checkpoints.json` describes, in its own order and without repetition.
 *
 * Derived from each entry's STEP rather than read from a `patch` field, because the step is what names
 * the file (`step-<n>.patch`) and a second, redundant name in the metadata could disagree with it. The
 * de-duplication is not cosmetic: two fractions of a short edit phase round onto the same step, and the
 * directory then holds one file for both of them.
 */
private fun checkpointPatchNames(metadataJson: String): List<String> =
    checkpointEntries(metadataJson).map { entry ->
        val step = entry["step"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error(
                "a checkpoint entry of ${RippleCheckpointRecorder.METADATA_FILE_NAME} names no step, so " +
                    "the state it describes cannot be matched with a committed patch"
            )
        RippleCheckpointRecorder.patchFileName(step)
    }.distinct()

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
            "carries ${entries.size} checkpoint(s)"
    )
    val step = entry["step"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: error("${metadataFile.absolutePath} has no step for checkpoint ${coordinates.checkpoint}")
    val editFraction = entry["editFraction"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: error(
            "${metadataFile.absolutePath} has no numeric editFraction for step $step, so this cell " +
                "could only be published at a coordinate the other arm cannot be compared on"
        )
    val position = entry["position"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: error("${metadataFile.absolutePath} has no numeric position for step $step")

    val patch = dir.resolve(RippleCheckpointRecorder.patchFileName(step))
    check(patch.isFile) { "no committed state for checkpoint ${coordinates.checkpoint} at ${patch.absolutePath}" }

    return LoadedCheckpoint(
        step = step,
        editFraction = editFraction,
        position = position,
        patchText = patch.readText(),
    )
}
