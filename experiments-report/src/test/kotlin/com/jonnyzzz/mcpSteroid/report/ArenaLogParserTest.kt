package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArenaLogParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses the with-mcp and without-mcp runs from a real build log`() {
        val runs = ArenaLogParser.parse(fixture("arena-log-petclinic27-claude.txt"))

        assertEquals(2, runs.size, "one run per mode")

        val withMcp = runs.single { it.mode == McpMode.WITH }
        assertEquals("dpaia__spring__petclinic-27", withMcp.scenario)
        assertEquals("claude", withMcp.agent)
        assertEquals(true, withMcp.claimedFix)
        assertEquals(true, withMcp.usedMcp)
        assertEquals(0, withMcp.exitCode)
        assertEquals(521_000L, withMcp.agentDurationMs)
        assertEquals(96, withMcp.testsRun)
        assertEquals(0, withMcp.testsFail)
        assertEquals(false, withMcp.buildSuccess)
        assertEquals(1, withMcp.execCodeCalls)
        assertEquals(812_345L, withMcp.inputTokens)
        assertEquals(45_678L, withMcp.outputTokens)
        assertEquals(3.21, withMcp.costUsd)
        assertEquals(47, withMcp.numTurns)
        assertNotNull(withMcp.summary)
        assertTrue(withMcp.summary.contains("REST CRUD"))

        val without = runs.single { it.mode == McpMode.WITHOUT }
        assertEquals(711_000L, without.agentDurationMs)
        assertEquals(false, without.usedMcp)
        assertEquals(94, without.testsRun)
        assertEquals(0, without.testsFail)
        assertEquals(true, without.buildSuccess)
        assertEquals(0, without.execCodeCalls)
    }
}
