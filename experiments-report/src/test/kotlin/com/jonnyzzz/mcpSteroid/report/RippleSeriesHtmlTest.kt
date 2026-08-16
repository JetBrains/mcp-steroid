package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The rendered ripple section. It is a section of its own on purpose: the latest-run table above it
 * answers "what did the newest build do", this one answers "what does the series say", and a reader
 * must not be able to mistake one for the other.
 */
class RippleSeriesHtmlTest {

    private fun run(buildId: Long, mode: McpMode, cost: Double, verdict: String = "COMPARABLE") = AgentRun(
        scenario = "ripple__keycloak__rename-method-wide",
        agent = "claude",
        mode = mode,
        buildId = buildId,
        exitCode = 0,
        costUsd = cost,
        numTurns = 12,
        agentDurationMs = 240_000,
        cacheReadTokens = 100_000,
        inputTokens = 5_000,
        outputTokens = 20_000,
        rippleSuccess = true,
        comparabilityVerdict = verdict,
        comparabilityReason = if (verdict == "COMPARABLE") "30 of 40 tool calls went to the IDE"
            else "the mcp arm made 6 of 44 tool calls against the IDE",
    )

    private fun render(runs: List<AgentRun>): String = HtmlRenderer.render(
        Report(
            title = "t",
            generatedAt = "now",
            comparisons = Aggregator.compare(runs),
            allRuns = runs,
            rippleSeries = rippleSeries(runs),
        )
    )

    @Test
    fun `the series renders medians, spreads, the token split and the paired statement`() {
        val html = render(
            listOf(
                run(1, McpMode.WITH, 6.0), run(1, McpMode.WITHOUT, 8.0),
                run(2, McpMode.WITH, 7.0), run(2, McpMode.WITHOUT, 9.0),
                run(3, McpMode.WITH, 8.0), run(3, McpMode.WITHOUT, 11.0),
            )
        )

        assertTrue(html.contains(ScenarioBucket.RIPPLE.title), "the ripple section has its own heading")
        assertTrue(html.contains("\$7.00"), "the mcp median cost")
        assertTrue(html.contains("(6.00…8.00)"), "the median is printed with its spread")
        assertTrue(html.contains("105,000 / 20,000"), "overhead tokens against work tokens")
        assertTrue(html.contains("cheaper"), "a difference clear of the spread may be named")
    }

    @Test
    fun `an excluded run is shown with its reason, not silently dropped`() {
        val html = render(
            listOf(
                run(1, McpMode.WITH, 6.0),
                run(2, McpMode.WITH, 99.0, verdict = "NOT_COMPARABLE"),
            )
        )

        assertTrue(html.contains("excluded (build 2)"), "the excluded attempt is named")
        assertTrue(html.contains("6 of 44 tool calls"), "with the arithmetic behind the exclusion")
        assertTrue(html.contains("insufficient repeats"), "and no difference is claimed off one attempt")
    }

    @Test
    fun `a report with no ripple runs renders no ripple section at all`() {
        val runs = listOf(
            AgentRun("dpaia__spring__petclinic-27", "claude", McpMode.WITH, claimedFix = true),
            AgentRun("dpaia__spring__petclinic-27", "claude", McpMode.WITHOUT, claimedFix = true),
        )
        assertTrue(!render(runs).contains(ScenarioBucket.RIPPLE.title))
    }
}
