/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * Where along a trajectory of [n] tool calls the readiness probes start.
 *
 * `round(n·(i/6)^1.5)` puts the five checkpoints at ≈7/19/35/54/76% — dense at the beginning, where a
 * trajectory changes the most per step, and still reaching deep into the solution. The final state is
 * deliberately excluded: the source run's own outcome is a separate fact, not a probe point.
 *
 * Rounding collides on short trajectories, and a collision would silently measure the same state
 * twice, so a duplicate is pushed up by the smallest amount that keeps the sequence strictly
 * increasing and below [n].
 */
fun rippleCheckpointSteps(n: Int, count: Int = 5): List<Int> {
    require(n > count) { "a trajectory of $n steps cannot carry $count distinct pre-final checkpoints" }
    val raw = (1..count).map { i ->
        Math.round(n * Math.pow(i.toDouble() / (count + 1), 1.5)).toInt().coerceAtLeast(1)
    }
    val fixed = mutableListOf<Int>()
    raw.forEach { candidate ->
        val previous = fixed.lastOrNull() ?: 0
        fixed += maxOf(candidate, previous + 1)
    }
    require(fixed.last() < n) { "checkpoints $fixed reach the final state of a $n-step trajectory" }
    return fixed
}

/** The two arms of the pilot: the source trajectory either had MCP Steroid or had nothing but a shell. */
val RIPPLE_CHECKPOINT_ARMS: List<String> = listOf("mcp", "none")

/**
 * The ONE assumed trajectory length both arms' checkpoints are derived from.
 *
 * Shared on purpose: `V_mcp` and `V_shell` are only comparable when both curves are measured at the
 * SAME tool-call counts, so a per-arm schedule would compare readiness after 24 mcp calls against
 * readiness after 30 shell calls and call the difference an arm effect.
 *
 * 32 is not the pooled mean (36) but the largest shared value both arms can actually reach: the v3
 * mcp sample is the shorter one (31.6±7.1 steps), so [admitCapture] only admits mcp captures of ≥25
 * tool calls, and `round(33·(5/6)^1.5) = 25` would already put the fifth snapshot beyond an admissible
 * run. It happens to equal the rounded mcp mean.
 */
val RIPPLE_EXPECTED_STEPS: Int = 32

/**
 * The five tool-call counts every capture run of this pilot snapshots at — `2, 6, 11, 17, 24`.
 *
 * Computed once, before any run: a snapshot is a full `git add -A` over the whole Keycloak tree, so
 * snapshotting every step would add tens of tree scans inside the measured agent loop and distort the
 * trajectory it records.
 */
val RIPPLE_CHECKPOINT_STEPS: List<Int> = rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS)

/**
 * `V(s_i)` for one checkpoint: the fraction of probes that finished the task from that state.
 *
 * Both bounds are required rather than clamped, because a count outside them can only come from a
 * miscounted verdict set, and a readiness silently clipped into `[0, 1]` would look like a
 * measurement.
 */
fun checkpointReadiness(successes: Int, runs: Int): Double {
    require(runs > 0) { "a readiness value needs at least one run" }
    require(successes in 0..runs) { "$successes successes out of $runs runs" }
    return successes.toDouble() / runs
}

/** One measured point of the curve: [position] is `a_i/n` of the capture run, [readiness] is `V(s_i)`. */
data class ReadinessPoint(val position: Double, val readiness: Double)

/**
 * The measured readiness curve and its area, integrated ONLY between the first and the last
 * checkpoint. Nothing is extrapolated to 0 or past the last checkpoint: readiness there was not
 * measured, and an implied value would be the loudest number in the report.
 */
data class ReadinessCurve(val points: List<ReadinessPoint>) {
    val rangeFrom: Double get() = ordered.first().position
    val rangeTo: Double get() = ordered.last().position

    val auc: Double
        get() {
            require(ordered.size >= 2) { "an area needs at least two measured checkpoints" }
            return ordered.zipWithNext().sumOf { (a, b) ->
                (a.readiness + b.readiness) / 2.0 * (b.position - a.position)
            }
        }

    private val ordered: List<ReadinessPoint> get() = points.sortedBy { it.position }
}
