/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.asDockerClaudeSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The CAPTURE half of the solution-readiness pilot: one recorded `microshop-18` run per arm, whose
 * intermediate states become the checkpoints a bare probe is later restarted from.
 *
 * `microshop-18` and not the keycloak rename it started on, because a readiness CURVE needs a solution
 * that is built up gradually — see [RippleCheckpointCase] for the measurement that settled it.
 *
 * It does not extend [DpaiaScenarioBaseTest] on purpose. A subclass would inherit that class's four
 * graded `@Test` methods, so `--tests '*CheckpointCaptureTest*'` would spend four extra arena runs that
 * record nothing at all. Instead it HOLDS a scenario flow — an anonymous [DpaiaScenarioBaseTest], which
 * JUnit's own `IsPotentialTestContainer` never treats as a test class — and drives it through
 * [DpaiaScenarioBaseTest.runAgent] with a recorder attached through [DpaiaRunSeams]. The graded run is
 * therefore byte-identical to the one [DpaiaMicroshop18Test] performs, which is the whole point: a
 * checkpoint is only meaningful if the trajectory it was cut from is a trajectory this experiment really
 * measures.
 *
 * Nothing here asserts admission. [admitCapture]'s verdict is printed, and a capture that fails it is
 * still a real measurement of this arm — the operator, not the test, decides whether another Opus run is
 * worth its price. There is no historical sample for this case, so [admitCapture] is called with no
 * reference at all and says so in its notes rather than judging representativeness against a band
 * borrowed from a different case.
 *
 * The three test methods break the package's backticked-sentence naming convention on purpose: each is
 * selected INDIVIDUALLY by a TeamCity build (`-PtestFilter=*CheckpointCaptureTest.<method>`), and
 * TeamCity splits `gradleParams` on whitespace with no shell to re-join it, so a name containing spaces
 * cannot be addressed from CI. A filter that fails to narrow would run all three methods in one build —
 * two Opus captures plus the preflight, well past the build's own timeout.
 */
class DpaiaMicroshop18CheckpointCaptureTest {

    /**
     * The recorded mcp arm.
     *
     * The 180-minute budget is the one [DpaiaScenarioBaseTest] already gives every arm of this case: the
     * agent's own 90 minutes (`agentTimeoutSeconds = 5_400`), a 20-minute project-ready budget for a
     * 23-module reactor, plus the pre-agent whole-suite baseline and the post-agent verification, neither
     * of which is inside the agent's timer.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun captureMcpArm() = capture(withMcp = true)

    /** The recorded shell arm — the positive control the mcp curve is read against. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun captureShellArm() = capture(withMcp = false)

    /**
     * The instrument, proven on a throwaway container before either capture is paid for.
     *
     * Shared with the keycloak capture test, because nothing about it is case-specific — see
     * [runCheckpointHookPreflight] for what the two halves of it decide.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun hookPreflight() = runCheckpointHookPreflight()

    /**
     * One recorded arm: the case's own run, plus a recorder and a reader.
     *
     * The recorder is built inside the seam because it addresses things that only exist there — the live
     * container and the guest project dir it snapshots — and its `gitDir` points inside the container's
     * bind-mounted run directory so the patches and `checkpoints.json` are published as TeamCity
     * artifacts (see [checkpointGitDir]). A capture whose patches stayed container-local would have to be
     * repeated.
     *
     * The model comes off the resolved session rather than from the system property, so the metadata
     * records the model that really ran instead of the one the operator meant to select.
     */
    private fun capture(withMcp: Boolean) {
        val arm = if (withMcp) "mcp" else "none"
        microshop18CaptureScenario(arm).runAgent("claude", withMcp)
    }
}

/**
 * The recorded `microshop-18` scenario as an ANONYMOUS [DpaiaScenarioBaseTest], so JUnit can never
 * discover it.
 *
 * Anonymity is the mechanism, not an accident: JUnit Jupiter's `IsPotentialTestContainer` rejects
 * anonymous classes, so the four graded `@Test` methods this object inherits can never be collected and
 * run alongside a capture. A named subclass — even a private top-level one, which Kotlin compiles to a
 * package-private JVM class that JUnit's private-class filter does NOT exclude — would be picked up by
 * classpath scanning.
 */
private fun microshop18CaptureScenario(arm: String): DpaiaScenarioBaseTest = object : DpaiaScenarioBaseTest() {
    override val instanceId: String = RippleCheckpointCase.INSTANCE_ID

    override val seams: DpaiaRunSeams = object : DpaiaRunSeams {
        override fun recorderFor(session: IntelliJContainer, projectDir: String) = RippleCheckpointRecorder(
            container = session.scope,
            projectDir = projectDir,
            gitDir = session.checkpointGitDir(),
            case = RippleCheckpointCase.INSTANCE_ID,
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
                // No historical sample exists for this case: the v3 bands belong to
                // ripple__keycloak__rename-method-wide, this agent and this model, and borrowing them
                // would reject or admit this capture against a distribution it is not from.
                reference = null,
                success = outcome.objectiveSuccess == true,
                steps = steps,
                seconds = outcome.agentDurationMs / 1000,
                contextTokens = outcome.endContextTokens ?: 0L,
                checkpointCount = RIPPLE_CHECKPOINT_COUNT,
            )
            println(
                "[CHECKPOINT] case=${outcome.instanceId} arm=$arm n=$steps " +
                    "endContextTokens=${outcome.endContextTokens ?: "unknown"} " +
                    "admitted=${admission.admitted}"
            )
            admission.reasons.forEach { println("[CHECKPOINT]   rejected: $it") }
            admission.notes.forEach { println("[CHECKPOINT]   note: $it") }

            // The positions are derived HERE, from the length that actually happened, and only from
            // states that differ from each other — see selectCheckpoints. A capture whose work tree
            // stopped changing carries fewer than five points, and the plan says so out loud rather than
            // exporting the same patch under two names.
            val plan = recorder.plan(steps)
            plan.checkpoints.forEach { checkpoint -> recorder.exportPatch(checkpoint.step) }
            recorder.exportMetadata(plan)
        }
    }
}
