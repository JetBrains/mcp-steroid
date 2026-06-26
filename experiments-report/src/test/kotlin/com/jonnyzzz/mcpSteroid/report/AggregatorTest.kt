package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AggregatorTest {
    @Test
    fun `pairs with-and-without runs per scenario and agent and computes deltas`() {
        val runs = listOf(
            AgentRun("petclinic-27", "claude", McpMode.WITH, claimedFix = true, agentDurationMs = 521_000, costUsd = 3.21, buildSuccess = false),
            AgentRun("petclinic-27", "claude", McpMode.WITHOUT, claimedFix = true, agentDurationMs = 711_000, costUsd = 4.10, buildSuccess = true),
            AgentRun("train-ticket-1", "codex", McpMode.WITH, claimedFix = true, agentDurationMs = 100_000),
            AgentRun("train-ticket-1", "codex", McpMode.WITHOUT, claimedFix = false, agentDurationMs = 120_000),
        )

        val comps = Aggregator.compare(runs)
        assertEquals(2, comps.size)

        val pet = comps.single { it.scenario == "petclinic-27" && it.agent == "claude" }
        assertEquals(Verdict.NEUTRAL, pet.verdict, "both claimed a fix -> neutral outcome")
        assertEquals(-190_000L, pet.durationDeltaMs, "mcp run was faster by 190s")
        assertEquals(-0.89, pet.costDeltaUsd!!, 1e-9)

        val tt = comps.single { it.scenario == "train-ticket-1" && it.agent == "codex" }
        assertEquals(Verdict.MCP_HELPED, tt.verdict, "with-mcp fixed, without did not")
        assertEquals(-20_000L, tt.durationDeltaMs)
    }

    @Test
    fun `marks a comparison incomplete when a mode is missing`() {
        val runs = listOf(AgentRun("solo", "claude", McpMode.WITH, claimedFix = true))
        val comp = Aggregator.compare(runs).single()
        assertEquals(Verdict.INCOMPLETE, comp.verdict)
        assertEquals(null, comp.durationDeltaMs)
    }
}
