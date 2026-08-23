/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.io.File

/**
 * One knowledge state of one research trajectory, addressed as a downstream condition.
 *
 * The understanding-note experiment could name a note by the run that produced it, because a run
 * produced exactly one note. Here a single forty-interaction trajectory carries FOUR knowledge states,
 * and the pair that identifies one of them is (trajectory, checkpoint) — the same pair every point of
 * the acquisition curve is indexed by. Reusing the note-id shape for this would have been possible and
 * wrong: `mcp-b10-l2000-r2` reads as "a run whose budget was ten", and the whole design of this round
 * is that no such run exists.
 *
 * The written form is `checkpoint:<trajectoryId>@<B>`.
 */
data class AcquisitionCheckpointNote(val trajectoryId: String, val checkpoint: Int) :
    UnderstandingCondition {

    init {
        check(trajectoryId.isNotBlank()) { "a checkpoint note must name the trajectory it was distilled from" }
        check(checkpoint in ACQUISITION_CHECKPOINTS) {
            "a checkpoint note can only be read at a pre-registered checkpoint $ACQUISITION_CHECKPOINTS, " +
                "got $checkpoint: a note distilled at some other prefix has no U to be plotted against"
        }
    }

    override val label: String = "$trajectoryId@$checkpoint"

    /** The arm the trajectory belonged to. Secondary analysis only — never shown to any agent. */
    val arm: String get() = if (trajectoryId.startsWith("mcp")) "mcp" else "shell"

    override fun noteText(case: UnderstandingCase): String {
        val file = acquisitionNoteFile(case, this)
        check(file.isFile) {
            "no distilled note at ${file.path}. A downstream cell cannot invent the knowledge state it " +
                "is named after; distil it from the committed transcript first (AcquisitionRecomputeTest " +
                "-D${AcquisitionRecomputeTest.RECOMPUTE_OUT_PROPERTY}=… then analysis/distill_and_judge.py)"
        }
        val text = file.readText().trim()
        check(text.isNotEmpty()) { "the committed note ${file.path} is empty" }
        return text
    }
}

/**
 * Where the distilled checkpoint notes of one case live.
 *
 * Deliberately NOT the understanding experiment's note directory. Those notes were written by the
 * research agent under a length limit it could feel; these are read-outs of a prefix, written by a
 * distiller that never saw the repository. Mixing them in one directory would make it possible to
 * queue one where the other is meant, and the two answer different questions.
 */
fun acquisitionNoteDir(case: UnderstandingCase): File =
    File("src/test/resources/acquisition-notes/${case.instanceId}")

/** The file one checkpoint note is committed as. */
fun acquisitionNoteFile(case: UnderstandingCase, note: AcquisitionCheckpointNote): File =
    acquisitionNoteDir(case).resolve("${note.trajectoryId}-at${note.checkpoint}.md")

/**
 * Parses `<trajectoryId>@<B>`, refusing anything else.
 *
 * Loud on a malformed value rather than lenient, for the reason the note-id parser gives: a downstream
 * cell that silently ran a different condition than the one it is labelled with corrupts the table it
 * lands in, and nothing later can detect it.
 */
fun parseAcquisitionCheckpointNote(raw: String): AcquisitionCheckpointNote {
    val at = raw.lastIndexOf('@')
    check(at > 0 && at < raw.length - 1) {
        "a checkpoint condition is `checkpoint:<trajectoryId>@<B>`, e.g. " +
            "`checkpoint:mcp-b40-l2000-r2@10`; got '$raw'"
    }
    val checkpoint = raw.substring(at + 1).trim().toIntOrNull()
    check(checkpoint != null) { "'${raw.substring(at + 1)}' is not a checkpoint number, in '$raw'" }
    return AcquisitionCheckpointNote(trajectoryId = raw.substring(0, at).trim(), checkpoint = checkpoint)
}

/**
 * What one downstream cell established, beyond the binary verdict.
 *
 * The count is the point. This round's question is whether a higher `U` buys a functionally better
 * starting position, and a pass/fail on eight assertions throws away most of the answer: a note that
 * carries the agent to seven of eight and a note that leaves it unable to compile are both `Y=0`. The
 * oracle is one class of eight independent assertions covering four different mechanisms, so the
 * number that still fail is a usable reading of the work the note did not do — and it makes twelve
 * cells informative where twelve Bernoulli trials would not be.
 *
 * [oracleTestsPassed] is counted against [oracleTestsTotal] taken from the case, never against what
 * the run happened to execute: a cell whose module did not compile ran zero tests, and reporting that
 * as "0 of 0" would quietly drop the worst outcomes out of every average.
 */
data class UnderstandingDownstreamOutcome(
    val success: Boolean?,
    val verdict: String,
    val oracleTestsPassed: Int,
    val oracleTestsTotal: Int,
    val cost: UnderstandingCellCost,
    /**
     * How many times the downstream agent reached for a tool.
     *
     * The second half of "functionally useful": a note that saves the reader nothing but still gets
     * there, and a note that gets there in a third of the work, are different findings. Nullable
     * because a stream that ended without any `tool_use` block really has an unknown count, and a zero
     * there would read as an agent that solved the task by thinking about it.
     */
    val toolCalls: Int? = null,
) {
    /** Assertions the note's reader never satisfied — the residual work, in the oracle's own units. */
    val residualTests: Int get() = oracleTestsTotal - oracleTestsPassed
}

/**
 * The one line the downstream aggregate of this round reads per cell.
 *
 * Carries the pair that identifies the knowledge state and the residual count, so the table of
 * `U -> downstream` can be rebuilt from build logs alone. `U` itself is deliberately NOT printed here:
 * it is a property of the trajectory prefix, recomputed offline from the committed transcript, and a
 * copy of it travelling through a build parameter is a copy that can disagree.
 */
fun acquisitionDownstreamLine(
    caseId: String,
    condition: UnderstandingCondition,
    replicate: Int,
    outcome: UnderstandingDownstreamOutcome,
): String = buildString {
    val note = condition as? AcquisitionCheckpointNote
    append("[ACQUISITION-DOWN] case=$caseId condition=${condition.label} ")
    append("trajectory=${note?.trajectoryId ?: "-"} checkpoint=${note?.checkpoint ?: "-"} ")
    append("arm=${note?.arm ?: "-"} replicate=$replicate ${outcome.verdict} ")
    append("oraclePassed=${outcome.oracleTestsPassed}/${outcome.oracleTestsTotal} ")
    append("residual=${outcome.residualTests}")
    outcome.toolCalls?.let { append(" toolCalls=$it") }
    outcome.cost.usd?.let { append(" usd=%.4f".format(java.util.Locale.ROOT, it)) }
    outcome.cost.agentSeconds?.let { append(" agentSeconds=$it") }
    outcome.cost.outputTokens?.let { append(" outputTokens=$it") }
}
