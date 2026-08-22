/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Re-reads transcripts that were already paid for and prints their curves again.
 *
 * The point is that a defect in the instrument must not cost a round of runs. A trajectory is a
 * transcript plus a checklist, and both are files: everything downstream of them — the slicing at
 * 5/10/20/40, the observed score, both denominators — is a pure function that can be re-evaluated on a
 * laptop. The first pilot shipped a token axis that could decrease, and fixing it required exactly this
 * and no Opus budget at all.
 *
 * Point it at a directory holding one folder per trajectory, each with the `transcript.ndjson` the cell
 * published:
 *
 * ```
 * ./gradlew :test-experiments:test --tests '*AcquisitionRecomputeTest*' \
 *     -Dacquisition.recompute.dir=/tmp/acq-art/unpacked/acquisition
 * ```
 *
 * Without the property the test asserts the property's own contract and passes, so it stays green in
 * ordinary builds instead of becoming a test that everyone learns to ignore.
 */
class AcquisitionRecomputeTest {

    @Test
    fun recompute() {
        val dir = System.getProperty(RECOMPUTE_DIR_PROPERTY)?.let(::File)
        if (dir == null) {
            println("[ACQUISITION-RECOMPUTE] no -D$RECOMPUTE_DIR_PROPERTY given, nothing to recompute")
            return
        }
        check(dir.isDirectory) { "-D$RECOMPUTE_DIR_PROPERTY=$dir is not a directory" }

        val transcripts = dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { folder -> folder.resolve("transcript.ndjson").takeIf { it.isFile } }
            .sortedBy { it.parentFile.name }
        check(transcripts.isNotEmpty()) { "no <trajectory>/transcript.ndjson under $dir" }

        val checklist = AcquisitionCases.checklistFor(AcquisitionCases.ccRefreshToken.instanceId)
        println("[ACQUISITION-RECOMPUTE] ${AcquisitionPoint.CSV_HEADER}")
        for (file in transcripts) {
            val trajectoryId = file.parentFile.name
            val arm = if (trajectoryId.startsWith("mcp")) "mcp" else "shell"
            val trajectory = parseAcquisitionTrajectory(
                rawNdjson = file.readText(),
                trajectoryId = trajectoryId,
                caseId = checklist.caseId,
                arm = arm,
            )

            val curve = observedCurve(trajectory, checklist)
            for (point in curve) println("[ACQUISITION-RECOMPUTE] ${point.csvRow()}")

            // The property the first pilot violated, asserted on the real transcripts rather than on a
            // synthetic one: a cumulative axis that decreases cannot be plotted against.
            val tokens = trajectory.calls.map { it.cumulativeOutputTokens }
            assertTrue(
                tokens.zipWithNext().all { (a, b) -> b >= a },
                "$trajectoryId has a decreasing token axis: $tokens",
            )
        }
    }

    companion object {
        const val RECOMPUTE_DIR_PROPERTY: String = "acquisition.recompute.dir"
    }
}
