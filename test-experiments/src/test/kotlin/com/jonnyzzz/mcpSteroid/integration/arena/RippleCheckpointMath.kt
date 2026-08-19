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
 * One probe point of a recorded trajectory.
 *
 * [nominalStep] is where the schedule of [rippleCheckpointSteps] put this checkpoint and [step] is
 * where it was actually taken. They differ whenever the nominal step held a state already probed —
 * see [selectCheckpoints] — and both are reported, because the correction moves the checkpoint deeper
 * into the trajectory and a reader comparing curves has to see that it happened.
 */
data class CheckpointSelection(
    val index: Int,
    val nominalStep: Int,
    val step: Int,
    val position: Double,
)

/**
 * The checkpoints of one capture run, plus every correction the selection had to make.
 *
 * [corrections] is part of the measurement, not a log: a plan carrying three checkpoints instead of
 * five is a statement about the recorded trajectory (it stopped changing the work tree), and the
 * report has to print the reason next to the shortened curve.
 */
data class CheckpointPlan(
    val n: Int,
    val checkpoints: List<CheckpointSelection>,
    val corrections: List<String>,
) {
    val steps: List<Int> get() = checkpoints.map { it.step }
}

/**
 * Picks the probe points of a trajectory of [n] steps, given the state each step left behind.
 *
 * [stateIdOf] identifies the work tree after a step — a git tree id in the capture, a string in the
 * tests. Two checkpoints with the same state id are the same experiment run twice: the mcp capture of
 * build 1034656372 finished the whole rename at step 11 and touched no file afterwards, so its
 * scheduled `step-11` and `step-17` patches were byte-identical, and probing both would have reported
 * one measured state as two points of a readiness curve.
 *
 * So a checkpoint whose nominal position repeats the previous checkpoint's state moves FORWARD to the
 * first step that differs — the smallest correction that keeps the sequence strictly increasing, and
 * the one the pilot's specification allows. When no differing state exists before the final step, the
 * checkpoint is dropped instead of duplicated: fewer honest points beat five points of which two are
 * the same measurement. The final state itself is never a checkpoint, which is why the search stops at
 * `n - 1`.
 */
fun selectCheckpoints(n: Int, count: Int = 5, stateIdOf: (Int) -> String): CheckpointPlan {
    val nominal = rippleCheckpointSteps(n, count)
    val corrections = mutableListOf<String>()
    val chosen = mutableListOf<CheckpointSelection>()
    var previousStep = 0
    var previousState: String? = null

    nominal.forEachIndexed { zeroBased, nominalStep ->
        val index = zeroBased + 1
        val from = maxOf(nominalStep, previousStep + 1)
        val step = (from until n).firstOrNull { candidate -> stateIdOf(candidate) != previousState }
        if (step == null) {
            corrections += "checkpoint $index (nominal step $nominalStep): no differing state before the " +
                "final step $n — the trajectory stopped changing the work tree after step $previousStep, " +
                "so this checkpoint would have repeated that state"
            return@forEachIndexed
        }
        if (step != nominalStep) {
            corrections += "checkpoint $index: nominal step $nominalStep held the state already probed at " +
                "step $previousStep, moved to $step"
        }
        chosen += CheckpointSelection(
            index = index,
            nominalStep = nominalStep,
            step = step,
            position = step.toDouble() / n,
        )
        previousStep = step
        previousState = stateIdOf(step)
    }
    return CheckpointPlan(n = n, checkpoints = chosen, corrections = corrections)
}

/** The two arms of the pilot: the source trajectory either had MCP Steroid or had nothing but a shell. */
val RIPPLE_CHECKPOINT_ARMS: List<String> = listOf("mcp", "none")

/**
 * How many checkpoints one capture's readiness curve is measured at.
 *
 * The POSITIONS are not a constant, and deliberately so. They are `round(n·(i/6)^1.5)` of each capture's
 * own measured length — see [selectCheckpoints] — because a schedule fixed before the run has to guess
 * `n`, and the guess of 32 that this pilot started with met runs of 23 and 51 tool calls: the first had
 * no fifth state at all, the second put its deepest checkpoint at 47% of a trajectory instead of 76%.
 * Curves of two arms are therefore compared at equal NORMALIZED positions `a_i/n`, not at equal
 * tool-call counts.
 */
val RIPPLE_CHECKPOINT_COUNT: Int = 5

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
