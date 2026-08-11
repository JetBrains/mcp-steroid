/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * One place a reference to the rename target lives, keyed so it survives the agent's edits.
 *
 * The key is `(file, enclosing declaration)` rather than a line or offset: line numbers shift as
 * soon as the agent touches the file, and an offset-keyed gold set would report false misses.
 */
data class GoldSite(
    val file: String,
    val enclosingDeclaration: String,
    val references: Int,
)

/** The pre-agent semantic state: what a correct rename must move, and what it must leave alone. */
data class SemanticGold(
    val targetFqn: String,
    val oldName: String,
    val newName: String,
    val sites: List<GoldSite>,
    /** Resolved-reference count per same-named declaration that is NOT the target. */
    val decoyReferences: Map<String, Int>,
    /** Declarations of [newName] already in the project — must be zero, or the task is ill-posed. */
    val newNameDeclarations: Int,
) {
    val totalReferences: Int get() = sites.sumOf { it.references }
    val files: Int get() = sites.map { it.file }.toSet().size

    /** Key used to match a post-agent site against this gold set. */
    fun keyOf(site: GoldSite): Pair<String, String> = site.file to site.enclosingDeclaration
}

/**
 * Grade of one run against [SemanticGold]. Every field is measured; none is inferred from the
 * agent's own claim.
 */
data class SemanticPostconditionResult(
    /** P1: the new name is declared on the target type and the old name is gone from it. */
    val p1NoAliasAndNewNameDeclared: Boolean,
    /** P2: every gold site now holds at least as many references to the new name as it held to the old. */
    val p2AllSitesConverted: Boolean,
    /** P3: every decoy declaration's reference count is unchanged. */
    val p3DecoysUnchanged: Boolean,
    /** P4: total references to the new name equal the gold total. */
    val p4Conserved: Boolean,
    val recall: Double,
    val precision: Double,
    val f1: Double,
    val missedSites: List<GoldSite>,
    val overReachedDecoys: List<String>,
) {
    val allPassed: Boolean
        get() = p1NoAliasAndNewNameDeclared && p2AllSitesConverted && p3DecoysUnchanged && p4Conserved
}

/**
 * Parse the capture script's output.
 *
 * Requires the `GOLD_END` terminator: without it a truncated or cancelled script would parse as a
 * smaller — or empty — gold set, and every downstream score would be computed against it silently.
 */
fun parseSemanticGold(output: String): SemanticGold {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "GOLD_END" }) {
        "Gold capture output has no GOLD_END terminator — the script was truncated or failed:\n$output"
    }
    val header = lines.firstOrNull { it.startsWith("GOLD_TARGET ") }
        ?: error("Gold capture output has no GOLD_TARGET line:\n$output")
    val headerParts = header.removePrefix("GOLD_TARGET ").split('|')
    check(headerParts.size == 3) { "Malformed GOLD_TARGET line: $header" }

    val sites = lines.filter { it.startsWith("GOLD_SITE ") }.map { line ->
        val parts = line.removePrefix("GOLD_SITE ").split('|')
        check(parts.size == 3) { "Malformed GOLD_SITE line: $line" }
        GoldSite(parts[0], parts[1], parts[2].toInt())
    }
    val decoys = lines.filter { it.startsWith("GOLD_DECOY ") }.associate { line ->
        val parts = line.removePrefix("GOLD_DECOY ").split('|')
        check(parts.size == 2) { "Malformed GOLD_DECOY line: $line" }
        parts[0] to parts[1].toInt()
    }
    val newNameDeclarations = lines.first { it.startsWith("GOLD_NEWNAME_DECLS ") }
        .removePrefix("GOLD_NEWNAME_DECLS ").toInt()

    return SemanticGold(
        targetFqn = headerParts[0],
        oldName = headerParts[1],
        newName = headerParts[2],
        sites = sites,
        decoyReferences = decoys,
        newNameDeclarations = newNameDeclarations,
    )
}

/**
 * Fail the run before the agent starts when the captured world does not match the pinned
 * measurement. An index failure produces an empty gold set, which would otherwise score as a
 * perfect rename over nothing.
 */
fun SemanticGold.checkTripwires() {
    check(newNameDeclarations == 0) {
        "'${SemanticRippleSpec.newName}' is already declared $newNameDeclarations times; the rename " +
            "target name must be free or the task is ill-posed"
    }
    check(totalReferences == SemanticRippleSpec.expectedGoldReferences) {
        "Gold reference count is $totalReferences, expected ${SemanticRippleSpec.expectedGoldReferences} " +
            "at ${SemanticRippleSpec.baseCommit}. Either the commit moved or the index is incomplete."
    }
    check(files == SemanticRippleSpec.expectedGoldFiles) {
        "Gold spans $files files, expected ${SemanticRippleSpec.expectedGoldFiles}"
    }
    check(decoyReferences.size == SemanticRippleSpec.expectedDecoyDeclarations) {
        "Found ${decoyReferences.size} decoy declarations named '${SemanticRippleSpec.oldName}', " +
            "expected ${SemanticRippleSpec.expectedDecoyDeclarations}"
    }
}

/** Parse the post-agent script's output and grade it against [gold]. */
fun parseSemanticPostcondition(output: String, gold: SemanticGold): SemanticPostconditionResult {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "POST_END" }) {
        "Post-condition output has no POST_END terminator — the script was truncated or failed:\n$output"
    }
    fun flag(prefix: String): String = lines.first { it.startsWith(prefix) }.removePrefix(prefix).trim()

    val newNameDeclared = flag("POST_NEWNAME_DECLARED ").toBooleanStrict()
    val oldNameOnTarget = flag("POST_OLDNAME_ON_TARGET ").toInt()
    val totalNewRefs = flag("POST_TOTAL_NEW_REFS ").toInt()

    val postSites = lines.filter { it.startsWith("POST_SITE ") }.map { line ->
        val parts = line.removePrefix("POST_SITE ").split('|')
        check(parts.size == 3) { "Malformed POST_SITE line: $line" }
        GoldSite(parts[0], parts[1], parts[2].toInt())
    }
    val postByKey = postSites.associate { (it.file to it.enclosingDeclaration) to it.references }

    val missed = gold.sites.filter { site ->
        (postByKey[gold.keyOf(site)] ?: 0) < site.references
    }
    val convertedAtGold = gold.sites.sumOf { site ->
        minOf(postByKey[gold.keyOf(site)] ?: 0, site.references)
    }

    val postDecoys = lines.filter { it.startsWith("POST_DECOY ") }.associate { line ->
        val parts = line.removePrefix("POST_DECOY ").split('|')
        check(parts.size == 2) { "Malformed POST_DECOY line: $line" }
        parts[0] to parts[1].toInt()
    }
    val overReached = gold.decoyReferences.filter { (owner, before) ->
        (postDecoys[owner] ?: 0) != before
    }.keys.sorted()

    val recall = if (gold.totalReferences == 0) 0.0 else convertedAtGold.toDouble() / gold.totalReferences
    val precision = if (totalNewRefs == 0) 0.0 else convertedAtGold.toDouble() / totalNewRefs
    val f1 = if (recall + precision == 0.0) 0.0 else 2 * recall * precision / (recall + precision)

    return SemanticPostconditionResult(
        p1NoAliasAndNewNameDeclared = newNameDeclared && oldNameOnTarget == 0,
        p2AllSitesConverted = missed.isEmpty(),
        p3DecoysUnchanged = overReached.isEmpty(),
        p4Conserved = totalNewRefs == gold.totalReferences,
        recall = recall,
        precision = precision,
        f1 = f1,
        missedSites = missed,
        overReachedDecoys = overReached,
    )
}
