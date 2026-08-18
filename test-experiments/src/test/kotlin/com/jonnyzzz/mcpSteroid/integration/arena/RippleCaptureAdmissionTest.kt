/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The representativeness gate of the checkpoint pilot, pinned against the v3 sample it is derived from.
 *
 * The scenarios are the four ways a capture can be unusable — it failed, it is an outlier, or it never
 * reached the last snapshot — plus the one shape that must be admitted. They matter because the gate
 * only ever runs once per arm, inside a 50-minute Opus build, where a wrong verdict either burns 25
 * probe builds on an atypical trajectory or throws away a perfectly good $2 run.
 */
class RippleCaptureAdmissionTest {
    private val mcp = v3RenameMethodWideReference.getValue("mcp")

    @Test
    fun `a median-looking successful run is admitted`() {
        val verdict = admitCapture(mcp, true, steps = 30, seconds = 685, endContextTokens = 73019, lastCheckpointStep = 24)
        assertTrue(verdict.admitted) { verdict.reasons.toString() }
    }

    @Test
    fun `a failed capture is rejected however typical its numbers look`() {
        val verdict = admitCapture(mcp, false, steps = 30, seconds = 685, endContextTokens = 73019, lastCheckpointStep = 24)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.any { it.contains("SUCCESS") }) { verdict.reasons.toString() }
    }

    @Test
    fun `an outlier is rejected and says which metric was out of band`() {
        val verdict = admitCapture(mcp, true, steps = 61, seconds = 685, endContextTokens = 73019, lastCheckpointStep = 24)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.single().contains("steps")) { verdict.reasons.toString() }
    }

    @Test
    fun `a run that ended before the last snapshot point is rejected`() {
        val verdict = admitCapture(mcp, true, steps = 23, seconds = 685, endContextTokens = 73019, lastCheckpointStep = 24)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.any { it.contains("24") }) { verdict.reasons.toString() }
    }

    @Test
    fun `wall time and end-context tokens are each their own criterion`() {
        val slowAndVerbose = admitCapture(
            mcp, true, steps = 30, seconds = 3_000, endContextTokens = 200_000, lastCheckpointStep = 24,
        )
        assertFalse(slowAndVerbose.admitted)
        assertEquals(2, slowAndVerbose.reasons.size) { slowAndVerbose.reasons.toString() }
        assertTrue(slowAndVerbose.reasons.any { it.contains("wall time") }) { slowAndVerbose.reasons.toString() }
        assertTrue(slowAndVerbose.reasons.any { it.contains("end-context tokens") }) {
            slowAndVerbose.reasons.toString()
        }
    }

    @Test
    fun `every reference arm carries the v3 sample it was derived from`() {
        assertEquals(setOf("mcp", "none"), v3RenameMethodWideReference.keys)
        // Verbatim from the v3 series (claude, rename-method-wide). Pinned so a later edit to the gate
        // cannot quietly widen the band the pilot's one capture per arm is admitted by.
        assertEquals(
            CaptureReference(
                arm = "mcp",
                stepsMin = 22, stepsMax = 41, stepsMean = 31.6, stepsSd = 7.1,
                secondsMean = 870.6, secondsSd = 410.5,
                tokensMean = 75069.6, tokensSd = 11820.4,
            ),
            mcp,
        )
        assertEquals(
            CaptureReference(
                arm = "none",
                stepsMin = 31, stepsMax = 56, stepsMean = 39.9, stepsSd = 8.1,
                secondsMean = 749.2, secondsSd = 265.3,
                tokensMean = 66364.4, tokensSd = 7009.0,
            ),
            v3RenameMethodWideReference.getValue("none"),
        )
        // The gate exists per arm because the arms differ: the shell arm takes more steps and fewer
        // tokens, so one shared band would admit an outlier of either.
        assertEquals(v3RenameMethodWideReference.keys, RIPPLE_EXPECTED_STEPS.keys)
    }
}
