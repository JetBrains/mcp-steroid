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
data class CaptureAdmission(
    val admitted: Boolean,
    val reasons: List<String>,
    val notes: List<String> = emptyList(),
)

/**
 * Judge one capture run against [reference], or — when there is no [reference] — against nothing but
 * its own usability.
 *
 * One reason per CRITERION, not per comparison: the step count is a single criterion satisfied by
 * being inside the v3 range AND inside mean±1sd, so an outlier at 61 steps produces one reason
 * naming both bounds rather than two rows saying the same thing twice.
 *
 * [contextTokens] must be measured the SAME way the reference's `tokensMean` was — as the end-of-run
 * cumulative context, `input + cache_read + cache_creation + output` of the last assistant message (see
 * [extractEndContextTokens]). Passing a run's `input + output` instead compares two different
 * quantities: on 2026-08-18 that mistake rejected both captures of the pilot for being ~17k against a
 * band of 63k..87k, a comparison no run could ever pass.
 *
 * [reference] is null for a case with no measured sample of its own. A band cannot be invented for it,
 * so representativeness is not judged at all and the gap is reported as a note — the capture is still
 * checked for the two things that make it unusable regardless of history: it must have SUCCEEDED, and
 * it must be long enough to carry [checkpointCount] distinct pre-final checkpoints.
 */
fun admitCapture(
    reference: CaptureReference?,
    success: Boolean,
    steps: Int,
    seconds: Long,
    contextTokens: Long,
    checkpointCount: Int = 5,
): CaptureAdmission {
    val arm = reference?.arm ?: "this"
    val reasons = buildList {
        if (!success) {
            add(
                "SUCCESS was false — the $arm arm of this case is historically all-pass, so a " +
                    "failed run is not a typical trajectory to take checkpoints from"
            )
        }
        if (steps <= checkpointCount) {
            add(
                "the run made only $steps tool calls, which cannot carry $checkpointCount checkpoints " +
                    "strictly before its final state"
            )
        }
        if (reference == null) return@buildList

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
        if (contextTokens.toDouble() !in tokensBand) {
            add(
                "end-of-run context tokens $contextTokens are outside the v3 ${reference.arm} mean±1sd " +
                    tokensBand.render()
            )
        }
    }
    val notes = buildList {
        if (reference == null) {
            add(
                "representativeness was NOT judged: no historical sample exists for this case, so this " +
                    "capture is a sample of one and the report must say so instead of implying it is typical"
            )
        }
    }
    return CaptureAdmission(admitted = reasons.isEmpty(), reasons = reasons, notes = notes)
}

private fun band(mean: Double, sd: Double): ClosedFloatingPointRange<Double> = (mean - sd)..(mean + sd)

private fun ClosedFloatingPointRange<Double>.render(): String =
    String.format(Locale.ROOT, "%.1f..%.1f", start, endInclusive)
