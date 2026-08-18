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
    fun `both arms are probed at the very same steps`() {
        assertEquals(listOf(2, 6, 11, 17, 24), RIPPLE_CHECKPOINT_STEPS)
        assertEquals(rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS), RIPPLE_CHECKPOINT_STEPS)
        assertEquals(listOf("mcp", "none"), RIPPLE_CHECKPOINT_ARMS)
    }

    /**
     * The shared schedule is only usable if an admissible capture of EITHER arm reaches its last
     * position, and the mcp arm is the short one: `admitCapture` requires steps inside mean±1sd, i.e.
     * at least 25 tool calls, so `a_5 = 24` is the deepest checkpoint the pilot may schedule. One step
     * more and every below-average mcp capture would be rejected for a missing fifth state.
     */
    @Test
    fun `the last checkpoint stays inside what an admissible capture of either arm reaches`() {
        val minimumAdmissibleSteps = v3RenameMethodWideReference.values.minOf { reference ->
            Math.ceil(reference.stepsMean - reference.stepsSd).toInt()
        }
        assertTrue(RIPPLE_CHECKPOINT_STEPS.last() < minimumAdmissibleSteps) {
            "a_5=${RIPPLE_CHECKPOINT_STEPS.last()} is not reached by a capture of $minimumAdmissibleSteps steps"
        }
        assertTrue(rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS + 1).last() >= minimumAdmissibleSteps) {
            "n̂=${RIPPLE_EXPECTED_STEPS} is not the largest schedule both arms can carry"
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
