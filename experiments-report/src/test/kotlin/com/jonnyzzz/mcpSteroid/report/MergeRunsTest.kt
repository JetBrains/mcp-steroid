package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MergeRunsTest {
    @Test
    fun `merges runs of the same scenario-agent-mode taking first non-null per field`() {
        val fromLog = AgentRun("s", "claude", McpMode.WITH, claimedFix = true, costUsd = null, testStatus = null)
        val fromJunit = AgentRun("s", "claude", McpMode.WITH, claimedFix = null, costUsd = 1.0, testStatus = "SUCCESS", testDurationMs = 500L)

        val merged = mergeRuns(listOf(fromLog, fromJunit))
        assertEquals(1, merged.size)
        val m = merged.single()
        assertEquals(true, m.claimedFix, "kept from the first source")
        assertEquals(1.0, m.costUsd, "filled from the second source")
        assertEquals("SUCCESS", m.testStatus)
        assertEquals(500L, m.testDurationMs)
    }

    @Test
    fun `keeps distinct scenario-agent-mode rows separate`() {
        val runs = listOf(
            AgentRun("s", "claude", McpMode.WITH),
            AgentRun("s", "claude", McpMode.WITHOUT),
            AgentRun("s", "codex", McpMode.WITH),
        )
        assertEquals(3, mergeRuns(runs).size)
    }
}
