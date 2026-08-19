/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for the arithmetic the checkpoint pilot is built on — no Docker, no agent.
 *
 * Two properties are worth a test each. The positions must be the SAME numbers the capture hook was
 * given before the run, because a report that normalizes by a different schedule than the one the
 * snapshots were taken with silently mislabels every measured state. And the area under the readiness
 * curve must stay inside the range that was actually measured: an AUC that quietly extrapolates to 0
 * or to the end of the trajectory would be the loudest number in the report and none of it measured.
 */
class RippleCheckpointMathTest {
    @Test
    fun `positions follow the 1_5 power schedule`() {
        assertEquals(listOf(2, 6, 11, 17, 24), rippleCheckpointSteps(32))
        assertEquals(listOf(3, 8, 14, 22, 30), rippleCheckpointSteps(40))
    }

    @Test
    fun `the pilot measures five checkpoints per arm`() {
        assertEquals(5, RIPPLE_CHECKPOINT_COUNT)
        assertEquals(RIPPLE_CHECKPOINT_COUNT, rippleCheckpointSteps(32).size)
        assertEquals(listOf("mcp", "none"), RIPPLE_CHECKPOINT_ARMS)
    }

    /**
     * The deepest checkpoint must be reachable by ANY run the gate admits, whatever its length — which is
     * exactly what deriving positions from the measured `n` buys. The v3 sample's extremes are the
     * regression: the pilot's first schedule was fixed at n̂=32, and the mcp arm's 23-step run then had
     * no state at its `a_5 = 24` to probe at all.
     */
    @Test
    fun `the deepest checkpoint is strictly inside the trajectory for every admissible length`() {
        val lengths = v3RenameMethodWideReference.values.flatMap { listOf(it.stepsMin, it.stepsMax) }
        lengths.forEach { n ->
            val steps = rippleCheckpointSteps(n)
            assertTrue(steps.last() < n) { "a_5=${steps.last()} is not inside a $n-step trajectory" }
            assertEquals(RIPPLE_CHECKPOINT_COUNT, steps.size) { "n=$n -> $steps" }
        }
    }

    @Test
    fun `short trajectories are nudged into a strictly increasing sequence`() {
        val steps = rippleCheckpointSteps(8)
        assertEquals(steps.sorted(), steps)
        assertEquals(steps.distinct(), steps)
        assertTrue(steps.all { it in 1..7 }) { "no checkpoint may be the final state: $steps" }
    }

    @Test
    fun `every trajectory length yields five distinct in-range checkpoints`() {
        (6..100).forEach { n ->
            val steps = rippleCheckpointSteps(n)
            assertEquals(5, steps.size, "n=$n")
            assertEquals(steps.distinct(), steps, "n=$n -> $steps")
            assertEquals(steps.sorted(), steps, "n=$n -> $steps")
            assertTrue(steps.last() < n, "n=$n -> $steps")
        }
    }

    @Test
    fun `n below six cannot carry five distinct checkpoints`() {
        assertThrows(IllegalArgumentException::class.java) { rippleCheckpointSteps(5) }
    }

    @Test
    fun `a trajectory whose every step differs is probed at the formula positions`() {
        val selection = selectCheckpoints(n = 32) { step -> "state-$step" }

        assertEquals(listOf(2, 6, 11, 17, 24), selection.steps)
        assertEquals(listOf(2, 6, 11, 17, 24), selection.checkpoints.map { it.nominalStep })
        assertTrue(selection.corrections.isEmpty()) { "nothing to correct: ${selection.corrections}" }
    }

    /**
     * The defect this whole selection exists for. The mcp capture of build 1034656372 wrote the entire
     * rename at step 11 and touched no file afterwards, so `step-11` and `step-17` were BYTE-IDENTICAL
     * patches — two probes of one state, reported as two points of a curve.
     */
    @Test
    fun `a checkpoint that would repeat the previous state moves to the next differing step`() {
        val selection = selectCheckpoints(n = 32) { step -> if (step < 11) "pristine" else "solved" }

        assertEquals(listOf(2, 11), selection.steps.take(2))
        assertEquals(2, selection.checkpoints.size) { "only two states exist: ${selection.checkpoints}" }
        assertTrue(selection.corrections.any { it.contains("11") }) { "${selection.corrections}" }
    }

    @Test
    fun `positions are normalized by the measured n`() {
        val selection = selectCheckpoints(n = 23) { step -> "state-$step" }

        assertEquals(listOf(2, 4, 8, 13, 17), selection.steps)
        assertEquals(2.0 / 23.0, selection.checkpoints.first().position, 1e-9)
        assertEquals(17.0 / 23.0, selection.checkpoints.last().position, 1e-9)
    }

    @Test
    fun `a state repeated to the end of the trajectory drops the checkpoints it would duplicate`() {
        val selection = selectCheckpoints(n = 20) { step -> if (step < 3) "pristine" else "solved" }

        assertEquals(listOf(1, 4), selection.steps)
        assertTrue(selection.corrections.any { it.contains("no differing state") }) {
            "${selection.corrections}"
        }
    }

    @Test
    fun `readiness is the success fraction`() {
        assertEquals(0.0, checkpointReadiness(0, 5))
        assertEquals(0.4, checkpointReadiness(2, 5))
        assertEquals(1.0, checkpointReadiness(5, 5))
    }

    @Test
    fun `auc integrates only the observed range`() {
        val curve = ReadinessCurve(listOf(
            ReadinessPoint(0.1, 0.0),
            ReadinessPoint(0.2, 0.5),
            ReadinessPoint(0.5, 1.0),
        ))
        // trapezoids: (0+0.5)/2*0.1 + (0.5+1.0)/2*0.3 = 0.025 + 0.225
        assertEquals(0.25, curve.auc, 1e-9)
        assertEquals(0.1, curve.rangeFrom, 1e-9)
        assertEquals(0.5, curve.rangeTo, 1e-9)
    }

    @Test
    fun `auc refuses to extrapolate a single point`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadinessCurve(listOf(ReadinessPoint(0.1, 1.0))).auc
        }
    }
}
