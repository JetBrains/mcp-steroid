/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The CAPTURE half of round 3 for `dpaia__jhipster__sample__app-3`: one recorded run per arm, whose
 * intermediate states become the checkpoints a bare probe is later restarted from.
 *
 * A generated-code repository, which is its own kind of trajectory: most of what an agent reads here is
 * scaffolding it did not write and cannot infer from, and the measured 1.30x says MCP did not rescue it.
 * Whether a state in such a project carries the same readiness as one in a hand-written project is a
 * question only a second case shape can answer.
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
class DpaiaJhipster3CheckpointCaptureTest {

    /**
     * The recorded mcp arm.
     *
     * 120 minutes: the agent's own 15 (this case takes the default `agentTimeoutSeconds = 900`), a
     * 10-minute project-ready budget, plus the pre-agent whole-suite baseline and the post-agent
     * verification — neither of which is inside the agent's timer. The pilot's own capture carries the
     * same 120 minutes with a DOUBLE agent budget, so the headroom here is strictly larger.
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
     * One recorded arm, addressed by the token that also names the case.
     *
     * `jh3-mcp`/`jh3-none` and not `mcp`/`none`: a probe build forwards nothing but `arm`, `index` and
     * `replicate`, so the arm token is the only place the case can ride — see [RippleCheckpointCaseSpec].
     */
    private fun capture(withMcp: Boolean) {
        val arm = if (withMcp) "jh3-mcp" else "jh3-none"
        checkpointCaptureScenario(arm).runAgent("claude", withMcp)
    }
}
