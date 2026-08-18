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

/**
 * The step count a capture run of each arm is EXPECTED to have, rounded from the v3 sample means
 * (mcp 31.6, none 39.9). Checkpoint positions are derived from these before the run, so the hook can
 * snapshot five states instead of every one of them.
 */
val RIPPLE_EXPECTED_STEPS: Map<String, Int> = mapOf("mcp" to 32, "none" to 40)

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
