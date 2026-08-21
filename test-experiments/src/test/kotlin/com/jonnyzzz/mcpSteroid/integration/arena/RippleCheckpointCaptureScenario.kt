/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.asDockerClaudeSession

/**
 * One recorded arm of any DPAIA checkpoint case, as an ANONYMOUS [DpaiaScenarioBaseTest].
 *
 * Anonymity is the mechanism, not an accident: JUnit Jupiter's `IsPotentialTestContainer` rejects
 * anonymous classes, so the four graded `@Test` methods this object inherits can never be collected and
 * run alongside a capture. A named subclass — even a private top-level one, which Kotlin compiles to a
 * package-private JVM class that JUnit's private-class filter does NOT exclude — would be picked up by
 * classpath scanning.
 *
 * Shared by every per-case capture class instead of copied into each, because the recording is the
 * INSTRUMENT and the case is only its subject: eight copies of this seam would be eight chances for one
 * case's `[CHECKPOINT]` lines, its export order or its admission call to drift away from the others,
 * and the whole point of round 3 is that the six new cases are measured by the same instrument round 1
 * used. The case-specific part is exactly two values — [spec] and [arm] — and both are handed in.
 *
 * The recorder is built inside the seam because it addresses things that only exist there — the live
 * container and the guest project dir it snapshots — and its `gitDir` points inside the container's
 * bind-mounted run directory so the patches and `checkpoints.json` are published as TeamCity artifacts
 * (see [checkpointGitDir]). A capture whose patches stayed container-local would have to be repeated.
 *
 * The model comes off the resolved session rather than from the system property, so the metadata records
 * the model that really ran instead of the one the operator meant to select.
 */
fun checkpointCaptureScenario(
    spec: RippleCheckpointCaseSpec,
    arm: String,
): DpaiaScenarioBaseTest = object : DpaiaScenarioBaseTest() {
    override val instanceId: String = spec.instanceId

    override val seams: DpaiaRunSeams = object : DpaiaRunSeams {
        override fun recorderFor(session: IntelliJContainer, projectDir: String) = RippleCheckpointRecorder(
            container = session.scope,
            projectDir = projectDir,
            gitDir = session.checkpointGitDir(),
            case = spec.instanceId,
            arm = arm,
            model = session.aiAgents.claude.asDockerClaudeSession().model,
        )

        /**
         * Everything the recording means, printed and exported.
         *
         * The order is deliberate: the verdict is printed BEFORE the patches are exported, so the numbers
         * are in the build log even if a whole-tree `git diff` later fails, and every `[CHECKPOINT]` line
         * stays greppable from a build log because that log is the only artifact an operator reads before
         * deciding whether to spend the 25 probe builds this capture would feed.
         */
        override fun afterAgentRun(outcome: DpaiaRunOutcome) {
            val recorder = outcome.recorder
                ?: error("a capture run must carry the recorder it was recorded with")
            val steps = recorder.stepCount()
            if (outcome.objectiveSuccess == null) {
                println(
                    "[CHECKPOINT]   note: the verifier produced no grade for this run, so admission " +
                        "judges it as a failure — the states are exported anyway and the operator decides"
                )
            }
            val admission = admitCapture(
                // No usable historical sample: the v3 bands belong to
                // ripple__keycloak__rename-method-wide, and a DPAIA case's own recorded passes were
                // measured on another model and an older harness. Either would reject or admit this
                // capture against a distribution it is not from.
                reference = null,
                success = outcome.objectiveSuccess == true,
                steps = steps,
                seconds = outcome.agentDurationMs / 1000,
                contextTokens = outcome.endContextTokens ?: 0L,
                // The grid is ten fractions of the edit phase, so a run with fewer tool calls than that
                // cannot place them on ten distinct states however early it started writing.
                checkpointCount = RIPPLE_CHECKPOINT_FRACTIONS,
            )
            println(
                "[CHECKPOINT] case=${outcome.instanceId} arm=$arm n=$steps " +
                    "endContextTokens=${outcome.endContextTokens ?: "unknown"} " +
                    "admitted=${admission.admitted}"
            )
            admission.reasons.forEach { println("[CHECKPOINT]   rejected: $it") }
            admission.notes.forEach { println("[CHECKPOINT]   note: $it") }

            // Round 2's upstream denominators: which tool each step was, and how many output tokens the
            // model had spent by then. Neither is derivable from the build log — summing the streamed
            // `usage` of capture 1 gives 642 output tokens against the 59 164 its result event reports —
            // so the hook records and the CLI's own transcript are published as artifacts. Printed
            // rather than asserted: a capture that recorded states but no transcript is still worth
            // keeping, and the free preflight is what refuses that shape before any money is spent.
            val hookRecords = recorder.hookRecordSteps()
            println(
                "[CHECKPOINT] hook records for ${hookRecords.size} of $steps steps" +
                    (if (hookRecords.size == steps) "" else "; missing ${(1..steps) - hookRecords}")
            )
            println("[CHECKPOINT] transcripts: ${recorder.exportStepRecords()}")

            // A patch for EVERY step, not only for the grid: round 2 selects its probed states by a rule
            // over the whole trajectory (REPLICATION-2.md), which can only see states that were
            // exported. The grid below stays exactly as round 1 published it, so the two rounds remain
            // comparable on the editFraction axis.
            recorder.exportEveryStepPatch(steps)

            // The fractions are derived HERE, from the edit phase that actually happened — see
            // selectCheckpoints. A capture whose work tree stopped changing publishes the repetition as
            // data (sameStateAs) instead of moving a checkpoint to a state the agent never held, and
            // distinct() keeps two fractions that round onto one step from being exported twice.
            val plan = recorder.plan(steps)
            plan.steps.distinct().forEach { step -> recorder.exportPatch(step) }
            recorder.exportMetadata(plan)
        }
    }
}

/**
 * The same scenario, addressed the way a capture build addresses it: by the arm TOKEN alone.
 *
 * The token is the one coordinate that names both the case and the arm (see [RippleCheckpointCaseSpec]),
 * so resolving the spec from it here is what keeps a per-case capture class from carrying a second,
 * independently editable copy of "which case am I" that could disagree with the registry the probe
 * reads.
 */
fun checkpointCaptureScenario(arm: String): DpaiaScenarioBaseTest =
    checkpointCaptureScenario(rippleCheckpointCaseOfArm(arm), arm)
