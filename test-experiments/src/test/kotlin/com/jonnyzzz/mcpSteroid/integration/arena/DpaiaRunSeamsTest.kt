/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The seams the readiness pilot hangs off the DPAIA run flow, decided offline.
 *
 * Two things have to be true at once and neither is visible from a single Docker run: the pilot must be
 * able to record, patch and read a run, and a DPAIA scenario that asks for none of that must behave
 * exactly as it did before the seams existed. So the no-op set is asserted to be the one an ordinary
 * scenario carries, and the verdict arithmetic — which decides whether a probe cell publishes a zero or
 * withholds one — is decided here rather than by reading a probe build's log.
 */
class DpaiaRunSeamsTest {
    @Test
    fun `an ordinary dpaia scenario test carries the no-op seams`() {
        assertSame(DpaiaRunSeams.NONE, DpaiaFeatureService125Test().seams) {
            "a DPAIA scenario that never asked to be recorded must run the flow it always ran"
        }
    }

    @Test
    fun `the no-op seam leaves the agent prompt byte-identical`() {
        val prompt = "Validate every release status transition.\nARENA_FIX_APPLIED: yes"
        assertEquals(prompt, DpaiaRunSeams.NONE.decoratePrompt(prompt))
    }

    /**
     * The distinction the pilot cannot afford to lose: an instrument failure withholds a verdict, it
     * does not report a zero. `V` is a fraction of probes that FINISHED, so folding an ungraded cell in
     * as a failure biases every readiness value downwards while looking like a complete measurement.
     */
    @Test
    fun `a run the verifier never graded has no verdict at all`() {
        assertNull(outcome(verification = null).objectiveSuccess)
    }

    @Test
    fun `a tampered run is not a success even with every FAIL_TO_PASS class green`() {
        val tampered = outcome(verification = verification(passed = true, tampered = true))
        assertFalse(tampered.objectiveSuccess!!)
    }

    @Test
    fun `a graded, untampered, green run is a success`() {
        assertTrue(outcome(verification = verification(passed = true, tampered = false)).objectiveSuccess!!)
        assertFalse(outcome(verification = verification(passed = false, tampered = false)).objectiveSuccess!!)
    }

    /**
     * A dead API connection is carried BESIDE the grade, never folded into it.
     *
     * [DpaiaRunOutcome.objectiveSuccess] answers "what does the tree look like now", which the verifier
     * measured and which a transport abort cannot change retroactively. Whether such a run may be
     * PUBLISHED is a different question, answered by the reader that grades cells — collapsing the two
     * here would hide from every other seam that the connection died.
     */
    @Test
    fun `a transport abort leaves the verifier's own grade untouched`() {
        val aborted = outcome(
            verification = verification(passed = false, tampered = false),
            apiTransportError = "API Error: Connection closed mid-response.",
        )
        assertFalse(aborted.objectiveSuccess!!)
        assertEquals("API Error: Connection closed mid-response.", aborted.apiTransportError)
        assertNull(outcome(verification = verification(passed = true, tampered = false)).apiTransportError)
    }

    /**
     * The agent's own budget running out is carried BESIDE the grade as well.
     *
     * Same reason as the transport abort: the verifier's answer to "what does the tree look like now" is
     * unaffected by how the run ended, and the reader that grades cells is the one place allowed to turn
     * an ungraded budget exhaustion into a zero. Folding it into [DpaiaRunOutcome.objectiveSuccess] here
     * would make an ungraded timeout indistinguishable from a graded failure for every other seam.
     */
    @Test
    fun `an exhausted agent budget leaves the verifier's own grade untouched`() {
        val exhausted = outcome(verification = null, agentTimedOut = true)
        assertNull(exhausted.objectiveSuccess)
        assertTrue(exhausted.agentTimedOut)
        assertFalse(outcome(verification = verification(passed = true, tampered = false)).agentTimedOut)
    }

    /**
     * The pilot's case id is spelled once and checked against the curated registry, because every other
     * place it appears — the capture, the probe, the committed resource directory — reads it from here.
     * A typo would deploy a case the dataset does not carry, hours into a build.
     */
    @Test
    fun `the probed case is a curated dpaia case`() {
        assertTrue(RippleCheckpointCase.INSTANCE_ID in DpaiaCuratedCases.CASE_CONFIGS) {
            "${RippleCheckpointCase.INSTANCE_ID} is not a curated case, so it has no resource limits " +
                "and no project-ready timeout of its own"
        }
    }

    private fun outcome(
        verification: ArenaVerificationResult?,
        apiTransportError: String? = null,
        agentTimedOut: Boolean = false,
    ) = DpaiaRunOutcome(
        instanceId = RippleCheckpointCase.INSTANCE_ID,
        agentName = "claude",
        modeLabel = "mcp",
        agentDurationMs = 1_000L,
        endContextTokens = 60_927L,
        costUsd = 0.3278,
        apiTransportError = apiTransportError,
        agentTimedOut = agentTimedOut,
        verification = verification,
        recorder = null,
    )

    private fun verification(passed: Boolean, tampered: Boolean) = ArenaVerificationResult(
        perClass = listOf(
            SurefireClassResult(
                className = "com.sivalabs.ft.features.ReleaseStatusTransitionValidatorTest",
                testsRun = 3,
                failures = if (passed) 0 else 1,
                errors = 0,
                skipped = 0,
            )
        ),
        failToPassTampered = tampered,
        collateralTestFilesEdited = emptyList(),
        regressions = emptyList(),
        baselineAvailable = true,
        verificationDurationMs = 1L,
    )
}
