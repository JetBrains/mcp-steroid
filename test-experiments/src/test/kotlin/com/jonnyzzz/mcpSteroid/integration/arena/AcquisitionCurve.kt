/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The pre-registered checkpoints of the acquisition curve, in environment interactions.
 *
 * Four points and a geometric spacing, because the shape being tested is "the same understanding,
 * earlier". A linear grid would spend its resolution where both arms are already saturated; doubling
 * puts three of the four points in the region where the previous rounds showed the arms actually
 * differ, and keeps one far enough out to answer the question that decides how the result reads —
 * whether the shell arm catches up.
 *
 * They are slices of ONE trajectory per arm per replicate, not four separate runs. See
 * [AcquisitionTrajectory].
 */
val ACQUISITION_CHECKPOINTS: List<Int> = listOf(5, 10, 20, 40)

/** The interaction budget every research trajectory of this experiment is run with. */
const val ACQUISITION_RESEARCH_BUDGET: Int = 40

/**
 * The character limit of a distilled hand-off note.
 *
 * 2 000 rather than the 500 the last round settled on, and the reason is that the two rounds measure
 * different things. There, the note WAS the instrument and a tight limit forced the agent to choose
 * what mattered; here the note is a read-out of a knowledge state that has already been fixed by the
 * prefix, and a limit tight enough to force choices would silently turn the actionable curve back into
 * a measurement of selection under pressure. 2 000 is the smallest length at which the previous round
 * saw both arms able to write everything they knew (17/20 and 18/20, no arm difference).
 */
const val ACQUISITION_NOTE_LIMIT_CHARS: Int = 2_000

/** One point of one curve: a checkpoint, its two denominators, and what was known there. */
data class AcquisitionPoint(
    val trajectoryId: String,
    val caseId: String,
    val arm: String,
    val checkpoint: Int,
    val actualCalls: Int,
    val complete: Boolean,
    val cumulativeOutputTokens: Long,
    val elapsedSeconds: Long?,
    val observedScore: Double,
    val observedFactIds: List<String>,
    val precedentObserved: Boolean,
) {
    fun csvRow(): String = listOf(
        trajectoryId, caseId, arm, checkpoint.toString(), actualCalls.toString(), complete.toString(),
        cumulativeOutputTokens.toString(), (elapsedSeconds ?: "").toString(),
        String.format(java.util.Locale.ROOT, "%.4f", observedScore),
        observedFactIds.joinToString("|"), precedentObserved.toString(),
    ).joinToString(",")

    companion object {
        const val CSV_HEADER: String = "trajectory_id,case_id,arm,checkpoint,actual_calls,complete," +
            "cumulative_output_tokens,elapsed_seconds,u_observed,observed_fact_ids,precedent_observed"
    }
}

/**
 * `U_observed(B)` for one trajectory, at every pre-registered checkpoint.
 *
 * This is the mechanical curve: no model is asked anything, the same detector runs over both arms, and
 * the result is reproducible from the committed transcript alone. It answers "was the information in
 * front of the agent", which is a lower bound on understanding and an upper bound on nothing —
 * an agent can be shown a file and take nothing from it, which is exactly why the second curve exists.
 */
fun observedCurve(
    trajectory: AcquisitionTrajectory,
    checklist: AcquisitionChecklist,
    checkpoints: List<Int> = ACQUISITION_CHECKPOINTS,
): List<AcquisitionPoint> {
    check(checklist.caseId == trajectory.caseId) {
        "checklist of '${checklist.caseId}' cannot score a trajectory of '${trajectory.caseId}'"
    }
    return checkpoints.map { checkpoint ->
        val prefix = trajectory.prefix(checkpoint)
        val results = prefix.toolResults
        AcquisitionPoint(
            trajectoryId = trajectory.trajectoryId,
            caseId = trajectory.caseId,
            arm = trajectory.arm,
            checkpoint = checkpoint,
            actualCalls = prefix.actualCalls,
            complete = prefix.complete,
            cumulativeOutputTokens = prefix.cumulativeOutputTokens,
            elapsedSeconds = prefix.elapsedSeconds,
            observedScore = checklist.observedScore(results),
            observedFactIds = checklist.observedIds(results),
            precedentObserved = checklist.precedentFact.observedIn(results),
        )
    }
}

/**
 * `U` read against cumulative output tokens instead of interactions.
 *
 * A step function, and it has to be sampled rather than compared point by point: the two arms never
 * reach the same token count, so "who knows more at ten thousand tokens" is answered by taking each
 * trajectory's last checkpoint at or below that budget. Returning `0.0` below the first checkpoint is
 * the honest reading — before its first interaction an agent has observed nothing.
 */
fun observedAtTokenBudget(points: List<AcquisitionPoint>, tokenBudget: Long): Double =
    points.filter { it.cumulativeOutputTokens <= tokenBudget }.maxByOrNull { it.checkpoint }?.observedScore ?: 0.0

/**
 * The brief that turns a transcript prefix into the hand-off note the actionable curve is judged from.
 *
 * Identical in both arms, run by the same model with NO tools, and given nothing but the prefix. Three
 * properties of this prompt are load-bearing:
 *
 * - it forbids the note to mention how anything was found, which is what keeps the judge blind to the
 *   arm — an mcp transcript is full of tool names and a note that quoted them would label itself;
 * - it forbids invention beyond the prefix, so the note is a read-out of a knowledge state and not a
 *   second research phase performed from the model's Keycloak priors;
 * - it says plainly that gaps are expected at early checkpoints, because a distiller that feels obliged
 *   to fill the page will guess, and a plausible guess scored as knowledge is the fastest way to
 *   flatten both curves into noise.
 */
fun buildAcquisitionDistillPrompt(
    problemStatement: String,
    prefixTranscript: String,
    noteLimitChars: Int = ACQUISITION_NOTE_LIMIT_CHARS,
): String = buildString {
    appendLine("Below is the partial record of an exploration of a large Java repository, made by a")
    appendLine("developer who was preparing to hand the work over. The exploration was interrupted: what")
    appendLine("you see is everything that was learned, and there is no way to go back and look at more.")
    appendLine()
    appendLine("## The task the next developer will have to solve")
    appendLine()
    appendLine(problemStatement.trim())
    appendLine()
    appendLine("## Your job")
    appendLine()
    appendLine("Write the hand-off note that this record supports, for a competent developer who does not")
    appendLine("know the repository, in at most $noteLimitChars characters.")
    appendLine()
    appendLine("Rules:")
    appendLine("1. Use ONLY what is in the record. Do not add anything you happen to know about this or")
    appendLine("   any other project, and do not guess what a file you never saw the inside of contains.")
    appendLine("2. Do not describe HOW anything was found, and never mention a command, a tool or a")
    appendLine("   search. Write about the repository, not about the exploration.")
    appendLine("3. Early records are thin. If the record does not establish something, leave it out or say")
    appendLine("   in one clause that it is unknown. A confident sentence that the record does not support")
    appendLine("   is worse than a gap.")
    appendLine("4. Prefer, in this order: what to imitate and where; what has to change and how the changes")
    appendLine("   depend on each other; what is easy to miss; how to verify.")
    appendLine()
    appendLine("Answer with the note and nothing else.")
    appendLine()
    appendLine("## The record")
    appendLine()
    appendLine(prefixTranscript)
}

/**
 * The checklist, in the form the offline distil-and-judge step consumes.
 *
 * Emitted by the cell that produced the trajectory rather than committed as a second copy, so the
 * rubric a note is judged against is by construction the one that scored its transcript. Two lists of
 * the same fifteen facts, one in Kotlin and one in a script, would drift on the first edit and the
 * drift would be invisible in every number downstream of it.
 */
fun checklistAsJson(checklist: AcquisitionChecklist): String = buildString {
    appendLine("{")
    appendLine("  \"caseId\": ${quoteJson(checklist.caseId)},")
    appendLine("  \"facts\": [")
    checklist.facts.forEachIndexed { index, fact ->
        val comma = if (index == checklist.facts.lastIndex) "" else ","
        appendLine("    {")
        appendLine("      \"id\": ${quoteJson(fact.id)},")
        appendLine("      \"category\": ${quoteJson(fact.category.name)},")
        appendLine("      \"weight\": ${fact.weight},")
        appendLine("      \"statement\": ${quoteJson(fact.statement)},")
        appendLine("      \"judgeQuestion\": ${quoteJson(fact.judgeQuestion)}")
        appendLine("    }$comma")
    }
    appendLine("  ]")
    append("}")
}

private fun quoteJson(text: String): String = buildString {
    append('"')
    for (char in text) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}

/**
 * The prefix, rendered for the distiller.
 *
 * Tool names are replaced by a neutral label for the reason the distiller prompt gives: the note must
 * not be traceable to an arm. The request is kept — WHAT was asked is part of the knowledge state and
 * is not arm-revealing once the tool is anonymous — and results are truncated per call so that one
 * enormous file dump cannot crowd the rest of the prefix out of the distiller's context.
 */
fun renderPrefixTranscript(prefix: AcquisitionPrefix, maxResultChars: Int = 6_000): String =
    prefix.calls.joinToString("\n\n") { call ->
        val result = if (call.resultText.length <= maxResultChars) {
            call.resultText
        } else {
            call.resultText.take(maxResultChars) + "\n… [${call.resultText.length - maxResultChars} more characters]"
        }
        buildString {
            appendLine("### Question ${call.ordinal}")
            appendLine(call.requestJson.take(2_000))
            appendLine()
            appendLine("### What came back")
            append(result)
        }
    }
