/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The CAPTURE half of round 3 for `dpaia__spring__petclinic__rest-37`: one recorded run per arm, whose
 * intermediate states become the checkpoints a bare probe is later restarted from.
 *
 * The heaviest oracle in round 3's set — 23 files, 37 KB of patch and 352 FAIL_TO_PASS tests — and a
 * from-scratch implementation task, which is the shape `feature-service-125` is NOT: a curve measured
 * here is what says whether readiness grows the same way when the solution is written rather than
 * navigated to.
 *
 * The recording itself is [checkpointCaptureScenario] — shared with every other case, because the six
 * new cases are only worth adding if they are measured by the very same instrument round 1 used.
 *
 * The three test methods break the package's backticked-sentence naming convention on purpose: each is
 * selected INDIVIDUALLY by a TeamCity build (`-PtestFilter=*CheckpointCaptureTest.<method>`), and
 * TeamCity splits `gradleParams` on whitespace with no shell to re-join it, so a name containing spaces
 * cannot be addressed from CI. A filter that fails to narrow would run all three methods in one build —
 * two Opus captures plus the preflight, well past the build's own timeout.
 */
class DpaiaPetclinicRest37CheckpointCaptureTest {

    /**
     * The recorded mcp arm.
     *
     * 210 minutes rather than the 120 the agent's own budget would suggest, because the agent's timer is
     * the smaller half of this case's cost: the raised `agentTimeoutSeconds = 1_800` (see
     * [DpaiaCuratedCases.CASE_CONFIGS], and the comment there for why 900 s could not stand), a
     * 10-minute project-ready budget, and then the pre-agent whole-suite baseline AND the post-agent
     * verification — each of them a full run of a 352-test suite over 23 files, and neither inside the
     * agent's timer. This is the same budget [DpaiaPetclinic71CheckpointCaptureTest] carries, for the
     * mirror-image reason: there the agent is slow, here the grading is.
     */
    @Test
    @Timeout(value = 210, unit = TimeUnit.MINUTES)
    fun captureMcpArm() = capture(withMcp = true)

    /** The recorded shell arm — the positive control the mcp curve is read against. */
    @Test
    @Timeout(value = 210, unit = TimeUnit.MINUTES)
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
     * One recorded arm, addressed by the token that also names the case.
     *
     * `pcr37-mcp`/`pcr37-none` and not `mcp`/`none`: a probe build forwards nothing but `arm`, `index`
     * and `replicate`, so the arm token is the only place the case can ride — see
     * [RippleCheckpointCaseSpec].
     */
    private fun capture(withMcp: Boolean) {
        val arm = if (withMcp) "pcr37-mcp" else "pcr37-none"
        checkpointCaptureScenario(arm).runAgent("claude", withMcp)
    }
}
