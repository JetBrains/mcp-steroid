package com.jonnyzzz.mcpSteroid.report

/** Whether MCP Steroid was enabled for an agent run. */
enum class McpMode { WITH, WITHOUT }

/**
 * One agent run on one scenario in one MCP mode — the atomic unit the dashboard compares.
 *
 * Every metric is nullable: different sources fill different subsets. The build log (baseline,
 * always present) and the per-run summary JSON / comparison CSV (enhanced, when published) all
 * map onto this one shape, and CI test results supply the authoritative [testStatus] +
 * [testDurationMs]. Merging is "first non-null wins per field" across sources.
 */
data class AgentRun(
    val scenario: String,
    val agent: String,
    val mode: McpMode,
    val buildConfigId: String? = null,
    val buildId: Long? = null,
    /** When the build that produced this run finished (collector meta.json `finishDate`); null for old caches. */
    val finishedAt: java.time.Instant? = null,
    // JUnit / CI test occurrence (authoritative pass/fail of the *test*, lenient: passes if the
    // agent exited cleanly or claimed a fix — NOT a quality signal on its own).
    val testStatus: String? = null,
    val testDurationMs: Long? = null,
    // [ARENA]-reported run facts.
    val agentDurationMs: Long? = null,
    val exitCode: Int? = null,
    val claimedFix: Boolean? = null,
    val usedMcp: Boolean? = null,
    // Build/test outcome the agent produced inside the sandbox (the real quality signal).
    val testsRun: Int? = null,
    val testsFail: Int? = null,
    val buildSuccess: Boolean? = null,
    // Cost / effort.
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheCreationTokens: Long? = null,
    val costUsd: Double? = null,
    val numTurns: Int? = null,
    // Identity of the agent run, straight from the agent output (NDJSON).
    val model: String? = null,
    val agentVersion: String? = null,
    // Token budget the agent ran under (context window / max output), from the NDJSON modelUsage.
    val contextWindow: Long? = null,
    val maxOutputTokens: Long? = null,
    // Per-tool call counts (Read, Edit, Write, Bash, Glob, Grep, steroid_execute_code, …). The dashboard
    // diffs these between the with- and without-MCP runs.
    val toolCalls: Map<String, Int> = emptyMap(),
    val execCodeCalls: Int? = null,
    val summary: String? = null,
    // ── Objective grade, straight from the harness's own verifier ────────────────────────────────
    /** FAIL_TO_PASS green AND no regression. For a ripple run this is NECESSARY but NOT sufficient. */
    val objectiveSuccess: Boolean? = null,
    /** The agent edited the very tests it was graded by — the run is void, whatever else it says. */
    val failToPassTampered: Boolean? = null,
    // ── The semantic-ripple family's own grade (absent on every non-ripple run) ──────────────────
    /** compile gate AND objective FAIL_TO_PASS AND every ripple predicate — the family's real SUCCESS. */
    val rippleSuccess: Boolean? = null,
    val rippleAllPredicatesPassed: Boolean? = null,
    val rippleCompileGatePassed: Boolean? = null,
    val rippleF1: Double? = null,
    val rippleRecall: Double? = null,
    val ripplePrecision: Double? = null,
    /** `P5_ARITY`, `P7_RECEIVER`, `P8_NO_SHIM`, … — the kind-specific predicates, by id. */
    val rippleExtraPredicates: Map<String, Boolean> = emptyMap(),
    // ── Comparability of this arm's COST (see RippleArmComparability on the producer side) ───────
    /** `COMPARABLE` / `NOT_COMPARABLE` / `UNKNOWN`; null when the run carries no verdict at all. */
    val comparabilityVerdict: String? = null,
    /** Always populated alongside the verdict — a verdict without its arithmetic cannot be argued with. */
    val comparabilityReason: String? = null,
    val steroidCalls: Int? = null,
    val bashCalls: Int? = null,
    val totalToolCalls: Int? = null,
    val toolErrorCount: Int? = null,
    val ideCallShare: Double? = null,
)

/**
 * May this run's cost enter a price aggregate?
 *
 * Deliberately NOT a boolean on [AgentRun]: three different states have to stay apart.
 *  - [CostInclusion.INCLUDED] — the run is comparable and untampered.
 *  - [CostInclusion.EXCLUDED] — it completed, but its money means something else than the table claims
 *    (tampered grade, or an mcp arm that barely touched the IDE, or a crashed agent CLI).
 *  - [CostInclusion.UNKNOWN] — nothing was recorded either way. Counting that as excluded would silently
 *    shrink the series; counting it as included would silently pollute it.
 */
enum class CostInclusion { INCLUDED, EXCLUDED, UNKNOWN }

/** A [CostInclusion] together with the sentence that justifies it — never one without the other. */
data class CostInclusionDecision(val state: CostInclusion, val reason: String?)

/** Whether this run's money may be averaged, and why. */
fun AgentRun.costInclusion(): CostInclusionDecision = when {
    failToPassTampered == true ->
        CostInclusionDecision(CostInclusion.EXCLUDED, "FAIL_TO_PASS tampered — the run is void")
    agentCrashed() ->
        CostInclusionDecision(CostInclusion.EXCLUDED, "the agent CLI itself died (exit $exitCode)")
    comparabilityVerdict == "NOT_COMPARABLE" ->
        CostInclusionDecision(CostInclusion.EXCLUDED, comparabilityReason ?: "not comparable")
    comparabilityVerdict == "UNKNOWN" -> CostInclusionDecision(
        CostInclusion.UNKNOWN,
        comparabilityReason ?: "comparability not decidable from what was recorded",
    )
    comparabilityVerdict == "COMPARABLE" -> CostInclusionDecision(CostInclusion.INCLUDED, null)
    else -> CostInclusionDecision(CostInclusion.UNKNOWN, "this run carries no comparability verdict")
}
