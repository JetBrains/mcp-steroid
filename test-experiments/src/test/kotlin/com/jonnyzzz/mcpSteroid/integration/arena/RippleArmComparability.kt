/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Whether an arm's numbers may be put next to the other arm's in a cost table.
 *
 * Three states, not two, on purpose. `usedMcpSteroid` — the existing arm-validity assertion — is
 * "at least one call", which build 1032465247 satisfied with 6 IDE calls against 38 shell commands:
 * a run that pays the whole MCP overhead (tool schemas in every request, the extra prompt section,
 * bulky `execute_code` results in context) and spends it on almost nothing. Averaging such a run into
 * a price comparison measures the COST OF HAVING the IDE, not the cost of USING it. But the opposite
 * mistake — calling a run not-comparable because its transcript could not be read at all — is just as
 * wrong, so missing data is [UNKNOWN], never [NOT_COMPARABLE].
 */
enum class RippleComparabilityVerdict {
    /** The arm's cost may enter the aggregate. */
    COMPARABLE,

    /** The arm ran, but used the IDE too little for its cost to mean what the table would claim. */
    NOT_COMPARABLE,

    /** Not decidable from what was recorded — no transcript, or no threshold fixed yet. */
    UNKNOWN,
}

/**
 * The share of an mcp arm's tool calls that must go to the IDE for its cost to be comparable.
 *
 * **Deliberately null.** The threshold has to come from the distribution of a real series — the
 * fourteen builds on `6c35a0d8c` — and that series has not been read yet. Setting it from the one
 * build we happen to remember (1032465247: 6 IDE calls of 44) would be picking the number that
 * produces the verdict we already have in mind, which is exactly the failure this whole gate exists
 * to prevent. Until a series is read, every mcp arm reports [RippleComparabilityVerdict.UNKNOWN] with
 * its raw counts printed and persisted, which is what an aggregator needs to derive the threshold in
 * the first place.
 *
 * When it is finally set: state in this KDoc which builds were read, what their IDE-call shares were,
 * and where in that distribution the number sits. Do not tune it afterwards against a result.
 */
val RIPPLE_IDE_CALL_SHARE_THRESHOLD: Double? = null

/**
 * How much of an arm's work went through the IDE, and whether that makes the arm comparable.
 *
 * All the call counts come from ONE source — the decoded transcript, via [DecodedLogMetrics] — because
 * that is the source already written into the run-summary JSON. Counting the printed numbers off the
 * raw NDJSON while the persisted ones came from the decoded log would let the build log and the
 * aggregate disagree about the same run. [toolErrorCount] is the single figure the decoded log does
 * not carry; it is read from the raw NDJSON ([extractToolCallStats]) and kept OUT of [totalToolCalls]
 * so the two sources are never summed together.
 *
 * Counts are nullable rather than zero-defaulted: no transcript means the number is unknown, and a
 * printed `0 total` would read as an agent that called nothing.
 */
data class RippleArmComparability(
    val steroidCalls: Int?,
    val bashCalls: Int?,
    val totalToolCalls: Int?,
    /** Tool results flagged `is_error` in the raw NDJSON. Not part of [totalToolCalls]. */
    val toolErrorCount: Int?,
    val verdict: RippleComparabilityVerdict,
    /** Always populated — a verdict without its arithmetic cannot be argued with. */
    val reason: String,
) {
    /** Share of tool calls that went to the IDE, or null when the counts are unknown or empty. */
    val ideCallShare: Double?
        get() {
            val total = totalToolCalls ?: return null
            val steroid = steroidCalls ?: return null
            if (total == 0) return null
            return steroid.toDouble() / total.toDouble()
        }

    /** True only for [RippleComparabilityVerdict.COMPARABLE] — UNKNOWN is not a yes. */
    val comparable: Boolean get() = verdict == RippleComparabilityVerdict.COMPARABLE
}

/** Every tool call the decoded transcript accounts for, IDE and shell alike. */
fun DecodedLogMetrics.totalToolCalls(): Int =
    execCodeCalls + readCalls + writeCalls + editCalls + bashCalls + globCalls + grepCalls

/**
 * Decide comparability for one arm.
 *
 * The shell arm is comparable by construction: it has no IDE to call, so an IDE-call share is not a
 * property it can fail. Only the mcp arm can be measured badly here.
 *
 * This is a measurement-quality verdict, not a grade: it never fails the test and never voids the
 * agent's work. A NOT_COMPARABLE arm still reports its recall, its compile gate and its FAIL_TO_PASS
 * — only its dollars are held out of the price aggregate.
 */
fun rippleArmComparability(
    withMcp: Boolean,
    decoded: DecodedLogMetrics?,
    toolStats: ToolCallStats?,
    ideCallShareThreshold: Double? = RIPPLE_IDE_CALL_SHARE_THRESHOLD,
): RippleArmComparability {
    val total = decoded?.totalToolCalls()
    val base = RippleArmComparability(
        steroidCalls = decoded?.execCodeCalls,
        bashCalls = decoded?.bashCalls,
        totalToolCalls = total,
        toolErrorCount = toolStats?.toolErrorCount,
        verdict = RippleComparabilityVerdict.UNKNOWN,
        reason = "",
    )
    if (!withMcp) {
        return base.copy(
            verdict = RippleComparabilityVerdict.COMPARABLE,
            reason = "shell arm: it was given no IDE access, so an IDE-call share is not something " +
                "it can fail",
        )
    }
    if (decoded == null) {
        return base.copy(
            reason = "UNAVAILABLE: no decoded transcript for this run, so the tool split is unknown — " +
                "which is not the same as an arm that never called the IDE",
        )
    }
    val observed = "the mcp arm made ${decoded.execCodeCalls} of $total tool calls against the IDE " +
        "(${decoded.bashCalls} went to the shell)"
    if (ideCallShareThreshold == null) {
        return base.copy(
            reason = "$observed; no threshold is fixed yet (see RIPPLE_IDE_CALL_SHARE_THRESHOLD), so " +
                "this run is recorded, not judged",
        )
    }
    val share = base.ideCallShare
    if (share == null) {
        return base.copy(
            reason = "the decoded transcript holds no tool calls at all, so the IDE-call share is " +
                "undefined rather than zero",
        )
    }
    val rendered = "%.2f".format(share)
    return if (share >= ideCallShareThreshold) {
        base.copy(
            verdict = RippleComparabilityVerdict.COMPARABLE,
            reason = "$observed — share $rendered is at or above the $ideCallShareThreshold threshold",
        )
    } else {
        base.copy(
            verdict = RippleComparabilityVerdict.NOT_COMPARABLE,
            reason = "$observed — share $rendered is below the $ideCallShareThreshold threshold, so " +
                "this run's cost measures the overhead of HAVING the IDE, not of using it",
        )
    }
}

/** The `[RIPPLE]` lines that make the tool split and the comparability verdict legible in the build log. */
fun rippleToolUsageLines(
    comparability: RippleArmComparability,
    decoded: DecodedLogMetrics?,
): List<String> {
    fun line(label: String, value: String) = "[RIPPLE]   ${(label + ":").padEnd(17)}$value"
    val tools = if (decoded == null) {
        "UNAVAILABLE — no decoded transcript was found for this run"
    } else {
        "${decoded.totalToolCalls()} total (${decoded.execCodeCalls} steroid, " +
            "${decoded.bashCalls} bash, ${decoded.readCalls} read, ${decoded.writeCalls} write, " +
            "${decoded.editCalls} edit, ${decoded.globCalls} glob, ${decoded.grepCalls} grep)"
    }
    val errors = comparability.toolErrorCount
        ?.let { "$it (from the raw NDJSON; not counted in the total above)" }
        ?: "UNAVAILABLE — the raw NDJSON carried no tool_use blocks"
    return listOf(
        line("tools", tools),
        line("tool errors", errors),
        line("comparable", "${comparability.verdict} — ${comparability.reason}"),
    )
}

/**
 * Everything the ripple track knows about a run that the shared arena summary has no field for.
 *
 * The shared summary's `objective_success` is FAIL_TO_PASS green plus no regression — for ripple that
 * is a NECESSARY but not sufficient condition, and reading it as "quality was equal" would publish
 * `change-signature-wide` as a baseline success on the very run where the baseline fails `P5_ARITY`
 * with FAIL_TO_PASS 1/1. [rippleSuccess] is the family's actual verdict, and it is persisted next to
 * the parts it is made of so an aggregate never has to reconstruct it.
 */
data class RippleRunSummary(
    val comparability: RippleArmComparability,
    val compileGatePassed: Boolean,
    val allPredicatesPassed: Boolean,
    /** compile gate AND objective FAIL_TO_PASS AND every predicate — the family's own SUCCESS. */
    val rippleSuccess: Boolean,
    val recall: Double,
    val precision: Double,
    val f1: Double,
    val missedSiteCount: Int,
    val overReachedDecoyCount: Int,
    val p1NoAliasAndNewNameDeclared: Boolean,
    val p2AllSitesConverted: Boolean,
    val p3DecoysUnchanged: Boolean,
    val p4Conserved: Boolean,
    /** Null where the kind legitimately moves imports — see `SemanticPostconditionResult`. */
    val p6ImportCountUnchanged: Boolean?,
    val extraPredicates: Map<String, Boolean>,
    val goldReferences: Int,
    val goldFiles: Int,
    val goldDecoys: Int,
)

/** Build the `ripple` object of the run-summary JSON. */
fun buildRippleRunSummaryJson(summary: RippleRunSummary): JsonObject = buildJsonObject {
    put("ripple_success", summary.rippleSuccess)
    put("compile_gate_passed", summary.compileGatePassed)
    put("all_predicates_passed", summary.allPredicatesPassed)
    put("recall", summary.recall)
    put("precision", summary.precision)
    put("f1", summary.f1)
    put("missed_site_count", summary.missedSiteCount)
    put("over_reached_decoy_count", summary.overReachedDecoyCount)
    put("p1_no_alias", summary.p1NoAliasAndNewNameDeclared)
    put("p2_all_sites", summary.p2AllSitesConverted)
    put("p3_decoys_kept", summary.p3DecoysUnchanged)
    put("p4_conserved", summary.p4Conserved)
    put("p6_imports_kept", summary.p6ImportCountUnchanged)
    put("gold_references", summary.goldReferences)
    put("gold_files", summary.goldFiles)
    put("gold_decoys", summary.goldDecoys)
    put("extra_predicates", buildJsonObject {
        summary.extraPredicates.toSortedMap().forEach { (id, passed) -> put(id, passed) }
    })
    put("comparability", buildJsonObject {
        val c = summary.comparability
        put("verdict", c.verdict.name)
        put("comparable", c.comparable)
        put("reason", c.reason)
        put("steroid_calls", c.steroidCalls)
        put("bash_calls", c.bashCalls)
        put("total_tool_calls", c.totalToolCalls)
        put("tool_error_count", c.toolErrorCount)
        put("ide_call_share", c.ideCallShare)
    })
}
