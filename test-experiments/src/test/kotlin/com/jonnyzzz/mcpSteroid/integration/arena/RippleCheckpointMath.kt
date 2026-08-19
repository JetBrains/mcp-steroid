/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/**
 * How many even fractions of the edit phase the pilot probes: `tau_k = k/10` for `k = 0 … 9`.
 *
 * Ten because the axis is a FRACTION and the arms must be readable against each other at the same
 * coordinate, so the resolution has to be a property of the instrument rather than of whichever arm is
 * being read. `tau = 1` is not among them on purpose — the final state is the source run's own outcome,
 * which the arena grades separately, and probing it would fold that grade into the curve.
 *
 * It replaces both earlier schedules, and the reasons are measured rather than aesthetic. The relative
 * grid `round(n·(i/6)^1.5)` normalized by each arm's whole `n`, which includes the turns spent reading
 * before a single byte was written — the mcp capture wrote nothing until its 15th of 26 tool calls, so
 * more than half of its "trajectory" was not trajectory at all. The absolute grid that replaced it made
 * the opposite mistake: the two captures wrote for 11 and 40 tool calls, so "edit turn 4" is a third of
 * one arm's work and a tenth of the other's, and comparing them answers nothing.
 */
val RIPPLE_CHECKPOINT_FRACTIONS: Int = 10

/**
 * The steps of a trajectory of [n] tool calls that sit at the [fractions] even fractions of its edit
 * phase, which begins at [firstWriteStep].
 *
 * `step_k = firstWriteStep + round(k/fractions · (n - firstWriteStep))`, so the first entry IS the first
 * write and the last one sits below the final state. Rounding is half-up, which is why the mcp arm's
 * midpoint is step 21 (`15 + round(5.5)`) and not step 20.
 *
 * An edit phase shorter than [fractions] cannot give every fraction a step of its own, and the last
 * fractions of a very short one round onto the final state itself. Both are handled by clamping to the
 * step before the end rather than by shortening the list: the coordinate grid is the same ten fractions
 * for every capture — that is the whole point of a fractional axis — and a repeated step is already a
 * shape [selectCheckpoints] publishes as data.
 */
fun rippleCheckpointSteps(
    n: Int,
    firstWriteStep: Int,
    fractions: Int = RIPPLE_CHECKPOINT_FRACTIONS,
): List<Int> {
    require(fractions > 0) { "a fractional grid needs a positive number of fractions, got $fractions" }
    require(firstWriteStep in 1 until n) {
        "the edit phase of a $n-step trajectory cannot start at tool call $firstWriteStep — a capture " +
            "that wrote nothing before its final state has no edit phase to take fractions of"
    }
    val editPhase = n - firstWriteStep
    return (0 until fractions).map { k ->
        val offset = (k.toDouble() / fractions * editPhase).roundToInt()
        firstWriteStep + minOf(offset, editPhase - 1)
    }
}

/**
 * One probe point of a recorded trajectory.
 *
 * [editFraction] is the NOMINAL `k/fractions` this point was cut at, not `(step - firstWriteStep)/E`
 * recomputed from the rounded step. It is the axis both arms are drawn on, and the two arms round onto
 * different steps — recomputing would give the mcp arm 0.091 where the shell arm has 0.100 and silently
 * stagger the two curves that exist to be compared.
 *
 * [position] is `step/n` of the capture that produced the state. Carried NEXT TO [editFraction] rather
 * than instead of it, because the two answer different questions: [editFraction] is how far through its
 * own EDIT PHASE the agent had come, which is comparable across arms, while [position] is how far
 * through the whole trajectory — readable only inside one arm, since the arms differ both in `n` and in
 * how long they read before writing.
 *
 * [sameStateAs] names the earliest fraction's step whose work tree is byte-identical to this one, or
 * null when this checkpoint is the first to hold its state. See [selectCheckpoints] for why a repetition
 * is recorded instead of removed.
 */
data class CheckpointSelection(
    val index: Int,
    val step: Int,
    val editFraction: Double,
    val position: Double,
    val stateId: String,
    val sameStateAs: Int?,
)

/**
 * The checkpoints of one capture run, together with the edit phase they were cut over.
 *
 * [firstWriteStep] and [fractions] are part of the record and not derivable from [checkpoints] alone: a
 * reader who only sees the steps cannot tell where this run stopped reading and started writing, and
 * that boundary is what every published fraction is measured from.
 */
data class CheckpointPlan(
    val n: Int,
    val firstWriteStep: Int,
    val fractions: Int,
    val checkpoints: List<CheckpointSelection>,
) {
    val steps: List<Int> get() = checkpoints.map { it.step }
}

/**
 * The probe points of a trajectory of [n] steps, given the state each step left behind.
 *
 * [stateIdOf] identifies the work tree after a step — a git tree id in the capture, a string in the
 * tests — and step 0 is the pristine tree the capture started from. Two decisions live here, and each
 * of them is a reversal of an earlier rule.
 *
 * **The phase is normalized, and it starts at the first write.** The turns before a capture's first
 * write are the agent reading the material: 14 of the mcp arm's 26 tool calls and 16 of the shell arm's
 * 57. Counting them into the axis compares reading with writing. Counting the edit turns absolutely is
 * no better — the two arms wrote for 11 and 40 tool calls, so the same absolute turn is a completely
 * different share of the work in each — which leaves the FRACTION of the edit phase as the only
 * coordinate under which "the mcp arm at 30% of its edits" and "the shell arm at 30% of its edits" name
 * comparable states. [firstWriteStep] is therefore derived from the states themselves: the earliest step
 * whose tree differs from step 0's.
 *
 * **A repeated state is DATA.** Two checkpoints holding the same tree used to be "one experiment run
 * twice", so the later one moved forward to the first differing step or was dropped. That correction
 * destroys the measurement it was protecting: the mcp capture wrote nothing between its 19th and 24th
 * tool call, and "half way through its edit phase this agent paused for three checkpoints, and a weak
 * continuation from there succeeds no more often than five turns earlier" is exactly the finding the
 * pilot is looking for. Moving those points forward would relabel a flat stretch as progress, and
 * dropping them would leave the curve's densest region unmeasured.
 *
 * What the repetition still deserves is a name, so nobody pays twice for a tree the pilot already
 * probed: [CheckpointSelection.sameStateAs] points at the EARLIEST fraction's step holding the same
 * tree, which is what lets a caller reuse that cell's verdicts instead of buying five more probe runs.
 * Earliest and not "the previous one", because a trajectory can hold several flat stretches — the shell
 * capture has two — and a predecessor-only comparison would report the second stretch against the first.
 */
fun selectCheckpoints(
    n: Int,
    fractions: Int = RIPPLE_CHECKPOINT_FRACTIONS,
    stateIdOf: (Int) -> String,
): CheckpointPlan {
    val firstWriteStep = firstWriteStepOf(n, stateIdOf)
    val steps = rippleCheckpointSteps(n = n, firstWriteStep = firstWriteStep, fractions = fractions)
    val firstStepHoldingState = mutableMapOf<String, Int>()
    val checkpoints = steps.mapIndexed { k, step ->
        val stateId = stateIdOf(step)
        CheckpointSelection(
            index = k + 1,
            step = step,
            editFraction = publishedFraction(k.toDouble() / fractions),
            position = step.toDouble() / n,
            stateId = stateId,
            // putIfAbsent returns the value already stored, i.e. the earliest step holding this tree,
            // and null on the first sighting — which is precisely the sameStateAs contract.
            sameStateAs = firstStepHoldingState.putIfAbsent(stateId, step),
        )
    }
    return CheckpointPlan(
        n = n,
        firstWriteStep = firstWriteStep,
        fractions = fractions,
        checkpoints = checkpoints,
    )
}

/**
 * The tool call at which the capture first changed the work tree — the origin of the edit-phase axis.
 *
 * Searched strictly before [n], because a run whose only change is its final state has no state a probe
 * could be started from. That is an error and not an empty plan: the caller has just paid for an Opus
 * run, and ten checkpoints of the pristine tree would be published as a readiness curve.
 */
fun firstWriteStepOf(n: Int, stateIdOf: (Int) -> String): Int {
    val pristine = stateIdOf(0)
    return (1 until n).firstOrNull { stateIdOf(it) != pristine }
        ?: error(
            "none of the $n recorded steps changed the work tree before the final state, so this " +
                "capture has no edit phase — there is nothing to take a fraction of, and every " +
                "checkpoint would hand a probe the pristine tree"
        )
}

/** The two arms of the pilot: the source trajectory either had MCP Steroid or had nothing but a shell. */
val RIPPLE_CHECKPOINT_ARMS: List<String> = listOf("mcp", "none")

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

/**
 * One measured point of a curve: [readiness] is `V(s_i)` at [coordinate].
 *
 * The coordinate is deliberately unnamed. The pilot draws the same readiness against two axes — the
 * edit fraction, which the arms share, and `step/n`, which is readable only inside one arm — and a
 * type that called its abscissa `editFraction` would have been used for the position axis anyway, with
 * every printed range then claiming to be a fraction of an edit phase.
 */
data class ReadinessPoint(val coordinate: Double, val readiness: Double)

/**
 * The measured readiness curve and its area, integrated ONLY between the first and the last
 * checkpoint. Nothing is extrapolated to 0 or past the last checkpoint: readiness there was not
 * measured, and an implied value would be the loudest number in the report.
 */
data class ReadinessCurve(val points: List<ReadinessPoint>) {
    val rangeFrom: Double get() = ordered.first().coordinate
    val rangeTo: Double get() = ordered.last().coordinate

    val auc: Double
        get() {
            require(ordered.size >= 2) { "an area needs at least two measured checkpoints" }
            return ordered.zipWithNext().sumOf { (a, b) ->
                (a.readiness + b.readiness) / 2.0 * (b.coordinate - a.coordinate)
            }
        }

    private val ordered: List<ReadinessPoint> get() = points.sortedBy { it.coordinate }
}

/**
 * `k/fractions` at the three decimals every published artifact carries.
 *
 * A fraction is a JOIN KEY — the probe echoes it on its verdict line, the aggregator groups by it and
 * the two arms are laid over each other on it — so the rounding has to happen once, here, rather than
 * in each reader. Three decimals resolve a hundred times finer than the ten fractions this pilot cuts,
 * which leaves room for a denser grid without changing the shape of the published files.
 */
private fun publishedFraction(fraction: Double): Double =
    BigDecimal(fraction).setScale(FRACTION_DECIMALS, RoundingMode.HALF_UP).toDouble()

private const val FRACTION_DECIMALS: Int = 3
