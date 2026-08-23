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
 * Point it at a directory holding one folder per trajectory, each with the transcript the cell
 * published — `transcript.ndjson`, or the `transcript.ndjson.gz` the repository commits:
 *
 * ```
 * ./gradlew :test-experiments:test --tests '*AcquisitionRecomputeTest*' \
 *     -Dacquisition.recompute.dir=docs/acquisition-curve-experiment/data/trajectories
 * ```
 *
 * With `-Dacquisition.recompute.out=<dir>` it also re-emits the two artifacts the offline
 * distil-and-judge step consumes — `checklist.json` and one `distill-b<K>.txt` per checkpoint — so a
 * note can be distilled from a trajectory bought months ago without re-running the cell. They are
 * written HERE rather than assembled by the script for the reason the cell states: one definition of
 * "after ten interactions", used by the curve and by the note alike.
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
        val outDir = System.getProperty(RECOMPUTE_OUT_PROPERTY)?.let(::File)

        val transcripts = dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { folder -> acquisitionTranscriptIn(folder) }
            .sortedBy { it.parentFile.name }
        check(transcripts.isNotEmpty()) { "no <trajectory>/transcript.ndjson[.gz] under $dir" }

        val case = AcquisitionCases.ccRefreshToken
        val checklist = AcquisitionCases.checklistFor(case.instanceId)
        println("[ACQUISITION-RECOMPUTE] ${AcquisitionPoint.CSV_HEADER}")
        for (file in transcripts) {
            val trajectoryId = file.parentFile.name
            val arm = if (trajectoryId.startsWith("mcp")) "mcp" else "shell"
            val trajectory = parseAcquisitionTrajectory(
                rawNdjson = readAcquisitionTranscript(file),
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

            if (outDir != null) {
                val target = outDir.resolve(trajectoryId)
                target.mkdirs()
                target.resolve("checklist.json").writeText(checklistAsJson(checklist))
                for (checkpoint in ACQUISITION_CHECKPOINTS) {
                    target.resolve("distill-b$checkpoint.txt").writeText(
                        buildAcquisitionDistillPrompt(
                            problemStatement = case.problemStatement,
                            prefixTranscript = renderPrefixTranscript(trajectory.prefix(checkpoint)),
                        )
                    )
                }
                println("[ACQUISITION-RECOMPUTE] distillation prompts: ${target.absolutePath}")
            }
        }
    }

    companion object {
        const val RECOMPUTE_DIR_PROPERTY: String = "acquisition.recompute.dir"
        const val RECOMPUTE_OUT_PROPERTY: String = "acquisition.recompute.out"
    }
}

/**
 * The transcript of one trajectory folder, whichever of its two forms is present.
 *
 * A build artifact arrives as plain NDJSON and the repository keeps it gzipped — 596 KB against six
 * megabytes for the eight trajectories of the pilot — and every consumer wants the same string. Kept
 * as one function so a reader that silently skipped the committed form would be a compile error rather
 * than an empty result set.
 */
fun acquisitionTranscriptIn(folder: File): File? =
    folder.resolve("transcript.ndjson").takeIf { it.isFile }
        ?: folder.resolve("transcript.ndjson.gz").takeIf { it.isFile }

/** Reads a transcript, un-gzipping it when that is what it is. */
fun readAcquisitionTranscript(file: File): String =
    if (file.name.endsWith(".gz")) {
        java.util.zip.GZIPInputStream(file.inputStream().buffered()).use { it.readBytes().decodeToString() }
    } else {
        file.readText()
    }
