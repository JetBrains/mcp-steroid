/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.util.Locale

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
 * [step] and [position] are copied from the log line rather than recomputed, because they belong to the
 * capture run that produced the state — `position` is `a_i/n` of THAT run's actual step count, and
 * recomputing it here from `n̂` would relabel every measured state with a number no snapshot was taken at.
 */
data class ProbeVerdict(
    val arm: String,
    val checkpoint: Int,
    val step: Int,
    val position: Double,
    val replicate: Int,
    val success: Boolean,
)

/**
 * The one line a probe build prints for the aggregator. Matched anywhere in a line, because in a real
 * build log it arrives behind Gradle's `[:test-experiments:test]` prefix.
 *
 * `Y=([01])` is deliberately narrow: a probe that could not be graded prints something else (`LOST`),
 * and a looser pattern would quietly turn every instrument failure into a graded zero — the one
 * mistake that would bias `V` downwards without leaving a trace.
 */
private val PROBE_VERDICT_LINE = Regex(
    """\[CHECKPOINT-PROBE] arm=(\S+) checkpoint=(\d+) step=(\d+) position=(\S+) replicate=(\d+) Y=([01])"""
)

/**
 * Read every probe verdict out of a concatenation of build logs, in the order the logs carry them.
 *
 * Non-matching lines are skipped without comment — 99% of a build log is not a verdict. A line that
 * matches but carries an unparseable position is NOT skipped: it means the probe printed a broken
 * coordinate, and silently dropping it would shrink a group from 5 runs to 4 while still looking whole.
 */
fun parseProbeVerdicts(logText: String): List<ProbeVerdict> =
    PROBE_VERDICT_LINE.findAll(logText).map { match ->
        val (arm, checkpoint, step, position, replicate, y) = match.destructured
        ProbeVerdict(
            arm = arm,
            checkpoint = checkpoint.toInt(),
            step = step.toInt(),
            position = requireNotNull(position.toDoubleOrNull()) {
                "a checkpoint probe reported the non-numeric position '$position': ${match.value}"
            },
            replicate = replicate.toInt(),
            success = y == "1",
        )
    }.toList()

/**
 * One markdown table per arm, its readiness curve, and the AUC over the range that was actually probed.
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
    verdicts.groupBy { it.arm }.toSortedMap().forEach { (arm, armVerdicts) ->
        val groups = armVerdicts.groupBy { it.checkpoint }.toSortedMap()
            .map { (checkpoint, group) -> summarizeCheckpoint(arm, checkpoint, group) }
        val positions = groups.map { it.position }
        require(positions == positions.sorted() && positions == positions.distinct()) {
            "arm $arm orders its checkpoints ${groups.map { it.checkpoint }} but their positions " +
                "$positions do not increase with them — one of the rows is mislabelled, and the AUC " +
                "would integrate over a range that does not exist"
        }
        appendLine("## $arm")
        appendLine()
        appendLine("| checkpoint | step | position | successes | runs | V |")
        appendLine("|---|---|---|---|---|---|")
        groups.forEach { group ->
            appendLine(
                "| ${group.checkpoint} | ${group.step} | ${group.position.render(3)} " +
                    "| ${group.successes} | ${group.runs} | ${group.renderReadiness()} |"
            )
        }
        appendLine()
        appendCurve(groups)
        appendLine()
    }
}

/** One checkpoint's verdicts collapsed into the row the report prints. */
private class CheckpointSummary(
    val checkpoint: Int,
    val step: Int,
    val position: Double,
    val successes: Int,
    val runs: Int,
) {
    val complete: Boolean get() = runs == RIPPLE_CHECKPOINT_REPLICATES

    /** `V(s_i)` for a complete checkpoint; the word for what a partial group has instead of one. */
    fun renderReadiness(): String =
        if (complete) checkpointReadiness(successes, runs).render(2) else "INCOMPLETE"
}

/**
 * Collapse one checkpoint's verdicts, refusing the two shapes that would silently corrupt `V`.
 *
 * A repeated replicate number means the same build was counted twice — that inflates `runs` past the
 * five real runs and moves `V` towards whichever verdict got duplicated. Two different positions under
 * one checkpoint index mean the verdicts came from capture runs with different step counts, so the
 * table would put probes of two unrelated states on the same row. Both are instrument failures, and
 * both are cheap to make when 50 build logs are concatenated by hand.
 */
private fun summarizeCheckpoint(arm: String, checkpoint: Int, group: List<ProbeVerdict>): CheckpointSummary {
    val replicates = group.map { it.replicate }
    require(replicates.distinct().size == replicates.size) {
        "arm $arm checkpoint $checkpoint has duplicate replicates $replicates — the same probe build " +
            "was counted more than once"
    }
    val positions = group.map { it.position }.distinct()
    require(positions.size == 1) {
        "arm $arm checkpoint $checkpoint reports several positions $positions — its verdicts come " +
            "from capture runs with different step counts and are not probes of one state"
    }
    val steps = group.map { it.step }.distinct()
    require(steps.size == 1) {
        "arm $arm checkpoint $checkpoint reports several steps $steps — its verdicts are not probes " +
            "of one state"
    }
    return CheckpointSummary(
        checkpoint = checkpoint,
        step = steps.single(),
        position = positions.single(),
        successes = group.count { it.success },
        runs = group.size,
    )
}

/**
 * The curve and its area, built ONLY from complete checkpoints and integrated only between the first
 * and the last of them — the range is printed next to the number so no reader can mistake it for an
 * area over the whole trajectory.
 */
private fun StringBuilder.appendCurve(groups: List<CheckpointSummary>) {
    val complete = groups.filter { it.complete }
    val points = complete.map { ReadinessPoint(it.position, checkpointReadiness(it.successes, it.runs)) }
    appendLine(
        "- curve: " + if (points.isEmpty()) {
            "no checkpoint has its $RIPPLE_CHECKPOINT_REPLICATES replicates yet"
        } else {
            points.joinToString(", ") { "(${it.position.render(3)}, ${it.readiness.render(2)})" }
        }
    )
    val completeness = "${complete.size} of ${groups.size} checkpoints have " +
        "$RIPPLE_CHECKPOINT_REPLICATES replicates"
    if (points.size < 2) {
        appendLine("- AUC: NOT MEASURED — $completeness, and an area needs at least two measured points")
        return
    }
    val curve = ReadinessCurve(points)
    val width = curve.rangeTo - curve.rangeFrom
    appendLine(
        "- AUC: ${curve.auc.render(3)} over ${curve.rangeFrom.render(3)}..${curve.rangeTo.render(3)} " +
            "(${(curve.auc / width).render(3)} width-normalised; $completeness)"
    )
}

private fun Double.render(decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", this)
