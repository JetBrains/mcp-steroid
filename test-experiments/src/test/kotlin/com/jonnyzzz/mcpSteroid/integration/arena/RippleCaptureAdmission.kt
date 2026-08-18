/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.util.Locale

/**
 * What a TYPICAL run of one arm of `rename-method-wide` looked like in the v3 series.
 *
 * A checkpoint capture is a sample of size one, and every `V(s_i)` the pilot publishes describes THAT
 * trajectory. So the only defence against reporting a readiness curve of an outlier is to check the
 * capture against the distribution it is supposed to represent before spending 25 probe builds on it.
 *
 * `sd` is the sample standard deviation of the v3 runs, not a tolerance someone picked: the band is
 * mean±1sd, so roughly the middle two thirds of the historical runs are admitted and the tails are not.
 */
data class CaptureReference(
    val arm: String,
    val stepsMin: Int,
    val stepsMax: Int,
    val stepsMean: Double,
    val stepsSd: Double,
    val secondsMean: Double,
    val secondsSd: Double,
    val tokensMean: Double,
    val tokensSd: Double,
)

/**
 * The measured v3 reference for `ripple__keycloak__rename-method-wide`, agent `claude`, per arm
 * (`mcp`: 9 runs, all SUCCESS; `none`: 10 runs, all SUCCESS).
 *
 * Written verbatim from the v3 feature table rather than recomputed here, so the numbers a capture is
 * judged against are the same ones the published report prints next to it. They are valid ONLY for
 * this case, this agent and `claude-opus-5`: any other cell needs its own sample before it can have a
 * gate.
 */
val v3RenameMethodWideReference: Map<String, CaptureReference> = mapOf(
    "mcp" to CaptureReference(
        arm = "mcp",
        stepsMin = 22, stepsMax = 41, stepsMean = 31.6, stepsSd = 7.1,
        secondsMean = 870.6, secondsSd = 410.5,
        tokensMean = 75069.6, tokensSd = 11820.4,
    ),
    "none" to CaptureReference(
        arm = "none",
        stepsMin = 31, stepsMax = 56, stepsMean = 39.9, stepsSd = 8.1,
        secondsMean = 749.2, secondsSd = 265.3,
        tokensMean = 66364.4, tokensSd = 7009.0,
    ),
)

/**
 * Whether a capture run may carry the pilot's checkpoints, and — when it may not — every reason.
 *
 * Every violated criterion is reported, not just the first: an operator deciding whether to spend
 * another Opus run needs to know if the capture missed the band by one metric or by four, and a
 * fail-fast verdict would hide that. The verdict is a printout, never an assertion — see
 * [RippleScenarioBaseTest] — because a rejected capture is a real measurement of the arm.
 */
data class CaptureAdmission(val admitted: Boolean, val reasons: List<String>)

/**
 * Judge one capture run against [reference].
 *
 * One reason per CRITERION, not per comparison: the step count is a single criterion satisfied by
 * being inside the v3 range AND inside mean±1sd, so an outlier at 61 steps produces one reason
 * naming both bounds rather than two rows saying the same thing twice.
 *
 * [lastCheckpointStep] is the fifth snapshot position. A run that ended at or before it never reached
 * that state, so its curve would have four points where the report promises five — a defect in the
 * INSTRUMENT's coverage, which is why it sits next to the representativeness criteria instead of being
 * discovered later by a probe build that cannot find its patch.
 */
fun admitCapture(
    reference: CaptureReference,
    success: Boolean,
    steps: Int,
    seconds: Long,
    endContextTokens: Long,
    lastCheckpointStep: Int,
): CaptureAdmission {
    val reasons = buildList {
        if (!success) {
            add(
                "SUCCESS was false — the ${reference.arm} arm of this case is historically all-pass, so a " +
                    "failed run is not a typical trajectory to take checkpoints from"
            )
        }
        val stepsBand = band(reference.stepsMean, reference.stepsSd)
        if (steps !in reference.stepsMin..reference.stepsMax || steps.toDouble() !in stepsBand) {
            add(
                "steps=$steps is outside the v3 ${reference.arm} sample: range " +
                    "${reference.stepsMin}..${reference.stepsMax}, mean±1sd ${stepsBand.render()}"
            )
        }
        val secondsBand = band(reference.secondsMean, reference.secondsSd)
        if (seconds.toDouble() !in secondsBand) {
            add(
                "agent wall time ${seconds}s is outside the v3 ${reference.arm} mean±1sd " +
                    secondsBand.render()
            )
        }
        val tokensBand = band(reference.tokensMean, reference.tokensSd)
        if (endContextTokens.toDouble() !in tokensBand) {
            add(
                "end-context tokens $endContextTokens are outside the v3 ${reference.arm} mean±1sd " +
                    tokensBand.render()
            )
        }
        if (steps <= lastCheckpointStep) {
            add(
                "the run stopped at $steps tool calls, at or before the last snapshot position " +
                    "$lastCheckpointStep, so it has no fifth checkpoint to probe"
            )
        }
    }
    return CaptureAdmission(admitted = reasons.isEmpty(), reasons = reasons)
}

private fun band(mean: Double, sd: Double): ClosedFloatingPointRange<Double> = (mean - sd)..(mean + sd)

private fun ClosedFloatingPointRange<Double>.render(): String =
    String.format(Locale.ROOT, "%.1f..%.1f", start, endInclusive)
