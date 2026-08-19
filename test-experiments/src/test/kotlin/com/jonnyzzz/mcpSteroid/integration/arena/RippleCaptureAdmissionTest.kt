/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The representativeness gate of the checkpoint pilot, pinned against the v3 sample it is derived from.
 *
 * The scenarios are the ways a capture can be unusable — it failed, it is an outlier, or it is too short
 * to carry five checkpoints — plus the shapes that must be admitted: a median run, and a run of a case
 * that has no historical sample to be judged against at all.
 *
 * They matter because the gate only ever runs once per arm, inside a ~50-minute Opus build, where a
 * wrong verdict either burns 25 probe builds on an atypical trajectory or throws away a perfectly good
 * $2 run. Both mistakes were made for real on 2026-08-18: the token criterion compared a run's
 * `input+output` against a v3 band measured as `input+cache_read+cache_creation+output`, so it rejected
 * both captures on a metric that could never be in band.
 */
class RippleCaptureAdmissionTest {
    private val mcp = v3RenameMethodWideReference.getValue("mcp")

    @Test
    fun `a median-looking successful run is admitted`() {
        val verdict = admitCapture(mcp, true, steps = 30, seconds = 685, contextTokens = 73019)
        assertTrue(verdict.admitted) { verdict.reasons.toString() }
    }

    @Test
    fun `a failed capture is rejected however typical its numbers look`() {
        val verdict = admitCapture(mcp, false, steps = 30, seconds = 685, contextTokens = 73019)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.any { it.contains("SUCCESS") }) { verdict.reasons.toString() }
    }

    @Test
    fun `an outlier is rejected and says which metric was out of band`() {
        val verdict = admitCapture(mcp, true, steps = 61, seconds = 685, contextTokens = 73019)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.single().contains("steps")) { verdict.reasons.toString() }
    }

    /**
     * Every step of a trajectory is snapshotted now, so "the run ended before the last snapshot" cannot
     * happen — the positions are computed FROM the measured length. What can still happen is a run so
     * short that five distinct pre-final positions do not exist in it.
     */
    @Test
    fun `a trajectory too short to carry five checkpoints is rejected`() {
        val verdict = admitCapture(mcp, true, steps = 5, seconds = 685, contextTokens = 73019)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.any { it.contains("5 checkpoints") }) { verdict.reasons.toString() }
    }

    /**
     * A case with no measured history cannot have a representativeness band, and inventing one would be
     * the worst of both worlds: a gate that looks objective while judging against numbers nobody
     * measured. The capture is admitted and the missing sample is stated as a NOTE, so the pilot's
     * report says "not judged" instead of implying "typical".
     */
    @Test
    fun `a case without a historical sample is admitted with the gap stated`() {
        val verdict = admitCapture(null, true, steps = 30, seconds = 685, contextTokens = 73019)
        assertTrue(verdict.admitted) { verdict.reasons.toString() }
        assertTrue(verdict.notes.any { it.contains("no historical sample") }) { verdict.notes.toString() }
    }

    @Test
    fun `wall time and context tokens are each their own criterion`() {
        val slowAndVerbose = admitCapture(
            mcp, true, steps = 30, seconds = 3_000, contextTokens = 200_000,
        )
        assertFalse(slowAndVerbose.admitted)
        assertEquals(2, slowAndVerbose.reasons.size) { slowAndVerbose.reasons.toString() }
        assertTrue(slowAndVerbose.reasons.any { it.contains("wall time") }) { slowAndVerbose.reasons.toString() }
        assertTrue(slowAndVerbose.reasons.any { it.contains("context tokens") }) {
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
        // The BAND is per arm because the arms differ: the shell arm takes more steps and fewer tokens,
        // so one shared band would admit an outlier of either.
        assertEquals(RIPPLE_CHECKPOINT_ARMS.toSet(), v3RenameMethodWideReference.keys)
    }
}
