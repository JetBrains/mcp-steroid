/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for the step between 50 build logs and one publishable number — no Docker, no agent.
 *
 * The two things worth a test each are the two ways this aggregator could lie. It reads verdicts out of
 * build logs, so the parser must survive the Gradle prefix every real log line carries and must ignore
 * everything else in a 50-run concatenation. And it must never print a `V` for a checkpoint that was
 * not probed five times: `1/1` and `5/5` are both "1.00" to a mean, while only one of them is the
 * quantity this pilot defines.
 */
class RippleCheckpointReportTest {
    @Test
    fun `verdicts are parsed off the log line`() {
        val log = """
            [:test-experiments:test] [CHECKPOINT-PROBE] arm=mcp checkpoint=1 step=2 position=0.0667 replicate=1 Y=0
            noise
            [CHECKPOINT-PROBE] arm=mcp checkpoint=5 step=23 position=0.7667 replicate=3 Y=1
        """.trimIndent()
        val verdicts = parseProbeVerdicts(log)
        assertEquals(2, verdicts.size)
        assertEquals(ProbeVerdict("mcp", 5, 23, 0.7667, 3, true), verdicts[1])
    }

    @Test
    fun `report prints V per checkpoint and the AUC with its range`() {
        val verdicts = (1..5).flatMap { i ->
            (1..5).map { j -> ProbeVerdict("mcp", i, i * 5, i * 0.1, j, success = j <= i) }
        }
        val report = renderCheckpointReport(verdicts)
        assertTrue(report.contains("| 1 | 5 | 0.100 | 1 | 5 | 0.20 |"))
        assertTrue(report.contains("AUC")); assertTrue(report.contains("0.100..0.500"))
    }

    @Test
    fun `a checkpoint with fewer than five verdicts is reported as incomplete, not averaged away`() {
        val report = renderCheckpointReport(listOf(ProbeVerdict("mcp", 1, 2, 0.07, 1, true)))
        assertTrue(report.contains("INCOMPLETE"))
    }

    @Test
    fun `a Y flag that is neither 0 nor 1 is not a verdict`() {
        val log = "[CHECKPOINT-PROBE] arm=mcp checkpoint=1 step=2 position=0.0667 replicate=1 Y=LOST"
        assertTrue(parseProbeVerdicts(log).isEmpty()) {
            "an instrument failure must never be read as a graded zero"
        }
    }

    @Test
    fun `a duplicated replicate is a double-counted run, not a bigger sample`() {
        val twice = List(2) { ProbeVerdict("mcp", 1, 2, 0.07, 1, true) }
        assertThrows(IllegalArgumentException::class.java) { renderCheckpointReport(twice) }
    }

    @Test
    fun `each arm gets its own table`() {
        val verdicts = listOf("mcp", "none").flatMap { arm ->
            (1..5).flatMap { i ->
                (1..5).map { j -> ProbeVerdict(arm, i, i * 5, i * 0.1, j, success = j <= i) }
            }
        }
        val report = renderCheckpointReport(verdicts)
        assertTrue(report.contains("## mcp")) { report }
        assertTrue(report.contains("## none")) { report }
        assertEquals(2, Regex("^\\| checkpoint \\|", RegexOption.MULTILINE).findAll(report).count())
    }

    @Test
    fun `an incomplete arm names no AUC it did not measure`() {
        val verdicts = (1..5).map { j -> ProbeVerdict("mcp", 3, 15, 0.3, j, success = true) }
        val report = renderCheckpointReport(verdicts)
        assertTrue(report.contains("NOT MEASURED")) { report }
        assertFalse(report.contains("0.300..0.300")) { "a single point is not a range: $report" }
    }

    @Test
    fun `one checkpoint may not report two different positions`() {
        val verdicts = (1..5).map { j ->
            ProbeVerdict("mcp", 1, 2, if (j == 5) 0.08 else 0.07, j, success = true)
        }
        assertThrows(IllegalArgumentException::class.java) { renderCheckpointReport(verdicts) }
    }

    @Test
    fun `a later checkpoint may not sit at an earlier position`() {
        val verdicts = (1..2).flatMap { i ->
            (1..5).map { j -> ProbeVerdict("mcp", i, 10 - i, 0.3 - i * 0.1, j, success = true) }
        }
        assertThrows(IllegalArgumentException::class.java) { renderCheckpointReport(verdicts) }
    }

    @Test
    fun `a report of nothing says so instead of printing an empty table`() {
        assertTrue(renderCheckpointReport(emptyList()).contains("no checkpoint probe verdicts")) {
            renderCheckpointReport(emptyList())
        }
    }
}
