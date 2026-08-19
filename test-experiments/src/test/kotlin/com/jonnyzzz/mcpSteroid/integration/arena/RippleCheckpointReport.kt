/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.util.Locale
import kotlin.math.roundToLong

/**
 * How many probe replicates one checkpoint needs before its `V` may be printed.
 *
 * Five is also the resolution of the measurement: `V` can only be a multiple of 0.2, so a difference
 * smaller than that between two checkpoints is not something this pilot can see. The number lives here
 * because the aggregator is the only place that can tell a fully probed checkpoint from a half-probed
 * one — the probe build knows nothing about its 49 siblings.
 */
val RIPPLE_CHECKPOINT_REPLICATES: Int = 5

/**
 * One graded probe run: replicate [replicate] of checkpoint [checkpoint] of the [arm] capture.
 *
 * [step], [editFraction] and [position] are copied from the log line rather than recomputed, because
 * they belong to the capture run that produced the state — `position` is `a_i/n` of THAT run's actual
 * step count and `editFraction` is measured from THAT run's first write, so recomputing either here
 * would relabel a measured state with a number no snapshot was taken at.
 *
 * [editFraction] and the three cost fields are nullable for one reason only: 38 verdicts were recorded
 * on TeamCity before the probe line carried them. Null is "this run never reported it" and is not the
 * same measurement as zero — a zero-dollar probe or a probe at the very first write both exist, and
 * folding an unknown into either would publish a number nobody measured.
 *
 * [usd], [agentSeconds] and [tokens] are what a continuation from this state COST. They are the pilot's
 * second signal: the probe's base rate on the pristine tree is already high and `V` saturates well
 * before the trajectory ends, after which the only thing still separating two states is how much a weak
 * agent has to spend to finish from each.
 */
data class ProbeVerdict(
    val arm: String,
    val checkpoint: Int,
    val step: Int,
    val position: Double,
    val replicate: Int,
    val success: Boolean,
    val editFraction: Double? = null,
    val usd: Double? = null,
    val agentSeconds: Long? = null,
    val tokens: Long? = null,
)

/**
 * The one line a probe build prints for the aggregator. Matched anywhere in a line, because in a real
 * build log it arrives behind Gradle's `[:test-experiments:test]` prefix.
 *
 * `Y=([01])` is deliberately narrow: a probe that could not be graded prints something else (`LOST`),
 * and a looser pattern would quietly turn every instrument failure into a graded zero — the one
 * mistake that would bias `V` downwards without leaving a trace.
 *
 * Four groups are OPTIONAL, and that is a compatibility contract rather than laxity: `editFraction` and
 * the three cost fields were added after 38 verdicts had already been recorded on TeamCity. Those runs
 * measured real states and must keep folding into `V`; a stricter pattern would silently drop them and
 * shrink whole groups from five runs to none while still looking like a complete parse. Each optional
 * field is matched independently, so a line carrying some of them is not thrown away for missing the
 * rest.
 */
private val PROBE_VERDICT_LINE = Regex(
    """\[CHECKPOINT-PROBE] arm=(\S+) checkpoint=(\d+) step=(\d+)(?: editFraction=(\S+))?""" +
        """ position=(\S+) replicate=(\d+) Y=([01])""" +
        """(?: usd=(\S+))?(?: agentSeconds=(\d+))?(?: tokens=(\d+))?"""
)

/**
 * Read every probe verdict out of a concatenation of build logs, in the order the logs carry them.
 *
 * Non-matching lines are skipped without comment — 99% of a build log is not a verdict. A line that
 * matches but carries an unparseable coordinate or price is NOT skipped: it means the probe printed a
 * broken number, and silently dropping it would shrink a group from 5 runs to 4 while still looking
 * whole.
 */
fun parseProbeVerdicts(logText: String): List<ProbeVerdict> =
    PROBE_VERDICT_LINE.findAll(logText).map { match ->
        fun optional(group: Int): String? = match.groups[group]?.value
        fun number(group: Int, field: String): Double? = optional(group)?.let { raw ->
            requireNotNull(raw.toDoubleOrNull()) {
                "a checkpoint probe reported the non-numeric $field '$raw': ${match.value}"
            }
        }
        ProbeVerdict(
            arm = match.groupValues[1],
            checkpoint = match.groupValues[2].toInt(),
            step = match.groupValues[3].toInt(),
            position = requireNotNull(number(5, "position")) { "unreachable: position is not optional" },
            replicate = match.groupValues[6].toInt(),
            success = match.groupValues[7] == "1",
            editFraction = number(4, "editFraction"),
            usd = number(8, "usd"),
            // The two counters are matched as \d+, so a group that is present is a number by construction.
            agentSeconds = optional(9)?.toLong(),
            tokens = optional(10)?.toLong(),
        )
    }.toList()

/**
 * One markdown table per arm, its readiness curve, the AUC over the range that was actually probed, and
 * — when both arms are present — the two of them side by side on the axis they share.
 *
 * A row is one STATE, keyed by `(arm, step)`. Not by the checkpoint ordinal: the ordinals were
 * renumbered when the axis changed, so a stale one would merge two unrelated trees into a single `V`,
 * while the step names the state directly (the committed patch is `step-<n>.patch`).
 *
 * A checkpoint whose run count is not [RIPPLE_CHECKPOINT_REPLICATES] renders `INCOMPLETE` instead of a
 * `V`, and stays out of the curve: the mean of a partial group is a different quantity from the one
 * this pilot defines, and `1/1 = 1.00` printed next to `5/5 = 1.00` would be indistinguishable in the
 * published table. For the same reason the AUC is reported as `NOT MEASURED` rather than approximated
 * when fewer than two checkpoints are complete.
 */
fun renderCheckpointReport(verdicts: List<ProbeVerdict>): String = buildString {
    if (verdicts.isEmpty()) {
        appendLine(
            "no checkpoint probe verdicts were found — nothing was measured, which is not the same as " +
                "a readiness of zero"
        )
        return@buildString
    }
    val perArm = verdicts.groupBy { it.arm }.toSortedMap().mapValues { (arm, armVerdicts) ->
        armVerdicts.groupBy { it.step }.toSortedMap()
            .map { (step, group) -> summarizeState(arm, step, group) }
            .also { requireMonotonic(arm, it) }
    }
    perArm.forEach { (arm, groups) ->
        appendLine("## $arm")
        appendLine()
        appendLine("| editFraction | step | position | successes | runs | V | median usd | " +
            "median agentSeconds | median tokens |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        groups.forEach { appendLine(it.renderRow()) }
        appendLine()
        appendCurve(groups)
        appendLine()
    }
    if (perArm.size > 1) appendArmComparison(perArm)
}

/** One state's verdicts collapsed into the row the report prints. */
private class CheckpointSummary(
    val step: Int,
    val editFraction: Double?,
    val position: Double,
    val successes: Int,
    val runs: Int,
    val medianUsd: Double?,
    val medianAgentSeconds: Double?,
    val medianTokens: Double?,
) {
    val complete: Boolean get() = runs == RIPPLE_CHECKPOINT_REPLICATES

    val readiness: Double get() = checkpointReadiness(successes, runs)

    /** `V(s_i)` for a complete checkpoint; the word for what a partial group has instead of one. */
    fun renderReadiness(): String = if (complete) readiness.render(2) else "INCOMPLETE"

    fun renderRow(): String = "| ${editFraction?.render(3) ?: NOT_REPORTED} | $step " +
        "| ${position.render(4)} | $successes | $runs | ${renderReadiness()} " +
        "| ${medianUsd?.render(4) ?: NOT_REPORTED} " +
        "| ${medianAgentSeconds?.roundToLong() ?: NOT_REPORTED} " +
        "| ${medianTokens?.roundToLong() ?: NOT_REPORTED} |"
}

/**
 * What a column says when no verdict of the group ever carried that number. Never `0` and never an
 * empty cell: both read as a measurement, and this pilot's whole cost signal is a set of numbers that
 * exist for some rows and not for others.
 */
private const val NOT_REPORTED: String = "n/a"

/**
 * Collapse one state's verdicts, refusing the three shapes that would silently corrupt the table.
 *
 * A repeated replicate number means the same build was counted twice — that inflates `runs` past the
 * five real runs and moves `V` towards whichever verdict got duplicated. Two different positions or two
 * different edit fractions under one step mean the verdicts came from capture runs with different step
 * counts or different first writes, so the row would put probes of two unrelated trajectories on one
 * line. All three are instrument failures, and all three are cheap to make when 50 build logs are
 * concatenated by hand.
 *
 * The medians are taken over the SUCCESSFUL runs only. A failed probe stops when its budget runs out
 * rather than when the work is done, so its price measures the timeout and not the state; mixing the
 * two would make an expensive-looking row out of a state that simply defeats the weak agent.
 */
private fun summarizeState(arm: String, step: Int, group: List<ProbeVerdict>): CheckpointSummary {
    val replicates = group.map { it.replicate }
    require(replicates.distinct().size == replicates.size) {
        "arm $arm step $step has duplicate replicates $replicates — the same probe build was counted " +
            "more than once"
    }
    val positions = group.map { it.position }.distinct()
    require(positions.size == 1) {
        "arm $arm step $step reports several positions $positions — its verdicts come from capture " +
            "runs with different step counts and are not probes of one state"
    }
    val fractions = group.mapNotNull { it.editFraction }.distinct()
    require(fractions.size <= 1) {
        "arm $arm step $step reports several edit fractions $fractions — either the verdicts come from " +
            "captures with different first writes, or one of them was recorded against a grid this " +
            "report no longer describes"
    }
    val succeeded = group.filter { it.success }
    return CheckpointSummary(
        step = step,
        editFraction = fractions.singleOrNull(),
        position = positions.single(),
        successes = succeeded.size,
        runs = group.size,
        medianUsd = succeeded.mapNotNull { it.usd }.median(),
        medianAgentSeconds = succeeded.mapNotNull { it.agentSeconds?.toDouble() }.median(),
        medianTokens = succeeded.mapNotNull { it.tokens?.toDouble() }.median(),
    )
}

/**
 * Both coordinates must grow with the step, or the arm's rows describe a trajectory that never existed
 * and every area integrated over them is over a range that does not exist either.
 */
private fun requireMonotonic(arm: String, groups: List<CheckpointSummary>) {
    val positions = groups.map { it.position }
    require(positions == positions.sorted() && positions == positions.distinct()) {
        "arm $arm orders its states ${groups.map { it.step }} but their positions $positions do not " +
            "increase with them — one of the rows is mislabelled"
    }
    val fractions = groups.mapNotNull { it.editFraction }
    require(fractions == fractions.sorted() && fractions == fractions.distinct()) {
        "arm $arm orders its states ${groups.map { it.step }} but their edit fractions $fractions do " +
            "not increase with them — one of the rows is mislabelled, and the AUC would integrate " +
            "over a range that does not exist"
    }
}

/**
 * The curve and its area over the EDIT FRACTION, built only from complete checkpoints and integrated
 * only between the first and the last of them — the range is printed next to the number so no reader
 * can mistake it for an area over the whole edit phase.
 *
 * A complete row recorded before the fraction axis existed has no coordinate to be drawn at. It stays
 * in the table, because its `V` was really measured, and it is counted OUT of the area out loud: a
 * reader must not have to compare two row counts to notice that the curve is thinner than the table.
 */
private fun StringBuilder.appendCurve(groups: List<CheckpointSummary>) {
    val complete = groups.filter { it.complete }
    val onAxis = complete.filter { it.editFraction != null }
    val points = onAxis.map { ReadinessPoint(requireNotNull(it.editFraction), it.readiness) }
    appendLine(
        "- curve: " + when {
            complete.isEmpty() -> "no checkpoint has its $RIPPLE_CHECKPOINT_REPLICATES replicates yet"
            points.isEmpty() -> "no complete checkpoint carries an editFraction to be drawn at"
            else -> points.joinToString(", ") { "(${it.coordinate.render(3)}, ${it.readiness.render(2)})" }
        }
    )
    val completeness = buildString {
        append("${complete.size} of ${groups.size} checkpoints have $RIPPLE_CHECKPOINT_REPLICATES replicates")
        val offAxis = complete.size - onAxis.size
        if (offAxis > 0) append("; $offAxis of them carry no editFraction and are not on the curve")
    }
    if (points.size < 2) {
        appendLine("- AUC: NOT MEASURED — $completeness, and an area needs at least two measured points")
        return
    }
    val curve = ReadinessCurve(points)
    val width = curve.rangeTo - curve.rangeFrom
    appendLine(
        "- AUC: ${curve.auc.render(3)} over edit fraction ${curve.rangeFrom.render(3)}.." +
            "${curve.rangeTo.render(3)} (${(curve.auc / width).render(3)} width-normalised; $completeness)"
    )
}

/**
 * The arms on one axis, which is the comparison this whole report exists for.
 *
 * Only the edit fraction can carry it. The arms' steps are incomparable by construction (the pilot's
 * captures wrote for 11 and 40 tool calls), and so are their positions, since each is normalized by its
 * own `n`. A row exists for every fraction ANY arm measured, so an arm that has not been probed there
 * shows as [NOT_REPORTED] instead of quietly shortening the comparison to whatever both happen to have.
 */
private fun StringBuilder.appendArmComparison(perArm: Map<String, List<CheckpointSummary>>) {
    val arms = perArm.keys.toList()
    val fractions = perArm.values.flatMap { groups -> groups.mapNotNull { it.editFraction } }
        .distinct()
        .sorted()
    if (fractions.isEmpty()) return
    appendLine("## ${arms.joinToString(" vs ")} at the same edit fraction")
    appendLine()
    appendLine("| editFraction | " + arms.joinToString(" | ") { "V $it" } + " |")
    appendLine("|---|" + arms.joinToString("") { "---|" })
    fractions.forEach { fraction ->
        val cells = arms.map { arm ->
            perArm.getValue(arm).firstOrNull { it.editFraction == fraction }?.renderReadiness()
                ?: NOT_REPORTED
        }
        appendLine("| ${fraction.render(3)} | " + cells.joinToString(" | ") + " |")
    }
    appendLine()
}

/**
 * The middle value, or the mean of the two middle ones — null for an empty sample rather than zero.
 *
 * A median and not a mean, because a single probe that thrashed for its whole budget before finishing
 * is a real run but not a typical price, and five samples are far too few for it to average out.
 */
private fun List<Double>.median(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private fun Double.render(decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", this)
