package com.jonnyzzz.mcpSteroid.report

/**
 * The repeated-run statistics for the semantic-ripple family: n attempts of the same (case × agent ×
 * arm) on ONE revision, aggregated into a median plus its spread, and — where the two arms of the same
 * build can be matched — a paired difference.
 *
 * Why this is a separate aggregate and not an extension of [Comparison]:
 *  - [Comparison] describes exactly one with/without pair, built from `InputReader.readAll().latest`,
 *    which deliberately drops every superseded build. A series of three repeats on one revision is
 *    precisely what that view throws away, so this aggregate reads `allBuilds` instead.
 *  - Quality inclusion and COST inclusion are different questions. A run whose grade is perfectly well
 *    known can still be barred from the price table because it was tampered with, because the agent CLI
 *    died, or because the mcp arm barely touched the IDE — in which case its dollars measure the cost of
 *    HAVING the IDE, not of using it. [RippleLegStats] therefore counts quality over every attempt and
 *    money only over the included ones, and prints every exclusion with its reason.
 *
 * Nothing here weights by recency: [RunHistory] does that on purpose for months-long drift, and a
 * same-revision series has no drift to weight — three attempts of the same thing count the same.
 */

/** Smallest honest description of dispersion: the observed range. Not an interval estimate. */
data class Spread(val min: Double, val max: Double) {
    val width: Double get() = max - min

    /** Does the observed range contain zero — i.e. did the sign of the effect flip across repeats? */
    fun straddlesZero(): Boolean = min <= 0.0 && max >= 0.0
}

/** One excluded attempt, kept as evidence: a bare exclusion count invites "excluded for what?". */
data class RippleExclusion(val buildId: Long?, val reason: String)

/** One arm of one (case × agent), over all its attempts on the collected revision. */
data class RippleLegStats(
    val scenario: String,
    val agent: String,
    val mode: McpMode,
    /** Every attempt found, including the ones excluded from the money. */
    val attempts: Int,
    /** Attempts whose ripple SUCCESS is known — quality survives exclusions from the cost aggregate. */
    val qualityKnown: Int,
    val rippleSuccesses: Int,
    val includedInCost: Int,
    val unknownComparability: Int,
    val exclusions: List<RippleExclusion>,
    val medianCostUsd: Double?,
    val costSpread: Spread?,
    val medianTurns: Double?,
    val turnsSpread: Spread?,
    val medianAgentDurationMs: Double?,
    val durationSpread: Spread?,
    /** Tokens the arm pays for merely carrying the tools: cache reads + prompt input. */
    val medianOverheadTokens: Double?,
    /** Tokens the arm spent producing an answer. */
    val medianWorkTokens: Double?,
)

/** One (case × agent): both arms, their paired differences, and what may honestly be said about them. */
data class RippleCaseSeries(
    val scenario: String,
    val agent: String,
    val withMcp: RippleLegStats?,
    val without: RippleLegStats?,
    /** Per-build (with − without) cost differences, over builds where BOTH arms are cost-included. */
    val pairedCostDeltas: List<Double>,
    val pairedTurnDeltas: List<Double>,
    val medianPairedCostDelta: Double?,
    val pairedCostSpread: Spread?,
    /** The one sentence the whitepaper may quote. Refuses to name a difference it cannot defend. */
    val statement: String,
)

/** Attempts below which no difference is named, however clean the numbers look. */
const val RIPPLE_MIN_REPEATS = 3

/**
 * Build the ripple series from EVERY collected build (`InputReader.readAll().allBuilds`).
 *
 * Non-ripple scenarios are ignored: this table exists for the family that is actually run repeatedly,
 * and folding a one-shot DPAIA run into a "median of 1" would dress a single run as a distribution.
 */
fun rippleSeries(allBuilds: List<AgentRun>): List<RippleCaseSeries> {
    val ripple = allBuilds.filter { scenarioBucket(it.scenario) == ScenarioBucket.RIPPLE }
    return ripple.groupBy { it.scenario to it.agent }
        .map { (key, runs) -> caseSeries(key.first, key.second, runs) }
        .sortedWith(compareBy({ it.scenario }, { it.agent }))
}

private fun caseSeries(scenario: String, agent: String, runs: List<AgentRun>): RippleCaseSeries {
    val withRuns = runs.filter { it.mode == McpMode.WITH }
    val withoutRuns = runs.filter { it.mode == McpMode.WITHOUT }
    val withLeg = withRuns.takeIf { it.isNotEmpty() }?.let { legStats(scenario, agent, McpMode.WITH, it) }
    val withoutLeg = withoutRuns.takeIf { it.isNotEmpty() }?.let { legStats(scenario, agent, McpMode.WITHOUT, it) }

    // Pair strictly by build id: the two arms of ONE build ran back to back on one agent, which is the
    // only pairing the data supports. Pairing by list position would silently marry unrelated attempts.
    val withByBuild = withRuns.filter { it.costIncluded() }.associateBy { it.buildId }
    val withoutByBuild = withoutRuns.filter { it.costIncluded() }.associateBy { it.buildId }
    val sharedBuilds = withByBuild.keys.filterNotNull().filter { withoutByBuild.containsKey(it) }.sorted()
    val costDeltas = sharedBuilds.mapNotNull { id ->
        val a = withByBuild[id]?.costUsd ?: return@mapNotNull null
        val b = withoutByBuild[id]?.costUsd ?: return@mapNotNull null
        a - b
    }
    val turnDeltas = sharedBuilds.mapNotNull { id ->
        val a = withByBuild[id]?.numTurns ?: return@mapNotNull null
        val b = withoutByBuild[id]?.numTurns ?: return@mapNotNull null
        (a - b).toDouble()
    }

    return RippleCaseSeries(
        scenario = scenario,
        agent = agent,
        withMcp = withLeg,
        without = withoutLeg,
        pairedCostDeltas = costDeltas,
        pairedTurnDeltas = turnDeltas,
        medianPairedCostDelta = median(costDeltas),
        pairedCostSpread = spread(costDeltas),
        statement = statement(costDeltas),
    )
}

private fun legStats(scenario: String, agent: String, mode: McpMode, runs: List<AgentRun>): RippleLegStats {
    val included = runs.filter { it.costIncluded() }
    val decisions = runs.map { it to it.costInclusion() }
    return RippleLegStats(
        scenario = scenario,
        agent = agent,
        mode = mode,
        attempts = runs.size,
        qualityKnown = runs.count { it.rippleSuccess != null },
        rippleSuccesses = runs.count { it.rippleSuccess == true },
        includedInCost = included.size,
        unknownComparability = decisions.count { it.second.state == CostInclusion.UNKNOWN },
        exclusions = decisions.filter { it.second.state == CostInclusion.EXCLUDED }
            .map { (run, decision) -> RippleExclusion(run.buildId, decision.reason ?: "excluded") },
        medianCostUsd = median(included.mapNotNull { it.costUsd }),
        costSpread = spread(included.mapNotNull { it.costUsd }),
        medianTurns = median(included.mapNotNull { it.numTurns?.toDouble() }),
        turnsSpread = spread(included.mapNotNull { it.numTurns?.toDouble() }),
        medianAgentDurationMs = median(included.mapNotNull { it.agentDurationMs?.toDouble() }),
        durationSpread = spread(included.mapNotNull { it.agentDurationMs?.toDouble() }),
        // The cost split that "+52%" was missing: what an arm pays for carrying the tool schemas and
        // the extra prompt section (cache reads + input) versus what it pays for producing an answer.
        medianOverheadTokens = median(
            included.mapNotNull { r ->
                if (r.cacheReadTokens == null && r.inputTokens == null) null
                else ((r.cacheReadTokens ?: 0L) + (r.inputTokens ?: 0L)).toDouble()
            }
        ),
        medianWorkTokens = median(included.mapNotNull { it.outputTokens?.toDouble() }),
    )
}

/** Cost-inclusion as the single boolean the aggregates filter on; the reason stays on the decision. */
private fun AgentRun.costIncluded(): Boolean = costInclusion().state == CostInclusion.INCLUDED

/**
 * The sentence the report prints instead of a headline number.
 *
 * Two refusals, both deliberate and both needed before publication:
 *  - fewer than [RIPPLE_MIN_REPEATS] usable pairs — a difference from one or two attempts is an anecdote;
 *  - a paired range that straddles zero — the arms swapped places across repeats, so the median
 *    difference is inside the noise and naming its direction would be picking a favourite repeat.
 */
private fun statement(costDeltas: List<Double>): String {
    val n = costDeltas.size
    if (n < RIPPLE_MIN_REPEATS) {
        return "insufficient repeats: n=$n usable pair(s), $RIPPLE_MIN_REPEATS required — no difference is named"
    }
    val s = spread(costDeltas) ?: return "no paired cost data — no difference is named"
    val med = median(costDeltas) ?: return "no paired cost data — no difference is named"
    if (s.straddlesZero()) {
        return "difference within spread (paired Δ ranges %.2f…%.2f over n=%d) — no difference is named"
            .format(s.min, s.max, n)
    }
    val direction = if (med < 0) "cheaper" else "more expensive"
    return "mcp arm %.2f USD %s per run (median of n=%d paired runs, range %.2f…%.2f)"
        .format(Math.abs(med), direction, n, s.min, s.max)
}

/** Plain unweighted median; the mean of the two middles for an even count. Null for no data. */
fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
}

/** Observed min…max, or null when there is nothing to describe. */
fun spread(values: List<Double>): Spread? =
    if (values.isEmpty()) null else Spread(values.min(), values.max())
