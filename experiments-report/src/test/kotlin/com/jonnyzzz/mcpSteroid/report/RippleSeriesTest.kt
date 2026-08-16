package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The repeated-run aggregate for the semantic-ripple family.
 *
 * Every case here is a mistake the plan review named in advance: reading the series off the
 * latest-only view, pairing arms that never ran together, averaging a run whose cost measures the
 * overhead of HAVING the IDE, calling a difference that is inside the spread, and publishing n=1.
 */
class RippleSeriesTest {

    private fun run(
        buildId: Long,
        mode: McpMode,
        cost: Double,
        turns: Int = 10,
        verdict: String? = "COMPARABLE",
        reason: String? = "the mcp arm made 30 of 40 tool calls against the IDE",
        rippleSuccess: Boolean? = true,
        tampered: Boolean? = false,
        exitCode: Int? = 0,
        durationMs: Long = 300_000,
        cacheRead: Long? = 100_000,
        input: Long? = 5_000,
        output: Long? = 20_000,
    ) = AgentRun(
        scenario = "ripple__keycloak__rename-method-wide",
        agent = "claude",
        mode = mode,
        buildId = buildId,
        exitCode = exitCode,
        costUsd = cost,
        numTurns = turns,
        agentDurationMs = durationMs,
        cacheReadTokens = cacheRead,
        inputTokens = input,
        outputTokens = output,
        rippleSuccess = rippleSuccess,
        failToPassTampered = tampered,
        comparabilityVerdict = verdict,
        comparabilityReason = reason,
    )

    @Test
    fun `three repeats give a median, a spread and a named paired difference`() {
        val runs = listOf(
            run(1, McpMode.WITH, 6.0), run(1, McpMode.WITHOUT, 8.0),
            run(2, McpMode.WITH, 7.0), run(2, McpMode.WITHOUT, 9.0),
            run(3, McpMode.WITH, 8.0), run(3, McpMode.WITHOUT, 11.0),
        )
        val series = rippleSeries(runs).single()

        assertEquals(7.0, series.withMcp!!.medianCostUsd)
        assertEquals(Spread(6.0, 8.0), series.withMcp.costSpread)
        assertEquals(9.0, series.without!!.medianCostUsd)
        assertEquals(3, series.pairedCostDeltas.size)
        assertEquals(-2.0, series.medianPairedCostDelta)
        assertTrue(series.statement.contains("cheaper"), series.statement)
        assertTrue(series.statement.contains("n=3"), series.statement)
    }

    @Test
    fun `a difference that straddles zero is not named`() {
        val runs = listOf(
            run(1, McpMode.WITH, 6.0), run(1, McpMode.WITHOUT, 8.0),
            run(2, McpMode.WITH, 9.0), run(2, McpMode.WITHOUT, 8.0),
            run(3, McpMode.WITH, 8.0), run(3, McpMode.WITHOUT, 8.5),
        )
        val series = rippleSeries(runs).single()

        assertTrue(series.statement.contains("within spread"), series.statement)
        assertTrue(!series.statement.contains("cheaper"), series.statement)
    }

    @Test
    fun `two repeats refuse to name anything, however clean`() {
        val runs = listOf(
            run(1, McpMode.WITH, 1.0), run(1, McpMode.WITHOUT, 9.0),
            run(2, McpMode.WITH, 1.0), run(2, McpMode.WITHOUT, 9.0),
        )
        val series = rippleSeries(runs).single()

        assertTrue(series.statement.contains("insufficient repeats"), series.statement)
        assertTrue(series.statement.contains("n=2"), series.statement)
    }

    @Test
    fun `a not-comparable mcp arm keeps its quality but leaves the cost aggregate with its reason`() {
        val notComparable = run(
            2, McpMode.WITH, cost = 99.0, verdict = "NOT_COMPARABLE",
            reason = "the mcp arm made 6 of 44 tool calls against the IDE",
        )
        val runs = listOf(run(1, McpMode.WITH, 6.0), notComparable, run(3, McpMode.WITH, 8.0))
        val leg = rippleSeries(runs).single().withMcp!!

        assertEquals(3, leg.attempts)
        assertEquals(2, leg.includedInCost)
        assertEquals(7.0, leg.medianCostUsd, "the 99.0 run must not move the median")
        // Quality is a separate question and survives the cost exclusion.
        assertEquals(3, leg.qualityKnown)
        assertEquals(3, leg.rippleSuccesses)
        assertEquals(1, leg.exclusions.size)
        assertEquals(2L, leg.exclusions.single().buildId)
        assertTrue(leg.exclusions.single().reason.contains("6 of 44"), leg.exclusions.single().reason)
    }

    @Test
    fun `a tampered run is excluded whatever its comparability says`() {
        val runs = listOf(
            run(1, McpMode.WITH, 6.0),
            run(2, McpMode.WITH, 99.0, tampered = true),
        )
        val leg = rippleSeries(runs).single().withMcp!!

        assertEquals(1, leg.includedInCost)
        assertEquals(6.0, leg.medianCostUsd)
        assertTrue(leg.exclusions.single().reason.contains("tampered"), leg.exclusions.single().reason)
    }

    @Test
    fun `a crashed agent CLI is excluded from the money`() {
        val runs = listOf(run(1, McpMode.WITH, 6.0), run(2, McpMode.WITH, 0.01, exitCode = 1))
        val leg = rippleSeries(runs).single().withMcp!!

        assertEquals(1, leg.includedInCost)
        assertTrue(leg.exclusions.single().reason.contains("exit 1"), leg.exclusions.single().reason)
    }

    @Test
    fun `an unrecorded comparability is UNKNOWN, not a silent exclusion nor a silent inclusion`() {
        val runs = listOf(run(1, McpMode.WITH, 6.0, verdict = "UNKNOWN", reason = "no decoded transcript"))
        val leg = rippleSeries(runs).single().withMcp!!

        assertEquals(1, leg.attempts)
        assertEquals(0, leg.includedInCost)
        assertEquals(1, leg.unknownComparability)
        assertTrue(leg.exclusions.isEmpty(), "unknown is not an exclusion — it is missing evidence")
        assertNull(leg.medianCostUsd)
    }

    @Test
    fun `arms are paired by build id, never by list order`() {
        // Only build 1 has both arms admitted; build 2's shell leg is missing and build 3's mcp leg is.
        val runs = listOf(
            run(1, McpMode.WITH, 6.0), run(1, McpMode.WITHOUT, 8.0),
            run(2, McpMode.WITH, 6.5),
            run(3, McpMode.WITHOUT, 8.5),
        )
        val series = rippleSeries(runs).single()

        assertEquals(listOf(-2.0), series.pairedCostDeltas)
        assertTrue(series.statement.contains("insufficient repeats"), series.statement)
    }

    @Test
    fun `the price is split into fixed overhead and work`() {
        val runs = listOf(run(1, McpMode.WITH, 6.0, cacheRead = 100_000, input = 5_000, output = 20_000))
        val leg = rippleSeries(runs).single().withMcp!!

        assertEquals(105_000.0, leg.medianOverheadTokens)
        assertEquals(20_000.0, leg.medianWorkTokens)
    }

    @Test
    fun `non-ripple scenarios never enter the series`() {
        val runs = listOf(
            AgentRun("dpaia__spring__petclinic-27", "claude", McpMode.WITH, buildId = 1, costUsd = 3.0),
            run(1, McpMode.WITH, 6.0),
        )
        assertEquals(listOf("ripple__keycloak__rename-method-wide"), rippleSeries(runs).map { it.scenario })
    }

    /**
     * The series must come off `allBuilds`. `latest` keeps, per (scenario, agent, mode), only the
     * newest build — feeding the aggregate from it would turn three repeats into "median of 1"
     * without a single error message.
     */
    @Test
    fun `the series is read from every collected build, not from the latest-only view`(@TempDir root: java.io.File) {
        fun summary(buildId: Long, mode: String, cost: Double) {
            val dir = java.io.File(root, "builds/RippleRenameMethodWide_Claude__$buildId")
            dir.mkdirs()
            java.io.File(dir, "dpaia-arena-run-ripple__keycloak__rename-method-wide-claude-$mode.json").writeText(
                """
                {
                  "instance_id": "ripple__keycloak__rename-method-wide", "agent": "claude",
                  "mode": "$mode", "exit_code": 0, "cost_usd": $cost, "num_turns": 12,
                  "ripple": {
                    "ripple_success": true,
                    "comparability": { "verdict": "COMPARABLE", "reason": "30 of 40 calls went to the IDE" }
                  }
                }
                """.trimIndent()
            )
        }
        listOf(1L to 6.0, 2L to 7.0, 3L to 8.0).forEach { (id, cost) -> summary(id, "mcp", cost) }
        listOf(1L to 8.0, 2L to 9.0, 3L to 11.0).forEach { (id, cost) -> summary(id, "none", cost) }

        val collected = InputReader.readAll(root)
        assertEquals(2, collected.latest.size, "latest keeps one build per leg — that is why it cannot be the source")

        val series = rippleSeries(collected.allBuilds).single()
        assertEquals(3, series.withMcp!!.attempts)
        assertEquals(3, series.pairedCostDeltas.size)
        assertTrue(series.statement.contains("n=3"), series.statement)
    }

    @Test
    fun `median and spread are plain and unweighted`() {
        assertEquals(2.0, median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, median(listOf(1.0, 4.0, 2.0, 3.0)))
        assertNull(median(emptyList()))
        assertEquals(Spread(1.0, 4.0), spread(listOf(2.0, 4.0, 1.0)))
        assertNull(spread(emptyList()))
        assertTrue(Spread(-1.0, 2.0).straddlesZero())
        assertTrue(!Spread(-3.0, -1.0).straddlesZero())
    }
}
