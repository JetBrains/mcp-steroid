/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for the step between 50 build logs and one publishable number — no Docker, no agent.
 *
 * Four things worth a test each, and each of them is a way this aggregator could lie. It reads verdicts
 * out of build logs, so the parser must survive the Gradle prefix every real log line carries, must
 * ignore everything else in a 50-run concatenation, and must still read the 38 lines TeamCity recorded
 * before the line grew its edit fraction and its cost fields — as NULLS, never as zeros. It must key a
 * row by the STATE the probe was handed, which is `(arm, step)`: checkpoint ordinals were renumbered
 * when the axis changed, so folding by ordinal would merge two unrelated trees. It must never print a
 * `V` for a checkpoint that was not probed five times, since `1/1` and `5/5` are both "1.00" to a mean
 * while only one of them is the quantity this pilot defines. And its axis must be the edit fraction,
 * because the comparison the report exists for is mcp against none AT THE SAME FRACTION of the work
 * each of them did.
 */
class RippleCheckpointReportTest {
    @Test
    fun `verdicts are parsed off the log line`() {
        val log = """
            [:test-experiments:test] [CHECKPOINT-PROBE] arm=mcp checkpoint=1 step=15 editFraction=0.000 position=0.5769 replicate=1 Y=0 usd=0.1200 agentSeconds=311 tokens=98123
            noise
            [CHECKPOINT-PROBE] arm=mcp checkpoint=4 step=18 editFraction=0.300 position=0.6923 replicate=1 Y=1 usd=0.3278 agentSeconds=613 tokens=152414
        """.trimIndent()
        val verdicts = parseProbeVerdicts(log)

        assertEquals(2, verdicts.size)
        assertEquals(
            ProbeVerdict(
                arm = "mcp", checkpoint = 4, step = 18, position = 0.6923, replicate = 1, success = true,
                editFraction = 0.300, usd = 0.3278, agentSeconds = 613, tokens = 152414,
            ),
            verdicts[1],
        )
    }

    /**
     * A second capture is addressed by a new arm token (`mcp2` / `none2`, see
     * `RIPPLE_CHECKPOINT_CASE_ARMS`), and the aggregator has to read it without any change: the whole
     * point of encoding the round in the arm is that nothing downstream — not the TeamCity DSL, not this
     * parser, not the report's grouping key `(arm, step)` — needs to learn about rounds at all.
     */
    @Test
    fun `a second capture's verdict parses under its own arm token`() {
        val line = "[CHECKPOINT-PROBE] arm=mcp2 checkpoint=3 step=21 editFraction=0.250 " +
            "position=0.7000 replicate=4 Y=1 usd=0.5100 agentSeconds=705 tokens=88012"
        val verdict = parseProbeVerdicts(line).single()

        assertEquals(
            ProbeVerdict(
                arm = "mcp2", checkpoint = 3, step = 21, position = 0.7000, replicate = 4, success = true,
                editFraction = 0.250, usd = 0.5100, agentSeconds = 705, tokens = 88012,
            ),
            verdict,
        )
        // Round 1's rows must not be folded into round 2's: the grouping key is (arm, step), and the two
        // captures are different arms precisely so that the same step number cannot merge them.
        val round1 = "[CHECKPOINT-PROBE] arm=mcp checkpoint=3 step=21 editFraction=0.500 " +
            "position=0.8077 replicate=4 Y=1"
        assertEquals(2, parseProbeVerdicts("$line\n$round1").map { it.arm to it.step }.distinct().size)
    }

    /**
     * The 38 verdicts TeamCity recorded before the line carried a fraction or a price. They measure real
     * states and must keep folding into `V`; what they never learned stays NULL, because a zero dollar
     * cost or a zero fraction would be a number nobody measured and would drag every median with it.
     */
    @Test
    fun `a verdict recorded before the cost fields existed still parses, with nulls`() {
        val old = "[CHECKPOINT-PROBE] arm=mcp checkpoint=5 step=20 position=0.7692 replicate=1 Y=1"
        val verdict = parseProbeVerdicts(old).single()

        assertEquals(ProbeVerdict("mcp", 5, 20, 0.7692, 1, true), verdict)
        assertNull(verdict.editFraction)
        assertNull(verdict.usd)
        assertNull(verdict.agentSeconds)
        assertNull(verdict.tokens)
    }

    @Test
    fun `report prints V per state and the AUC over the edit fraction range`() {
        val report = renderCheckpointReport(armVerdicts("mcp"))

        assertTrue(report.contains("| editFraction | step | position |")) { report }
        assertTrue(report.contains("| 0.000 | 15 | 0.5769 | 1 | 5 | 0.20 |")) { report }
        assertTrue(report.contains("AUC: 0.240 over edit fraction 0.000..0.400")) { report }
    }

    /**
     * `V` saturates long before the trajectory ends — the probe's base rate on the pristine tree is
     * already high — so what still separates two states at the same fraction is what a continuation
     * COSTS from each. Medians and not means, over the SUCCESSFUL runs only: a failed probe stops when
     * its budget runs out rather than when the task is done, and averaging that in measures the timeout.
     */
    @Test
    fun `the cost columns are the median over the successful runs`() {
        val verdicts = (1..5).map { replicate ->
            ProbeVerdict(
                arm = "mcp", checkpoint = 1, step = 15, position = 0.5769, replicate = replicate,
                success = replicate <= 3, editFraction = 0.0,
                usd = replicate * 0.1, agentSeconds = replicate * 100L, tokens = replicate * 1000L,
            )
        }
        val report = renderCheckpointReport(verdicts)

        // successes are replicates 1..3, so the medians are the second of each triple
        assertTrue(report.contains("| 0.2000 | 200 | 2000 |")) { report }
    }

    @Test
    fun `a state no successful run reported a cost for prints n slash a`() {
        val verdicts = (1..5).map { replicate ->
            ProbeVerdict("mcp", 1, 15, 0.5769, replicate, success = true, editFraction = 0.0)
        }
        val report = renderCheckpointReport(verdicts)

        assertTrue(report.contains("| n/a | n/a | n/a |")) { report }
    }

    @Test
    fun `a checkpoint with fewer than five verdicts is reported as incomplete, not averaged away`() {
        val report = renderCheckpointReport(listOf(ProbeVerdict("mcp", 1, 15, 0.5769, 1, true)))
        assertTrue(report.contains("INCOMPLETE")) { report }
    }

    @Test
    fun `a Y flag that is neither 0 nor 1 is not a verdict`() {
        val log = "[CHECKPOINT-PROBE] arm=mcp checkpoint=1 step=15 position=0.5769 replicate=1 Y=LOST"
        assertTrue(parseProbeVerdicts(log).isEmpty()) {
            "an instrument failure must never be read as a graded zero"
        }
    }

    @Test
    fun `a duplicated replicate is a double-counted run, not a bigger sample`() {
        val twice = List(2) { ProbeVerdict("mcp", 1, 15, 0.5769, 1, true) }
        assertThrows(IllegalArgumentException::class.java) { renderCheckpointReport(twice) }
    }

    /**
     * The reason a row is keyed by `(arm, step)` and not by the checkpoint ordinal: the ordinals were
     * renumbered when the axis changed, so the same state carries a 5 in the verdicts recorded before
     * and a 1 in those recorded after. The step is the state — the patch is literally named after it —
     * so the two sets are one group of five runs, not two half-probed rows that both render INCOMPLETE.
     */
    @Test
    fun `verdicts of one step are one row even when the ordinals were renumbered`() {
        val old = (1..2).map { ProbeVerdict("mcp", 5, 15, 0.5769, it, success = true) }
        val new = (3..5).map {
            ProbeVerdict("mcp", 1, 15, 0.5769, it, success = true, editFraction = 0.0)
        }
        val report = renderCheckpointReport(old + new)

        assertTrue(report.contains("| 0.000 | 15 | 0.5769 | 5 | 5 | 1.00 |")) { report }
        assertFalse(report.contains("INCOMPLETE")) { report }
    }

    @Test
    fun `each arm gets its own table`() {
        val report = renderCheckpointReport(armVerdicts("mcp") + armVerdicts("none"))

        assertTrue(report.contains("## mcp")) { report }
        assertTrue(report.contains("## none")) { report }
        assertEquals(2, Regex("^\\| editFraction \\| step \\|", RegexOption.MULTILINE).findAll(report).count())
    }

    /**
     * The comparison the whole pilot exists for: the same fraction of the edit phase in both arms, side
     * by side. The arms' steps have nothing in common — 15 against 40 here — which is exactly why the
     * shared column is the fraction.
     */
    @Test
    fun `the two arms are laid side by side at the same fraction`() {
        val report = renderCheckpointReport(armVerdicts("mcp") + armVerdicts("none"))

        assertTrue(report.contains("| editFraction | V mcp | V none |")) { report }
        assertTrue(report.contains("| 0.000 | 0.20 | 0.20 |")) { report }
    }

    @Test
    fun `an incomplete arm names no AUC it did not measure`() {
        val verdicts = (1..5).map { ProbeVerdict("mcp", 3, 15, 0.3, it, true, editFraction = 0.2) }
        val report = renderCheckpointReport(verdicts)

        assertTrue(report.contains("NOT MEASURED")) { report }
        assertFalse(report.contains("0.200..0.200")) { "a single point is not a range: $report" }
    }

    /**
     * A complete row whose verdicts all predate the fraction axis has no coordinate to be drawn at. It
     * stays in the table — it is a measured `V` — and stays out of the area, which is said out loud
     * rather than left to a reader comparing two row counts.
     */
    @Test
    fun `a row with no edit fraction is kept in the table and left out of the area`() {
        val verdicts = (1..5).map { ProbeVerdict("mcp", 5, 20, 0.7692, it, success = true) }
        val report = renderCheckpointReport(verdicts)

        assertTrue(report.contains("| n/a | 20 | 0.7692 | 5 | 5 | 1.00 |")) { report }
        assertTrue(report.contains("carry no editFraction")) { report }
    }

    @Test
    fun `one step may not report two different positions`() {
        val verdicts = (1..5).map { replicate ->
            ProbeVerdict("mcp", 1, 15, if (replicate == 5) 0.58 else 0.5769, replicate, success = true)
        }
        assertThrows(IllegalArgumentException::class.java) { renderCheckpointReport(verdicts) }
    }

    @Test
    fun `one step may not report two different edit fractions`() {
        val verdicts = (1..5).map { replicate ->
            ProbeVerdict(
                "mcp", 1, 15, 0.5769, replicate, success = true,
                editFraction = if (replicate == 5) 0.1 else 0.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) { renderCheckpointReport(verdicts) }
    }

    @Test
    fun `a later state may not sit at an earlier position`() {
        val verdicts = (1..2).flatMap { i ->
            (1..5).map { j -> ProbeVerdict("mcp", i, 10 + i, 0.3 - i * 0.1, j, success = true) }
        }
        assertThrows(IllegalArgumentException::class.java) { renderCheckpointReport(verdicts) }
    }

    @Test
    fun `a report of nothing says so instead of printing an empty table`() {
        assertTrue(renderCheckpointReport(emptyList()).contains("no checkpoint probe verdicts")) {
            renderCheckpointReport(emptyList())
        }
    }

    /**
     * Five fully probed states of one arm, at the first five fractions of its edit phase, with a
     * readiness that climbs from 1/5 to 5/5. The mcp arm's real steps for those fractions; the shell
     * arm's are elsewhere entirely, which is the point of comparing on the fraction.
     */
    private fun armVerdicts(arm: String): List<ProbeVerdict> {
        val steps = if (arm == "mcp") listOf(15, 16, 17, 18, 19) else listOf(17, 21, 25, 29, 33)
        val n = if (arm == "mcp") 26.0 else 57.0
        return steps.flatMapIndexed { k, step ->
            (1..5).map { replicate ->
                ProbeVerdict(
                    arm = arm,
                    checkpoint = k + 1,
                    step = step,
                    position = publishedFourDecimals(step / n),
                    replicate = replicate,
                    success = replicate <= k + 1,
                    editFraction = k / 10.0,
                )
            }
        }
    }

    private fun publishedFourDecimals(value: Double): Double =
        BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toDouble()
}
