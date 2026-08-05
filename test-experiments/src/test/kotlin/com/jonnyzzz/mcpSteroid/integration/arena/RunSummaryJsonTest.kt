/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for [buildRunSummaryJson] — no Docker/container involved. Confirms the objective
 * FAIL_TO_PASS verification grade, the claim-vs-reality flag, and the tamper flag always make it into
 * the arena run summary, whether or not [ArenaVerifier.verify] ran.
 */
class RunSummaryJsonTest {

    private fun minimalRecord(
        claimedFix: Boolean,
        verification: ArenaVerificationResult?,
    ) = DpaiaScenarioBaseTest.RunRecord(
        instanceId = "dpaia__example__case-1",
        agentName = "claude",
        withMcp = true,
        agentDurationMs = 1_000L,
        prewarmMs = 0L,
        exitCode = 0,
        claimedFix = claimedFix,
        usedMcpSteroid = true,
        summary = "did the thing",
        tokenUsage = null,
        testMetrics = null,
        verification = verification,
    )

    @Test
    fun `run summary json includes verified fields when verification succeeded and matches the claim`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = ArenaVerificationResult(
                perClass = listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0)),
                testsTampered = false,
                verificationDurationMs = 42L,
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertTrue("verified_ftp_passed" in json.keys)
        assertTrue("verified_ftp_total" in json.keys)
        assertTrue("verified_ftp_rate" in json.keys)
        assertTrue("claim_matches_reality" in json.keys)
        assertTrue("tests_tampered" in json.keys)

        assertEquals(1, json["verified_ftp_passed"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, json["verified_ftp_total"]?.jsonPrimitive?.intOrNull)
        assertEquals(1.0, json["verified_ftp_rate"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(true, json["claim_matches_reality"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, json["tests_tampered"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `claim_matches_reality is false when the agent claimed a fix the verifier disproves`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = ArenaVerificationResult(
                perClass = listOf(
                    SurefireClassResult("com.example.FooTest", 3, 1, 0, 0),
                    SurefireClassResult("com.example.BarTest", 2, 0, 0, 0),
                ),
                testsTampered = false,
                verificationDurationMs = 42L,
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertEquals(1, json["verified_ftp_passed"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, json["verified_ftp_total"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, json["claim_matches_reality"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `tests_tampered surfaces true when a FAIL_TO_PASS file was touched after the agent ran`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = ArenaVerificationResult(
                perClass = listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0)),
                testsTampered = true,
                verificationDurationMs = 42L,
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertEquals(true, json["tests_tampered"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `run summary json keeps the verified keys present with null values on verifier infra failure`() {
        val rec = minimalRecord(claimedFix = true, verification = null)
        val json = buildRunSummaryJson(rec)

        assertTrue("verified_ftp_passed" in json.keys)
        assertTrue("verified_ftp_total" in json.keys)
        assertTrue("verified_ftp_rate" in json.keys)
        assertTrue("tests_tampered" in json.keys)
        assertNull(json["verified_ftp_passed"]?.jsonPrimitive?.intOrNull)
        assertNull(json["verified_ftp_rate"]?.jsonPrimitive?.doubleOrNull)
        assertNull(json["tests_tampered"]?.jsonPrimitive?.booleanOrNull)

        // No verification available → cannot confirm the claim against reality.
        assertEquals(false, json["claim_matches_reality"]?.jsonPrimitive?.booleanOrNull)
    }
}
