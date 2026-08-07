/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for [buildRunSummaryJson] — no Docker/container involved. Confirms the objective
 * FAIL_TO_PASS verification grade, the objective verdict, the claim-vs-reality flag and both tamper
 * signals always make it into the arena run summary, whether or not [ArenaVerifier.verify] ran.
 */
class RunSummaryJsonTest {

    private fun minimalRecord(
        claimedFix: Boolean,
        verification: ArenaVerificationResult?,
        baselinePassing: Int? = 10,
        baselineAlreadyFailing: Int? = 0,
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
        baselinePassing = baselinePassing,
        baselineAlreadyFailing = baselineAlreadyFailing,
    )

    private fun verification(
        perClass: List<SurefireClassResult>,
        failToPassTampered: Boolean = false,
        collateralTestFilesEdited: List<String> = emptyList(),
        regressions: List<String> = emptyList(),
        baselineAvailable: Boolean = true,
        regressionScanTruncated: Boolean = false,
    ) = ArenaVerificationResult(
        perClass = perClass,
        failToPassTampered = failToPassTampered,
        collateralTestFilesEdited = collateralTestFilesEdited,
        regressions = regressions,
        baselineAvailable = baselineAvailable,
        regressionScanTruncated = regressionScanTruncated,
        verificationDurationMs = 42L,
    )

    @Test
    fun `run summary json includes verified fields when verification succeeded and matches the claim`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = verification(listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0))),
        )
        val json = buildRunSummaryJson(rec)

        assertTrue("verified_ftp_passed" in json.keys)
        assertTrue("verified_ftp_total" in json.keys)
        assertTrue("verified_ftp_rate" in json.keys)
        assertTrue("objective_success" in json.keys)
        assertTrue("claim_matches_reality" in json.keys)
        assertTrue("fail_to_pass_tampered" in json.keys)
        assertTrue("collateral_test_files_edited" in json.keys)
        assertTrue("regressions" in json.keys)
        assertTrue("regression_count" in json.keys)
        assertTrue("baseline_passing" in json.keys)
        assertTrue("baseline_already_failing" in json.keys)

        assertEquals(1, json["verified_ftp_passed"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, json["verified_ftp_total"]?.jsonPrimitive?.intOrNull)
        assertEquals(1.0, json["verified_ftp_rate"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(true, json["objective_success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, json["claim_matches_reality"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, json["fail_to_pass_tampered"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(0, json["regression_count"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `claim_matches_reality is false when the agent claimed a fix the verifier disproves`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = verification(
                listOf(
                    SurefireClassResult("com.example.FooTest", 3, 1, 0, 0),
                    SurefireClassResult("com.example.BarTest", 2, 0, 0, 0),
                ),
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertEquals(1, json["verified_ftp_passed"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, json["verified_ftp_total"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, json["objective_success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, json["claim_matches_reality"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `a regression denies objective success even with every FAIL_TO_PASS class green`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = verification(
                perClass = listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0)),
                regressions = listOf("com.example.OtherTest"),
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertEquals(1.0, json["verified_ftp_rate"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(false, json["objective_success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, json["claim_matches_reality"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, json["regression_count"]?.jsonPrimitive?.intOrNull)
        assertEquals("com.example.OtherTest", json["regressions"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `an agent that solved the task but refused the marker reads as an under-claim, not a wrong claim`() {
        // The train-ticket-1 / petclinic-27 shape: FAIL_TO_PASS all green, no regression, yet the agent
        // withheld the success marker because the whole suite was red for reasons it did not cause.
        // objective_success stays true; claim_matches_reality goes false — a conservative agent, not a
        // false claim, and the two are now distinguishable in the data.
        val rec = minimalRecord(
            claimedFix = false,
            verification = verification(listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0))),
            baselineAlreadyFailing = 17,
        )
        val json = buildRunSummaryJson(rec)

        assertEquals(true, json["objective_success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, json["claim_matches_reality"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(17, json["baseline_already_failing"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `fail_to_pass_tampered surfaces true when an oracle file was touched after the agent ran`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = verification(
                perClass = listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0)),
                failToPassTampered = true,
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertEquals(true, json["fail_to_pass_tampered"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `collateral test edits are recorded without denying objective success`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = verification(
                perClass = listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0)),
                collateralTestFilesEdited = listOf("src/test/java/A.java", "src/test/java/B.java"),
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertEquals(false, json["fail_to_pass_tampered"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, json["objective_success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(
            "src/test/java/A.java;src/test/java/B.java",
            json["collateral_test_files_edited"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `regressions read as unknown rather than none when no baseline was taken`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = verification(
                perClass = listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0)),
                baselineAvailable = false,
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertTrue("regression_count" in json.keys)
        assertNull(json["regression_count"]?.jsonPrimitive?.intOrNull)
        assertNull(json["regressions"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `a truncated regression scan is marked so a zero is not read as proof of none`() {
        val rec = minimalRecord(
            claimedFix = true,
            verification = verification(
                perClass = listOf(SurefireClassResult("com.example.FooTest", 3, 0, 0, 0)),
                regressionScanTruncated = true,
            ),
        )
        val json = buildRunSummaryJson(rec)

        assertEquals(0, json["regression_count"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, json["regression_scan_truncated"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `run summary json keeps the verified keys present with null values on verifier infra failure`() {
        val rec = minimalRecord(claimedFix = true, verification = null)
        val json = buildRunSummaryJson(rec)

        assertTrue("verified_ftp_passed" in json.keys)
        assertTrue("verified_ftp_total" in json.keys)
        assertTrue("verified_ftp_rate" in json.keys)
        assertTrue("objective_success" in json.keys)
        assertTrue("fail_to_pass_tampered" in json.keys)
        assertNull(json["verified_ftp_passed"]?.jsonPrimitive?.intOrNull)
        assertNull(json["verified_ftp_rate"]?.jsonPrimitive?.doubleOrNull)
        assertNull(json["objective_success"]?.jsonPrimitive?.booleanOrNull)
        assertNull(json["fail_to_pass_tampered"]?.jsonPrimitive?.booleanOrNull)

        // No verification available → cannot confirm the claim against reality.
        assertEquals(false, json["claim_matches_reality"]?.jsonPrimitive?.booleanOrNull)
    }
}
