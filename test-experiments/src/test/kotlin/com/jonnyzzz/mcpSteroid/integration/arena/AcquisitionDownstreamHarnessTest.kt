/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The unit tests of the downstream-validation half of the acquisition round.
 *
 * They exist for the reason the curve's own tests do: every rule here, when wrong, produces a
 * plausible table rather than an error. A checkpoint condition that silently resolved to the other
 * family's note directory, or a residual count that read "0 of 0" for a cell whose module never
 * compiled, would both publish numbers that look like measurements of understanding and are not.
 */
class AcquisitionDownstreamHarnessTest {

    private val case = AcquisitionCases.ccRefreshToken

    @Test
    fun `a checkpoint condition names a knowledge state, a note id still names a run`() {
        val condition = understandingConditionOf("checkpoint:mcp-b40-l2000-r2@10")
        val note = assertInstanceOf(AcquisitionCheckpointNote::class.java, condition)
        assertEquals("mcp-b40-l2000-r2", note.trajectoryId)
        assertEquals(10, note.checkpoint)
        assertEquals("mcp-b40-l2000-r2@10", note.label)
        assertEquals("mcp", note.arm)
        assertEquals("shell", AcquisitionCheckpointNote("none-b40-l2000-r3", 20).arm)

        // The three older shapes must keep meaning exactly what they meant. A prefix that started
        // swallowing note ids would silently redirect every note-bottleneck cell to an empty directory.
        assertTrue(understandingConditionOf("mcp-b10-l2000-r1") is UnderstandingCondition.Research)
        assertTrue(understandingConditionOf("baseline") is UnderstandingCondition.Baseline)
        assertTrue(understandingConditionOf("oracle:gold") is UnderstandingCondition.Oracle)
    }

    @Test
    fun `a note distilled at an unregistered prefix has no U to be plotted against`() {
        // 7 is not a checkpoint of this experiment, so no `U` was ever computed for it. A cell run
        // against such a note would land on the scatter plot with a fabricated x coordinate.
        assertThrows(IllegalStateException::class.java) {
            understandingConditionOf("checkpoint:mcp-b40-l2000-r2@7")
        }
        assertThrows(IllegalStateException::class.java) {
            understandingConditionOf("checkpoint:mcp-b40-l2000-r2")
        }
        assertThrows(IllegalStateException::class.java) {
            understandingConditionOf("checkpoint:@10")
        }
    }

    @Test
    fun `the two families read their notes from different directories`() {
        val checkpoint = AcquisitionCheckpointNote("mcp-b40-l2000-r2", 20)
        assertEquals(
            "src/test/resources/acquisition-notes/${case.instanceId}/mcp-b40-l2000-r2-at20.md",
            acquisitionNoteFile(case, checkpoint).path.replace(File.separatorChar, '/'),
        )
        assertTrue(
            understandingNoteDir(case).path != acquisitionNoteDir(case).path,
            "a read-out of a prefix and a note the research agent wrote answer different questions",
        )
    }

    @Test
    fun `residual work is counted against the oracle, never against what happened to run`() {
        fun verification(
            testsRun: Int,
            failures: Int,
            errors: Int,
            tampered: Boolean = false,
        ) = ArenaVerificationResult(
            perClass = listOf(SurefireClassResult(case.failToPass.single(), testsRun, failures, errors, 0)),
            failToPassTampered = tampered,
            collateralTestFilesEdited = emptyList(),
            regressions = emptyList(),
            baselineAvailable = false,
            verificationDurationMs = 1,
        )

        assertEquals(8, oracleAssertionsPassed(verification(8, 0, 0), case))
        assertEquals(5, oracleAssertionsPassed(verification(8, 2, 1), case))
        // The case this reading exists for: the module did not compile, surefire ran nothing, and the
        // binary verdict cannot tell it apart from seven assertions of eight.
        assertEquals(0, oracleAssertionsPassed(verification(0, 0, 0), case))
        assertEquals(0, oracleAssertionsPassed(null, case), "a cell that was never graded proved nothing")
        assertEquals(
            0,
            oracleAssertionsPassed(verification(8, 0, 0, tampered = true), case),
            "an agent that rewrote the oracle it is judged by scored nothing on it",
        )
    }

    @Test
    fun `the verdict line carries the pair the scatter plot is indexed by`() {
        val line = acquisitionDownstreamLine(
            caseId = case.instanceId,
            condition = AcquisitionCheckpointNote("none-b40-l2000-r3", 5),
            replicate = 1,
            outcome = UnderstandingDownstreamOutcome(
                success = false,
                verdict = "Y=0",
                oracleTestsPassed = 3,
                oracleTestsTotal = 8,
                cost = UnderstandingCellCost(usd = 0.25, agentSeconds = 400, outputTokens = 9_000),
            ),
        )
        assertTrue(line.contains("trajectory=none-b40-l2000-r3"), line)
        assertTrue(line.contains("checkpoint=5"), line)
        assertTrue(line.contains("arm=shell"), line)
        assertTrue(line.contains("oraclePassed=3/8"), line)
        assertTrue(line.contains("residual=5"), line)
        // `U` deliberately absent: it is recomputed from the transcript, and a copy carried through a
        // build parameter is a copy that can disagree with the curve it is plotted on.
        assertTrue(!line.contains("u="), line)
    }

    @Test
    fun `a calibration anchor reports its residual work without pretending to be a note`() {
        val line = acquisitionDownstreamLine(
            caseId = case.instanceId,
            condition = UnderstandingCondition.Baseline,
            replicate = 2,
            outcome = UnderstandingDownstreamOutcome(
                success = false,
                verdict = "Y=0",
                oracleTestsPassed = 0,
                oracleTestsTotal = 8,
                cost = UnderstandingCellCost(null, null, null),
            ),
        )
        assertTrue(line.contains("condition=baseline"), line)
        assertTrue(line.contains("trajectory=- checkpoint=- arm=-"), line)
        assertTrue(line.contains("residual=8"), line)
    }

    @Test
    fun `a trajectory that never used its tools cannot become a note either`() {
        fun trajectory(arm: String, vararg tools: String) = AcquisitionTrajectory(
            trajectoryId = "t", caseId = case.instanceId, arm = arm, model = "opus",
            calls = tools.mapIndexed { index, tool ->
                AcquisitionToolCall(index + 1, tool, "{}", "", 0, null)
            },
            exemptCalls = 0, refusedCalls = 0, totalOutputTokens = 1,
            tokenAccounting = AcquisitionTokenAccounting.PER_MESSAGE, finalMessage = "",
        )

        assertTrue(armDegenerate(trajectory("mcp", "Bash", "Read")))
        assertTrue(!armDegenerate(trajectory("mcp", "Bash", "mcp__mcp-steroid__steroid_execute_code")))
        // The control arm has no semantic tools by construction, so the predicate must never fire on
        // it — a re-reader that skipped every shell trajectory would silently halve the round.
        assertTrue(!armDegenerate(trajectory("shell", "Bash", "Read")))
    }

    @Test
    fun `a committed transcript reads the same gzipped as it does plain`(@TempDir dir: File) {
        val folder = dir.resolve("mcp-b40-l2000-r1").apply { mkdirs() }
        val text = """{"type":"assistant","message":{"id":"m1"}}"""
        GZIPOutputStream(folder.resolve("transcript.ndjson.gz").outputStream()).use {
            it.write(text.toByteArray())
        }

        val found = acquisitionTranscriptIn(folder)
        assertEquals("transcript.ndjson.gz", found?.name)
        assertEquals(text, readAcquisitionTranscript(found!!))

        // A folder with neither form is not a trajectory, and must not silently score as an empty one.
        assertNull(acquisitionTranscriptIn(dir.resolve("nothing-here").apply { mkdirs() }))
    }
}
