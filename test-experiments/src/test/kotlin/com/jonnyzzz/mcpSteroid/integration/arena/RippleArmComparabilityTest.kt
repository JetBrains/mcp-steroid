/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for the tool-usage split and the comparability verdict — no Docker involved.
 *
 * The point of the gate is that a run which pays for the IDE and does not use it must be visible as
 * such, and that a run whose transcript could not be read must NOT be confused with it. Both shapes
 * are asserted here, against transcripts in the two decoded formats the harness really produces.
 */
class RippleArmComparabilityTest {

    // A Claude decoded transcript: the IDE tool appears under its full MCP name, the shell as `Bash`.
    private val claudeMcpDecodedLog = """
        >> mcp__mcp-steroid__steroid_execute_code (find usages of setRealm)
        >> Bash (mvn -q -pl server-spi test-compile)
        >> Bash (grep -rn setRealm .)
        >> mcp__mcp-steroid__steroid_execute_code (rename the method)
        >> Read (/project/KeycloakContext.java)
        >> Edit (/project/KeycloakContext.java)
        >> Grep (setRealm)
    """.trimIndent()

    // A Codex decoded transcript: no `Bash` tool at all — the shell is invoked directly.
    private val codexShellDecodedLog = """
        >> /bin/bash -lc 'grep -rn setRealm .'
        >> exit 0 (0.4s)
        >> /bin/bash -lc 'sed -i s/setRealm/setActiveRealm/g file.java'
        >> Read (/project/KeycloakContext.java)
    """.trimIndent()

    // Two tool_use blocks, one of whose results came back as an error.
    private val rawNdjsonWithOneToolError = """
        {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__mcp-steroid__steroid_execute_code"}]}}
        {"type":"user","message":{"content":[{"type":"tool_result","is_error":true}]}}
        {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash"}]}}
        {"type":"user","message":{"content":[{"type":"tool_result","is_error":false}]}}
    """.trimIndent()

    private fun decodedOf(log: String): DecodedLogMetrics =
        extractDecodedLogMetrics(log) ?: error("The fixture transcript holds no tool lines:\n$log")

    private fun toolStatsOf(ndjson: String): ToolCallStats =
        extractToolCallStats(ndjson) ?: error("The fixture NDJSON holds no tool_use blocks:\n$ndjson")

    @Test
    fun `a claude mcp transcript yields the tool split the log prints`() {
        val decoded = decodedOf(claudeMcpDecodedLog)
        assertEquals(2, decoded.execCodeCalls)
        assertEquals(2, decoded.bashCalls)
        assertEquals(7, decoded.totalToolCalls())
    }

    @Test
    fun `a codex transcript counts its direct shell invocations, not its exit echoes`() {
        val decoded = decodedOf(codexShellDecodedLog)
        assertEquals(0, decoded.execCodeCalls)
        assertEquals(2, decoded.bashCalls)
        assertEquals(3, decoded.totalToolCalls())
    }

    @Test
    fun `the printed tools line carries every count and names its error source`() {
        val decoded = decodedOf(claudeMcpDecodedLog)
        val stats = toolStatsOf(rawNdjsonWithOneToolError)
        val comparability = rippleArmComparability(withMcp = true, decoded = decoded, toolStats = stats)

        val lines = rippleToolUsageLines(comparability, decoded)
        val tools = lines.single { it.contains("tools:") }
        assertTrue(tools.contains("7 total"), tools)
        assertTrue(tools.contains("2 steroid"), tools)
        assertTrue(tools.contains("2 bash"), tools)
        val errors = lines.single { it.contains("tool errors:") }
        assertTrue(errors.startsWith("[RIPPLE]   tool errors:"), errors)
        assertTrue(errors.contains("1 (from the raw NDJSON"), errors)
        assertTrue(lines.any { it.contains("comparable:") }, lines.toString())
    }

    @Test
    fun `the tool error count stays out of the total so the two sources are never summed`() {
        val decoded = decodedOf(claudeMcpDecodedLog)
        val stats = toolStatsOf(rawNdjsonWithOneToolError)
        val comparability = rippleArmComparability(withMcp = true, decoded = decoded, toolStats = stats)

        assertEquals(7, comparability.totalToolCalls)
        assertEquals(1, comparability.toolErrorCount)
    }

    @Test
    fun `an arm below the threshold is not comparable and says why`() {
        // 6 IDE calls against 38 shell commands — the shape build 1032465247 showed. Used here as an
        // ILLUSTRATION with an explicit threshold passed in; it is not the source of any constant.
        val decoded = DecodedLogMetrics(
            execCodeCalls = 6, readCalls = 0, writeCalls = 0, bashCalls = 38,
        )
        val comparability = rippleArmComparability(
            withMcp = true,
            decoded = decoded,
            toolStats = null,
            ideCallShareThreshold = 0.5,
        )

        assertEquals(RippleComparabilityVerdict.NOT_COMPARABLE, comparability.verdict)
        assertFalse(comparability.comparable)
        assertTrue(comparability.reason.isNotBlank())
        assertTrue(comparability.reason.contains("6 of 44"), comparability.reason)
    }

    @Test
    fun `an arm at or above the threshold is comparable and still states its arithmetic`() {
        val decoded = DecodedLogMetrics(
            execCodeCalls = 30, readCalls = 0, writeCalls = 0, bashCalls = 10,
        )
        val comparability = rippleArmComparability(
            withMcp = true,
            decoded = decoded,
            toolStats = null,
            ideCallShareThreshold = 0.5,
        )

        assertEquals(RippleComparabilityVerdict.COMPARABLE, comparability.verdict)
        assertTrue(comparability.comparable)
        assertTrue(comparability.reason.isNotBlank())
        assertEquals(0.75, comparability.ideCallShare)
    }

    @Test
    fun `no threshold is fixed yet, so a real mcp arm records its counts and is not judged`() {
        val decoded = decodedOf(claudeMcpDecodedLog)
        val comparability = rippleArmComparability(withMcp = true, decoded = decoded, toolStats = null)

        // Guards the deliberate emptiness of RIPPLE_IDE_CALL_SHARE_THRESHOLD: a number picked from a
        // single remembered build is a fitted number, and this test must be updated together with the
        // KDoc that records which series the threshold came from.
        assertNull(RIPPLE_IDE_CALL_SHARE_THRESHOLD)
        assertEquals(RippleComparabilityVerdict.UNKNOWN, comparability.verdict)
        assertFalse(comparability.comparable)
        assertTrue(comparability.reason.contains("no threshold is fixed yet"), comparability.reason)
        assertEquals(2, comparability.steroidCalls)
    }

    @Test
    fun `a missing transcript is unknown, never not-comparable`() {
        val comparability = rippleArmComparability(
            withMcp = true,
            decoded = null,
            toolStats = null,
            ideCallShareThreshold = 0.5,
        )

        assertEquals(RippleComparabilityVerdict.UNKNOWN, comparability.verdict)
        assertNull(comparability.steroidCalls)
        assertNull(comparability.ideCallShare)
        assertTrue(comparability.reason.contains("UNAVAILABLE"), comparability.reason)
        assertTrue(rippleToolUsageLines(comparability, null).single { it.contains("tools:") }
            .contains("UNAVAILABLE"))
    }

    @Test
    fun `the shell arm is comparable by construction`() {
        val decoded = decodedOf(codexShellDecodedLog)
        val comparability = rippleArmComparability(
            withMcp = false,
            decoded = decoded,
            toolStats = null,
            ideCallShareThreshold = 0.5,
        )

        assertEquals(RippleComparabilityVerdict.COMPARABLE, comparability.verdict)
        assertTrue(comparability.reason.contains("no IDE access"), comparability.reason)
        assertEquals(0, comparability.steroidCalls)
    }

    @Test
    fun `the run summary carries the ripple grade, not just the shared objective verdict`() {
        val decoded = decodedOf(claudeMcpDecodedLog)
        val comparability = rippleArmComparability(withMcp = true, decoded = decoded, toolStats = null)
        val record = DpaiaScenarioBaseTest.RunRecord(
            instanceId = "ripple__keycloak__change-signature-wide",
            agentName = "claude",
            withMcp = true,
            agentDurationMs = 1_000L,
            prewarmMs = 0L,
            exitCode = 0,
            claimedFix = true,
            usedMcpSteroid = true,
            summary = "renamed it",
            tokenUsage = null,
            testMetrics = null,
            decodedLogMetrics = decoded,
            rippleSummary = RippleRunSummary(
                comparability = comparability,
                compileGatePassed = true,
                // The shape the shared summary cannot express: FAIL_TO_PASS green, an extra
                // predicate red, so ripple's own verdict is a failure while objective_success is not.
                allPredicatesPassed = false,
                rippleSuccess = false,
                recall = 1.0,
                precision = 1.0,
                f1 = 1.0,
                missedSiteCount = 0,
                overReachedDecoyCount = 0,
                p1NoAliasAndNewNameDeclared = true,
                p2AllSitesConverted = true,
                p3DecoysUnchanged = true,
                p4Conserved = true,
                p6ImportCountUnchanged = null,
                extraPredicates = mapOf("P5_ARITY" to false),
                goldReferences = 109,
                goldFiles = 40,
                goldDecoys = 37,
            ),
        )

        val ripple = buildRunSummaryJson(record)["ripple"]!!.jsonObject
        assertEquals(false, ripple["ripple_success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, ripple["compile_gate_passed"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, ripple["all_predicates_passed"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1.0, ripple["f1"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(109, ripple["gold_references"]?.jsonPrimitive?.intOrNull)
        assertEquals(
            false,
            ripple["extra_predicates"]!!.jsonObject["P5_ARITY"]?.jsonPrimitive?.booleanOrNull,
        )

        val c = ripple["comparability"]!!.jsonObject
        assertEquals("UNKNOWN", c["verdict"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, c["comparable"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(2, c["steroid_calls"]?.jsonPrimitive?.intOrNull)
        assertEquals(7, c["total_tool_calls"]?.jsonPrimitive?.intOrNull)
        assertTrue(c["reason"]?.jsonPrimitive?.contentOrNull!!.isNotBlank())
    }

    @Test
    fun `a dpaia run summary has no ripple section at all`() {
        val record = DpaiaScenarioBaseTest.RunRecord(
            instanceId = "dpaia__example__case-1",
            agentName = "claude",
            withMcp = false,
            agentDurationMs = 1L,
            prewarmMs = 0L,
            exitCode = 0,
            claimedFix = false,
            usedMcpSteroid = false,
            summary = null,
            tokenUsage = null,
            testMetrics = null,
        )

        assertNull(buildRunSummaryJson(record)["ripple"])
    }
}
