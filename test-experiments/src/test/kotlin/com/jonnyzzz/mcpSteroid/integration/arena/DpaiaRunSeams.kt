/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer

/**
 * The four places the solution-readiness pilot attaches to a DPAIA scenario run, and nothing else.
 *
 * Every member does nothing by default, and [NONE] — the set an ordinary scenario carries — overrides
 * none of them. That is the whole design constraint: a checkpoint is only worth measuring if the
 * trajectory it was cut from is a trajectory this experiment really runs, so the recorded run has to be
 * the SAME run `DpaiaFeatureService125Test` performs. Copying the flow into a second file would have
 * made the two drift apart silently — the arena flow deploys, gates the deploy path, takes a whole-suite
 * baseline, normalizes formatting, snapshots the oracle, runs the agent, grades, reports and asserts,
 * and every one of those steps is load-bearing for comparability.
 *
 * Why these four and not one: they happen at four different moments of one method, and each needs
 * something that exists only there.
 *
 * - [recorderFor] needs the live container and the guest project dir, and its hook must be installed
 *   after the harness's own pre-agent work (which is not the agent's trajectory) and before the agent's
 *   first tool call.
 * - [prepareTree] needs the same two plus the case the flow really grades, but must run AFTER the
 *   pre-agent baseline and BEFORE the tamper snapshot: a probe's starting state is part of what it
 *   inherits, not part of what it is blamed for.
 * - [decoratePrompt] sees the brief the graded scenario would have sent, so a probe cannot accidentally
 *   send a different task than the capture it is compared against.
 * - [afterAgentRun] needs the graded outcome, which only exists once the verifier has run.
 */
interface DpaiaRunSeams {
    /**
     * The checkpoint recorder to install on the Claude session, or null for an unrecorded run.
     *
     * A factory rather than a ready recorder because everything it addresses is created inside the run
     * flow. The flow installs it on Claude only — the recorder's seam is a Claude Code `--settings`
     * hook file, and another agent would produce a run indistinguishable from an unrecorded one.
     */
    fun recorderFor(session: IntelliJContainer, projectDir: String): RippleCheckpointRecorder? = null

    /**
     * Act on the deployed work tree before the agent starts — the probe applies its checkpoint patch here.
     *
     * [testCase] is the OVERLAY-AUGMENTED case, the one whose `failToPass` the run is graded on, so a
     * seam can check its own starting state against the oracle instead of against the dataset entry that
     * an overlay may have extended.
     */
    fun prepareTree(session: IntelliJContainer, projectDir: String, testCase: DpaiaTestCase) {}

    /** The prompt the agent receives, given the one the graded scenario would have sent. */
    fun decoratePrompt(prompt: String): String = prompt

    /** Read the finished run: the capture judges admission here, the probe prints its verdict. */
    fun afterAgentRun(outcome: DpaiaRunOutcome) {}

    companion object {
        /** What every DPAIA scenario test runs with: no recorder, no patch, the brief unchanged, no reader. */
        val NONE: DpaiaRunSeams = object : DpaiaRunSeams {}
    }
}

/**
 * One finished DPAIA run, as much of it as a seam needs to judge the run rather than repeat it.
 *
 * [verification] is nullable because the arena flow degrades an unreachable verifier to an unverified
 * record instead of failing the whole run — see [objectiveSuccess] for why that nullability must be
 * carried all the way to the reader instead of being collapsed into a false.
 */
data class DpaiaRunOutcome(
    val instanceId: String,
    val agentName: String,
    val modeLabel: String,
    val agentDurationMs: Long,
    /** End-of-run context size, the quantity [admitCapture] compares against a historical band. */
    val endContextTokens: Long?,
    val verification: ArenaVerificationResult?,
    /** The recorder that watched this run, or null when it was not recorded. */
    val recorder: RippleCheckpointRecorder?,
) {
    /**
     * The harness's verdict on the run: every FAIL_TO_PASS class green, nothing regressed, and the
     * oracle untouched — or NULL when the verifier never produced a grade at all.
     *
     * The null is the point. A probe cell publishes `Y=0` for "the agent could not finish from that
     * state" and `LOST` for "the instrument failed", and `V` is a fraction over the cells that were
     * graded. Collapsing an ungraded run into `false` would bias every readiness value downwards while
     * looking like a complete measurement — the one error in this pilot that leaves no trace in the log.
     */
    val objectiveSuccess: Boolean?
        get() = verification?.let { it.objectiveSuccess && !it.failToPassTampered }
}
