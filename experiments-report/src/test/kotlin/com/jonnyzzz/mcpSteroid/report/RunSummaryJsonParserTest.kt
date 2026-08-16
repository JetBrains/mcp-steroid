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
    fun `reads the ripple grade and the comparability block out of the nested ripple object`() {
        val json = """
            {
              "instance_id": "ripple__keycloak__change-signature-wide",
              "agent": "claude", "mode": "mcp", "exit_code": 0,
              "cost_usd": 12.13, "num_turns": 41,
              "objective_success": true, "fail_to_pass_tampered": false,
              "ripple": {
                "ripple_success": false, "compile_gate_passed": true, "all_predicates_passed": false,
                "recall": 1.0, "precision": 1.0, "f1": 1.0,
                "extra_predicates": { "P5_ARITY": false, "P7_RECEIVER": true },
                "comparability": {
                  "verdict": "NOT_COMPARABLE", "comparable": false,
                  "reason": "the mcp arm made 6 of 44 tool calls against the IDE",
                  "steroid_calls": 6, "bash_calls": 38, "total_tool_calls": 44,
                  "tool_error_count": 2, "ide_call_share": 0.136
                }
              }
            }
        """.trimIndent()

        val run = RunSummaryJsonParser.parse(json)

        // The whole point of the nested block: FAIL_TO_PASS is green and the ripple verdict is still false.
        assertEquals(true, run.objectiveSuccess)
        assertEquals(false, run.rippleSuccess)
        assertEquals(false, run.succeeded(), "ripple SUCCESS outranks the generic objective flag")
        assertEquals(true, run.rippleCompileGatePassed)
        assertEquals(false, run.rippleAllPredicatesPassed)
        assertEquals(1.0, run.rippleF1)
        assertEquals(mapOf("P5_ARITY" to false, "P7_RECEIVER" to true), run.rippleExtraPredicates)
        assertEquals("NOT_COMPARABLE", run.comparabilityVerdict)
        assertEquals(6, run.steroidCalls)
        assertEquals(38, run.bashCalls)
        assertEquals(44, run.totalToolCalls)
        assertEquals(2, run.toolErrorCount)
        assertEquals(0.136, run.ideCallShare)
        assertEquals(CostInclusion.EXCLUDED, run.costInclusion().state)
        assertEquals("the mcp arm made 6 of 44 tool calls against the IDE", run.costInclusion().reason)
    }

    @Test
    fun `a summary without a ripple block carries no ripple grade and no comparability verdict`() {
        val run = RunSummaryJsonParser.parse(fixture("dpaia-arena-run-petclinic27-claude-mcp.json"))

        assertEquals(null, run.rippleSuccess)
        assertEquals(null, run.comparabilityVerdict)
        // Missing evidence is UNKNOWN — never a silent inclusion into a price aggregate.
        assertEquals(CostInclusion.UNKNOWN, run.costInclusion().state)
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
