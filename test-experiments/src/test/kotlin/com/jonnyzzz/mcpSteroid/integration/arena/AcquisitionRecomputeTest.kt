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
 * Point it at a directory holding one folder per CASE, each holding one folder per trajectory, each
 * with the transcript the cell published — `transcript.ndjson`, or the `transcript.ndjson.gz` the
 * repository commits:
 *
 * ```
 * docs/acquisition-curve-experiment/data/trajectories/
 *   acquisition__keycloak__cc-refresh-token/mcp-b40-l2000-r1/transcript.ndjson.gz
 *   acquisition__keycloak__oauth-grant-type/mcp-b40-l2000-r1/transcript.ndjson.gz
 *
 * ./gradlew :test-experiments:test --tests '*AcquisitionRecomputeTest*' \
 *     -Dacquisition.recompute.dir=docs/acquisition-curve-experiment/data/trajectories
 * ```
 *
 * The case level is not decoration and it is not optional. Trajectory ids repeat across cases by
 * construction — every case has an `mcp-b40-l2000-r1` — while the checklist, the statement and
 * therefore every number derived from a transcript are per case. A flat directory would let the
 * generalization round's twelve transcripts overwrite the pilot's, and would score one case's
 * transcript against another case's checklist without any symptom other than a low `U`.
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

        // Refused rather than guessed. A folder of transcripts directly under the root is the OLD
        // single-case layout, and reading it would silently score it against whichever case this test
        // used to hardcode — which is exactly the defect the case level exists to remove.
        val stray = dir.listFiles().orEmpty().filter { it.isDirectory && acquisitionTranscriptIn(it) != null }
        check(stray.isEmpty()) {
            "$dir holds transcripts directly (${stray.map { it.name }}), i.e. the flat single-case " +
                "layout. Move each trajectory under its case: <root>/<caseId>/<trajectoryId>/. A " +
                "transcript whose case is unknown cannot be scored, because the checklist is per case"
        }
        val caseFolders = dir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        check(caseFolders.isNotEmpty()) { "no <caseId>/<trajectory>/transcript.ndjson[.gz] under $dir" }

        println("[ACQUISITION-RECOMPUTE] ${AcquisitionPoint.CSV_HEADER}")
        for (caseFolder in caseFolders) recomputeCase(caseFolder, outDir)
    }

    private fun recomputeCase(caseFolder: File, outRoot: File?) {
        val case = AcquisitionCases.byId(caseFolder.name)
        val checklist = AcquisitionCases.checklistFor(case.instanceId)
        val outDir = outRoot?.resolve(case.instanceId)

        val transcripts = caseFolder.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { folder -> acquisitionTranscriptIn(folder) }
            .sortedBy { it.parentFile.name }
        check(transcripts.isNotEmpty()) {
            "no <trajectory>/transcript.ndjson[.gz] under $caseFolder"
        }

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

            if (outDir != null && armDegenerate(trajectory)) {
                // Refused rather than filtered downstream. This trajectory is a paid recording and its
                // curve is printed above, but it is a control-arm curve wearing the treatment label,
                // and a note distilled from it would enter a table of "what the semantic arm knew".
                // Enforcing that here means no consumer has to remember a list of rejected ids.
                println(
                    "[ACQUISITION-RECOMPUTE] ${case.instanceId}/$trajectoryId is arm-degenerate " +
                        "(${trajectory.calls.groupingBy { it.toolName }.eachCount()}); no distillation " +
                        "prompts written for it"
                )
            } else if (outDir != null) {
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
