/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The nine kinds of thing an agent has to work out before it can implement a change in a tree it has
 * never seen.
 *
 * A category is not a score. It exists so that a curve can be read as "which KIND of knowledge arrives
 * first", which is the question the acquisition experiment asks that a single number cannot answer:
 * two arms can reach the same `U` at twenty interactions and still differ in whether the wiring or the
 * invariant was the part they had at five.
 */
enum class AcquisitionFactCategory {
    /** Which existing feature is the right architectural analogue. */
    PRECEDENT,

    /** Where the relevant runtime path starts. */
    ENTRY_POINT,

    /** The interface / SPI that defines the mechanism. */
    ABSTRACTION,

    /** The concrete data and exception types the change actually manipulates. */
    IMPLEMENTATION,

    /** How data and control move between the components at run time. */
    FLOW,

    /** How an implementation becomes reachable at run time. */
    WIRING,

    /** The second, non-obvious mechanism that also has to be touched. */
    SECONDARY_INTEGRATION,

    /** The condition a naive implementation breaks. */
    INVARIANT,

    /** How correctness is observed. */
    VERIFICATION,
}

/**
 * One atomic, objectively gradable fact about the repository that the change cannot be made correctly
 * without.
 *
 * Three fields carry the whole methodology and each of them exists because of a way the previous
 * experiment could have fooled itself:
 *
 * - [statement] is written as a claim that is either true or false of the repository, never as a
 *   quality ("understands the architecture well"). A judge that has to decide whether prose is *good*
 *   is measuring the prose; a judge that has to decide whether a named class really is the one that
 *   reads the stored client is measuring the repository.
 * - [evidenceBundles] is what makes `U_observed` mechanical rather than judged. A bundle is a set of
 *   literals that must occur **together inside one tool result**. Together and in one result, because
 *   the failure mode this defends against is exactly the cheap one: a `find` that prints a hundred
 *   paths, one of which happens to be the right file, is not knowledge of what is in that file, and a
 *   detector keyed on the file name alone would score it as if it were.
 * - [judgeQuestion] is the yes/no a blind judge answers from the hand-off note alone. The note never
 *   names a tool, so the judge cannot infer the arm — which is the only reason the actionable curve is
 *   admissible as a comparison at all.
 *
 * [weight] is 1 for every fact in the pre-registered list. It is a field rather than a constant so that
 * a later round can down-weight a fact WITHOUT rewriting the list and pretending it always looked that
 * way; any value other than 1 must be justified in the design document before the round is run.
 */
data class AcquisitionFact(
    val id: String,
    val category: AcquisitionFactCategory,
    val statement: String,
    val evidenceBundles: List<List<String>>,
    val judgeQuestion: String,
    val weight: Int = 1,
) {
    init {
        check(id.matches(Regex("[A-I][0-9]"))) {
            "a fact id is a category letter and a digit, e.g. `H1`, so a curve can be read without a " +
                "legend: got '$id'"
        }
        check(evidenceBundles.isNotEmpty() && evidenceBundles.all { it.isNotEmpty() }) {
            "$id has an empty evidence bundle, which would mark the fact observed in every prefix " +
                "including the empty one"
        }
        check(weight >= 1) { "$id has weight $weight; a fact nobody scores does not belong in the list" }
    }

    /**
     * True when some single tool result in [toolResults] contains every literal of some bundle.
     *
     * Case-insensitive, because tool results quote source in whatever case the source has and a
     * detector that missed `ClientCRUDContext` because the transcript said `clientCrudContext` would
     * silently under-count one arm — the arm whose tool happens to normalise identifiers.
     */
    fun observedIn(toolResults: List<String>): Boolean =
        toolResults.any { result ->
            evidenceBundles.any { bundle -> bundle.all { result.contains(it, ignoreCase = true) } }
        }
}

/**
 * The pre-registered checklist of a case: the facts, and nothing about how they will be scored.
 *
 * Registered BEFORE any trajectory of either arm is looked at. That ordering is the guarantee the
 * whole experiment rests on, and it is worth spelling out why it is not merely good manners: a
 * checklist written after reading an mcp trajectory would contain the facts that the mcp tools happen
 * to surface, and the acquisition curve would then measure the checklist's provenance rather than the
 * arms. The commit that adds a checklist must therefore precede the commit that adds the first
 * transcript of that case, and [AcquisitionCases] carries the audit note that says so.
 */
data class AcquisitionChecklist(
    val caseId: String,
    val facts: List<AcquisitionFact>,
    /**
     * True when this case is a deliberate POSITIVE CONTROL made of reference facts.
     *
     * The generalization round runs, beside the architecture cases, one case whose knowledge really is
     * a set of references: which declarations share the name, how many call sites a textual rewrite
     * would damage, which modules the fan-out reaches. Semantic tooling should win it by a wide
     * margin, and that is the point — it anchors the top of the predicted ordering, so that a modest
     * effect on an architecture case can be read as modest rather than as "the instrument is blind".
     *
     * It is a declared flag and not an inferred one because the two failure modes are opposite. An
     * architecture checklist that drifted into usages would be won by construction and would prove
     * nothing; a control checklist that drifted into architecture would stop anchoring anything and
     * nobody would notice. So each shape is asserted against what it claims to be.
     */
    val positiveControl: Boolean = false,
) {
    init {
        check(facts.size in 8..15) {
            "$caseId has ${facts.size} facts. Fewer than eight cannot describe a multi-stage change " +
                "and more than fifteen stops being gradable one by one"
        }
        val duplicates = facts.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "$caseId repeats fact ids $duplicates" }
        val categories = facts.map { it.category }.toSet()
        val navigational = setOf(
            AcquisitionFactCategory.PRECEDENT,
            AcquisitionFactCategory.ABSTRACTION,
            AcquisitionFactCategory.IMPLEMENTATION,
            AcquisitionFactCategory.WIRING,
        )
        if (positiveControl) {
            check((categories - navigational).size <= 2) {
                "$caseId claims to be the navigational positive control, but ${categories - navigational} " +
                    "of its facts are architecture facts. A control that looks like the cases it is " +
                    "supposed to bound stops bounding them"
            }
        } else {
            check((categories - navigational).size >= 4) {
                "$caseId is a navigation benchmark in disguise: all but ${categories - navigational} of " +
                    "its facts are the kind a single find-usages answers. A checklist made of usages, " +
                    "callers and implementations would be won by semantic tooling by construction and " +
                    "would say nothing about acquiring an architectural model"
            }
        }
    }

    val totalWeight: Int get() = facts.sumOf { it.weight }

    /** The share of the checklist whose evidence is present in the given tool results. */
    fun observedScore(toolResults: List<String>): Double =
        facts.filter { it.observedIn(toolResults) }.sumOf { it.weight }.toDouble() / totalWeight

    fun observedIds(toolResults: List<String>): List<String> =
        facts.filter { it.observedIn(toolResults) }.map { it.id }

    /** The single fact that measures `P` — the architectural precedent — reported on its own. */
    val precedentFact: AcquisitionFact
        get() = facts.single { it.category == AcquisitionFactCategory.PRECEDENT && it.id == "A1" }
}
