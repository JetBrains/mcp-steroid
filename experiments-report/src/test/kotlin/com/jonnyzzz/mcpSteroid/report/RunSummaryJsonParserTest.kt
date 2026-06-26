package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RunSummaryJsonParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing $name" }
            .bufferedReader().readText()

    @Test
    fun `parses a dpaia-arena-run summary json into an AgentRun`() {
        val run = RunSummaryJsonParser.parse(fixture("dpaia-arena-run-petclinic27-claude-mcp.json"))

        assertEquals("dpaia__spring__petclinic-27", run.scenario)
        assertEquals("claude", run.agent)
        assertEquals(McpMode.WITH, run.mode)
        assertEquals(0, run.exitCode)
        assertEquals(true, run.claimedFix)
        assertEquals(true, run.usedMcp)
        assertEquals(521_000L, run.agentDurationMs)
        assertEquals(812_345L, run.inputTokens)
        assertEquals(45_678L, run.outputTokens)
        assertEquals(3.21, run.costUsd)
        assertEquals(47, run.numTurns)
        assertEquals(96, run.testsRun)
        assertEquals(0, run.testsFail)
        assertEquals(false, run.buildSuccess)
        assertEquals(1, run.execCodeCalls)
        assertEquals("Created PetRepository, VisitRepository and REST controllers under /api.", run.summary)
        // metrics sourced from the agent NDJSON, persisted into the summary by the test
        assertEquals("claude-opus-4-6", run.model)
        assertEquals("2.1.119", run.agentVersion)
        assertEquals(200_000L, run.contextWindow)
        assertEquals(64_000L, run.maxOutputTokens)
        // per-tool counts become the toolCalls map for the diff
        assertEquals(12, run.toolCalls["Read"])
        assertEquals(8, run.toolCalls["Edit"])
        assertEquals(1, run.toolCalls["steroid_execute_code"])
    }

    @Test
    fun `maps the none mode to WITHOUT`() {
        val json = """{"instance_id":"x","agent":"codex","mode":"none","agent_claimed_fix":false}"""
        val run = RunSummaryJsonParser.parse(json)
        assertEquals(McpMode.WITHOUT, run.mode)
        assertEquals("codex", run.agent)
        assertEquals(false, run.claimedFix)
    }
}
