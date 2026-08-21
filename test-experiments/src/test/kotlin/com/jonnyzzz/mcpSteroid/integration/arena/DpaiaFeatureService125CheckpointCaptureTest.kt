/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The CAPTURE half of the solution-readiness pilot: one recorded `feature-service-125` run per arm,
 * whose intermediate states become the checkpoints a bare probe is later restarted from.
 *
 * This case and neither of the two the pilot started on, because a readiness CURVE needs a solution
 * that is assembled out of independently landable parts and an oracle that really runs — see
 * [RippleCheckpointCase] for the two rejections that settled it. It is the case rounds 1 and 2
 * measured, and the reason its arm tokens are the bare `mcp`/`none` while every case added in round 3
 * carries a prefix — see [RippleCheckpointCaseSpec].
 *
 * It does not extend [DpaiaScenarioBaseTest] on purpose. A subclass would inherit that class's four
 * graded `@Test` methods, so `--tests '*CheckpointCaptureTest*'` would spend four extra arena runs that
 * record nothing at all. Instead it HOLDS a scenario flow — [checkpointCaptureScenario]'s anonymous
 * [DpaiaScenarioBaseTest], which JUnit's own `IsPotentialTestContainer` never treats as a test class —
 * and drives it through [DpaiaScenarioBaseTest.runAgent] with a recorder attached through
 * [DpaiaRunSeams]. The graded run is therefore byte-identical to the one [DpaiaFeatureService125Test]
 * performs, which is the whole point: a checkpoint is only meaningful if the trajectory it was cut from
 * is a trajectory this experiment really measures.
 *
 * Nothing here asserts admission. [admitCapture]'s verdict is printed, and a capture that fails it is
 * still a real measurement of this arm — the operator, not the test, decides whether another Opus run is
 * worth its price. The case does have a recorded history (one 900 s timeout and four passes at 638 s,
 * 444 s, 570 s and 403 s), but it was taken on a different model and a much older harness, so
 * [admitCapture] is called with no reference at all and says so in its notes rather than judging this
 * run's representativeness against a band it is not from.
 *
 * The three test methods break the package's backticked-sentence naming convention on purpose: each is
 * selected INDIVIDUALLY by a TeamCity build (`-PtestFilter=*CheckpointCaptureTest.<method>`), and
 * TeamCity splits `gradleParams` on whitespace with no shell to re-join it, so a name containing spaces
 * cannot be addressed from CI. A filter that fails to narrow would run all three methods in one build —
 * two Opus captures plus the preflight, well past the build's own timeout.
 */
class DpaiaFeatureService125CheckpointCaptureTest {

    /**
     * The recorded mcp arm.
     *
     * The 120-minute budget covers what this case's arm really costs and nothing more: the agent's own
     * 30 minutes (`agentTimeoutSeconds = 1_800`), a 10-minute project-ready budget, plus the pre-agent
     * whole-suite baseline and the post-agent verification — neither of which is inside the agent's
     * timer, and both of which run the Testcontainers oracle this case is chosen for.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    fun captureMcpArm() = capture(withMcp = true)

    /** The recorded shell arm — the positive control the mcp curve is read against. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    fun captureShellArm() = capture(withMcp = false)

    /**
     * The instrument, proven on a throwaway container before either capture is paid for.
     *
     * Shared with every other capture test, because nothing about it is case-specific — see
     * [runCheckpointHookPreflight] for what the two halves of it decide.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun hookPreflight() = runCheckpointHookPreflight()

    /**
     * One recorded arm: the case's own run, plus a recorder and a reader — see
     * [checkpointCaptureScenario], which every case's capture shares.
     *
     * The arm tokens are the bare `mcp`/`none`, and they must stay that way: rounds 1 and 2 published
     * every number under them and the committed states live in directories of those names. The case is
     * resolved FROM the token, so this class and the probe can never disagree about which trajectory
     * `mcp` is.
     */
    private fun capture(withMcp: Boolean) {
        val arm = if (withMcp) "mcp" else "none"
        checkpointCaptureScenario(arm).runAgent("claude", withMcp)
    }
}
