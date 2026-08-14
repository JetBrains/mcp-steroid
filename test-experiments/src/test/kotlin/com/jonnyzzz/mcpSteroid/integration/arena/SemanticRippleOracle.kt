/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The enclosing-declaration marker both oracle scripts emit for a reference that lives inside an
 * `import` statement.
 *
 * An import has no enclosing method and no enclosing class, so before this marker existed every
 * import reference landed in the same `<file>` bucket as any other file-level reference and could not
 * be told apart from one. It is a single constant rather than a literal in each script because the
 * scripts emit it and the parsers act on it: two spellings would make the exclusion silently inert.
 */
const val IMPORT_SITE_DECLARATION = "<import>"

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
) {
    /** True when this site is an `import` statement rather than a usage — see [SemanticGold.countedSites]. */
    val isImport: Boolean get() = enclosingDeclaration == IMPORT_SITE_DECLARATION
}

/**
 * The pre-agent semantic state: what a correct rename must move, and what it must leave alone.
 *
 * Two readings live here on purpose. [sites] / [totalReferences] / [files] are the RAW reading — every
 * resolved reference the IDE reports, which is what the survey measured and what
 * [SemanticGold.checkTripwires] pins a case against, so the pinned constants keep meaning exactly what
 * they meant when they were measured. [countedSites] / [countedReferences] are the GRADED reading, with
 * import statements removed; that is the set P2, P4, recall and precision are computed over.
 */
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

    /** References the raw reading holds inside `import` statements. */
    val importReferences: Int get() = sites.filter { it.isImport }.sumOf { it.references }

    /**
     * The sites conservation, P2, recall and precision are computed over: usages, never imports.
     *
     * The family measures usages; an import is bookkeeping that follows from where a usage sits. For a
     * rename and a change-signature the distinction is arithmetically free — the same import reference
     * exists in both readings and cancels — but for a MOVE it is the difference between a meaningful
     * predicate and an impossible one. Classes that lived in the moved class's own package named it
     * with no import at all, and after a correct move they must add one; an import statement is itself
     * a resolved reference, so a perfect move GROWS the total by the number of newly required imports
     * and strict equality can never hold. Both arms of both move cases were scored `SUCCESS: false`
     * for that alone — a perfect move at recall 1.0, precision 0.9000 (9 of 10) on the narrow case and
     * 0.8333 (145 of 174) on the wide one. Excluding imports from BOTH readings restores conservation
     * without weakening it: a usage the agent invented still shows, because it is not an import.
     *
     * A missed import is not left unmeasured either — it cannot compile, and the scoped compile gate
     * is the layer that covers it.
     */
    val countedSites: List<GoldSite> get() = sites.filterNot { it.isImport }

    /** [totalReferences] minus [importReferences] — the denominator of recall. */
    val countedReferences: Int get() = countedSites.sumOf { it.references }

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
    /**
     * P2: every counted gold site — a usage site, at the identity the transformation gives it — now
     * holds at least as many references to the new name as it held to the old.
     */
    val p2AllSitesConverted: Boolean,
    /** P3: every decoy declaration's reference count is unchanged. */
    val p3DecoysUnchanged: Boolean,
    /** P4: counted references to the new name equal the counted gold total, imports excluded from both. */
    val p4Conserved: Boolean,
    val recall: Double,
    val precision: Double,
    val f1: Double,
    val missedSites: List<GoldSite>,
    val overReachedDecoys: List<String>,
    /**
     * References counted in the post-agent reading that live in a hidden-consumer file and were
     * therefore excluded from [p4Conserved] and [precision] — see [parseSemanticPostcondition].
     */
    val excludedConsumerReferences: Int = 0,
    /**
     * References counted in the post-agent reading that live inside an `import` statement and were
     * therefore excluded from [p4Conserved] and [precision] — see [SemanticGold.countedSites] for why
     * both readings drop them.
     */
    val excludedImportReferences: Int = 0,
    /**
     * [excludedImportReferences] minus the gold reading's own import references: how many import
     * statements naming the target the run added (positive) or removed (negative).
     *
     * Reported for every kind, because excluding imports from conservation would otherwise make a
     * spurious import free — an `import` of the transformed type sprayed into a file that never uses it
     * costs no precision, no conservation, and passes the compile gate, since an unused import compiles.
     * Before the exclusion that reference inflated the post total and broke P4; the delta is what keeps
     * it visible now. See [p6ImportCountUnchanged] for the kinds where it is also asserted.
     */
    val importReferenceDelta: Int = 0,
    /**
     * P6, where the kind admits it: the run added and removed no import statement naming the target.
     *
     * `null` means the predicate does not apply, and applicability is a property of the kind, not of
     * the run. A move and a type rename legitimately change the import count — a move ADDS one wherever
     * the class was named from its own old package, a rename rewrites existing ones — so for those
     * kinds [importReferenceDelta] is reported and nothing is asserted from it. A method rename and a
     * signature change cannot: their target is a method, so the only import that can reference it at
     * all is an `import static`, and a behaviour-preserving change to the method neither creates nor
     * destroys one. There the delta must be zero, and a non-zero one is over-reach.
     */
    val p6ImportCountUnchanged: Boolean? = null,
    /**
     * Kind-specific predicates contributed by the [RippleTarget] variant — arity for a signature
     * change, FQN movement for a move, supertype ownership for a pull-up. Kept as a map rather than
     * as more boolean fields because P1–P4 are the family's shared contract and each kind adds at
     * most one or two of its own; a field per kind would make every case carry every other kind's
     * vocabulary.
     */
    val extraPredicates: Map<String, Boolean> = emptyMap(),
) {
    val allPassed: Boolean
        get() = p1NoAliasAndNewNameDeclared && p2AllSitesConverted && p3DecoysUnchanged &&
            p4Conserved && (p6ImportCountUnchanged ?: true) && extraPredicates.values.all { it }
}

/**
 * True when every resolved call site carries the new arity.
 *
 * Presence of the renamed declaration is not enough for a signature change: an agent can add the
 * parameter to the declaration and leave callers passing the old argument list, which fails to
 * compile — but it can equally add an OVERLOAD, which compiles, keeps every call site resolving, and
 * satisfies P1–P4 while performing no signature change at all. This is the predicate that catches it.
 */
fun parseArityPredicate(output: String, expectedArity: Int): Boolean {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "POST_END" }) {
        "Post-condition output has no POST_END terminator — the script was truncated or failed:\n$output"
    }
    fun field(prefix: String): Int =
        (lines.firstOrNull { it.startsWith(prefix) }
            ?: error("Post-condition output is missing the $prefix field:\n$output"))
            .removePrefix(prefix).trim().toInt()
    // The script states the arity it measured against; the case states the arity it asked for. A
    // disagreement means the script and the registry describe different transformations, so the
    // predicate would be answering a question nobody asked — an instrument fault, not a grade.
    val reportedExpected = field("POST_ARITY_EXPECTED ")
    check(reportedExpected == expectedArity) {
        "The post-condition script measured against arity $reportedExpected but the case asks for " +
            "$expectedArity; the script and the case registry disagree:\n$output"
    }
    val total = field("POST_TOTAL_NEW_REFS ")
    return total > 0 && field("POST_ARITY_MATCHING ") == total
}

/**
 * True when the class now resolves at its new fully-qualified name and no longer at the old one.
 *
 * Both halves are needed. A class copied to the new package while a forwarding shell stays behind
 * satisfies every reference-based predicate — the references moved, the counts conserved — and is not
 * a move at all.
 */
fun parseFqnMovePredicate(output: String): Boolean {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "POST_END" }) {
        "Post-condition output has no POST_END terminator — the script was truncated or failed:\n$output"
    }
    fun flag(prefix: String): Boolean =
        (lines.firstOrNull { it.startsWith(prefix) }
            ?: error("Post-condition output is missing the $prefix field:\n$output"))
            .removePrefix(prefix).trim().toBooleanStrict()
    return flag("POST_NEW_FQN_RESOLVES ") && !flag("POST_OLD_FQN_RESOLVES ")
}

/**
 * Parse the capture script's output.
 *
 * Requires the `GOLD_END` terminator: without it a truncated or cancelled script would parse as a
 * smaller — or empty — gold set, and every downstream score would be computed against it silently.
 *
 * [hiddenConsumerFiles] are excluded for the same reason they are excluded from the post-condition:
 * the consumer names BOTH the old and the new method by reflection, and IntelliJ resolves
 * `Class.getMethod("roles")` as a real reference. Counted in, it makes the gold set one larger than
 * the repository's own — 446 against the pinned 445, which is what the tripwire caught on build
 * 1028893177 the moment the consumer's missing imports were fixed and its references began to
 * resolve. The pinned counts describe the repository at the base commit and must stay independent of
 * what our own overlay adds to it.
 */
fun parseSemanticGold(output: String, hiddenConsumerFiles: Set<String> = emptySet()): SemanticGold {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "GOLD_END" }) {
        "Gold capture output has no GOLD_END terminator — the script was truncated or failed:\n$output"
    }
    val header = lines.firstOrNull { it.startsWith("GOLD_TARGET ") }
        ?: error("Gold capture output has no GOLD_TARGET line:\n$output")
    val headerParts = header.removePrefix("GOLD_TARGET ").split('|')
    check(headerParts.size == 3) { "Malformed GOLD_TARGET line: $header" }

    val sites = parseSiteLines(lines, "GOLD_SITE ")
        .filterNot { site -> hiddenConsumerFiles.any { site.file.endsWith(it) } }
    val decoys = parseDecoyLines(lines, "GOLD_DECOY ")
    val newNameDeclarations = (
        lines.firstOrNull { it.startsWith("GOLD_NEWNAME_DECLS ") }
            ?: error("Gold capture output is missing the GOLD_NEWNAME_DECLS field:\n$output")
        ).removePrefix("GOLD_NEWNAME_DECLS ").toInt()

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
 * Parses `$prefix<file>|<enclosing declaration>|<references>` lines into [GoldSite]s.
 *
 * A repeated `(file, enclosingDeclaration)` key is a broken capture script, not two observations to
 * merge: summing would double-count a duplicated gold line, and keeping only one would silently drop
 * the other. Either way a repair-by-guessing would corrupt the count that everything else is graded
 * against, so a repeated key throws instead.
 */
private fun parseSiteLines(lines: List<String>, prefix: String): List<GoldSite> {
    val parsed = lines.filter { it.startsWith(prefix) }.map { line ->
        val parts = line.removePrefix(prefix).split('|')
        check(parts.size == 3) { "Malformed $prefix line: $line" }
        GoldSite(parts[0], parts[1], parts[2].toInt())
    }
    val duplicateKeys = parsed.groupBy { it.file to it.enclosingDeclaration }
        .filterValues { it.size > 1 }.keys
    check(duplicateKeys.isEmpty()) {
        "Duplicate $prefix key(s) $duplicateKeys — each (file, enclosing declaration) must appear " +
            "at most once in $prefix output"
    }
    return parsed
}

/**
 * Parses `$prefix<owner>|<references>` lines into a map. See [parseSiteLines] for why a repeated key
 * throws rather than being merged.
 */
private fun parseDecoyLines(lines: List<String>, prefix: String): Map<String, Int> {
    val parsed = lines.filter { it.startsWith(prefix) }.map { line ->
        val parts = line.removePrefix(prefix).split('|')
        check(parts.size == 2) { "Malformed $prefix line: $line" }
        parts[0] to parts[1].toInt()
    }
    val duplicateKeys = parsed.groupBy { it.first }.filterValues { it.size > 1 }.keys
    check(duplicateKeys.isEmpty()) {
        "Duplicate $prefix key(s) $duplicateKeys — each owner must appear at most once in $prefix output"
    }
    return parsed.toMap()
}

/**
 * Fail the run before the agent starts when the captured world does not match [case]'s pinned
 * measurement. An index failure produces an empty gold set, which would otherwise score as a
 * perfect transformation over nothing.
 *
 * The case is mandatory. A default reaching for one particular case would compile at any call site
 * that forgot to pass its own, and grade it against the pilot's 445/79/16 — which is precisely the
 * silent failure this seam was cut to remove, reintroduced as a convenience.
 */
fun SemanticGold.checkTripwires(case: RippleCase) {
    check(newNameDeclarations == 0) {
        "'${case.target.destinationDescription}' already exists $newNameDeclarations times; the " +
            "destination must be free or the task is ill-posed"
    }
    check(totalReferences == case.expectedGoldReferences) {
        "Gold reference count is $totalReferences, expected ${case.expectedGoldReferences} " +
            "at ${SemanticRippleSpec.baseCommit}. Either the commit moved or the index is incomplete."
    }
    check(files == case.expectedGoldFiles) {
        "Gold spans $files files, expected ${case.expectedGoldFiles}"
    }
    check(decoyReferences.size == case.expectedDecoyDeclarations) {
        "Found ${decoyReferences.size} decoy declarations sharing the simple name of " +
            "'${case.target.targetDescription}', expected ${case.expectedDecoyDeclarations}"
    }
}

/**
 * [checkTripwires] against the pilot's case, for the unit tests that were written against the pilot's
 * numbers before the family had more than one case. Named rather than defaulted so that no production
 * call site can reach the pilot's counts by omission.
 */
fun SemanticGold.checkPilotTripwires() = checkTripwires(RippleCases.renameMethodWide)

/**
 * Parse the post-agent script's output and grade it against [gold].
 *
 * [hiddenConsumerFiles] are the test-patch paths whose references CANNOT exist in [gold] by
 * construction: the hidden consumer names the new method before it exists, so pre-agent that name
 * resolves to nothing and post-agent it resolves — including through
 * `Class.getMethod("realmLevelRoles")`, which IntelliJ resolves as a real reference. Counting those
 * against conservation makes a perfect rename read as one invented reference too many (446 against a
 * gold of 445, in both arms of build 1028521545). They are excluded from [SemanticPostconditionResult.
 * p4Conserved] and from precision, and reported separately; the consumer itself is graded by the
 * compile gate and by its own FAIL_TO_PASS run, not by this count. Paths are matched as suffixes
 * because the reading carries absolute container paths.
 *
 * [expectedPostKey] maps a gold site's key to the identity that same site must carry AFTER the
 * transformation the case asked for. The default is the identity mapping, which is correct for a
 * transformation that leaves every enclosing declaration where it was — a method rename, a signature
 * change. It is NOT correct for a TYPE-level transformation: the target is its own enclosing
 * declaration and the file is named after it, so the type's self-references inside its own file change
 * both halves of the key at once. Both arms of the rename-type wide case scored exactly recall 0.9798
 * and precision 0.9798 (194 of 198, P2 false, no over-reach) because of it — identical scores in both
 * arms being the signature of an oracle artifact rather than of agent behaviour. The mapping comes from
 * the [RippleTarget] variant, which knows the transformation it asked for; see
 * [retargetTypeSiteKey].
 *
 * [importCountIsInvariant] comes from [RippleTarget.importCountIsInvariant] and decides whether the
 * import-count delta is asserted (P6) or merely reported — see
 * [SemanticPostconditionResult.p6ImportCountUnchanged]. It exists because dropping imports from
 * conservation would otherwise make a SPURIOUS import cost nothing at all: it fails no predicate here,
 * and an unused import compiles, so the gate cannot see it either.
 */
fun parseSemanticPostcondition(
    output: String,
    gold: SemanticGold,
    hiddenConsumerFiles: Set<String> = emptySet(),
    extraPredicates: Map<String, Boolean> = emptyMap(),
    expectedPostKey: (GoldSite) -> Pair<String, String> = { it.file to it.enclosingDeclaration },
    importCountIsInvariant: Boolean = false,
): SemanticPostconditionResult {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "POST_END" }) {
        "Post-condition output has no POST_END terminator — the script was truncated or failed:\n$output"
    }
    fun flag(prefix: String): String =
        (lines.firstOrNull { it.startsWith(prefix) }
            ?: error("Post-condition output is missing the $prefix field:\n$output"))
            .removePrefix(prefix).trim()

    val newNameDeclared = flag("POST_NEWNAME_DECLARED ").toBooleanStrict()
    val oldNameOnTarget = flag("POST_OLDNAME_ON_TARGET ").toInt()
    val totalNewRefs = flag("POST_TOTAL_NEW_REFS ").toInt()

    val allPostSites = parseSiteLines(lines, "POST_SITE ")
    check(allPostSites.sumOf { it.references } <= totalNewRefs) {
        "POST_SITE references sum to ${allPostSites.sumOf { it.references }}, which exceeds the " +
            "declared POST_TOTAL_NEW_REFS $totalNewRefs — the capture script or its parsing is broken"
    }
    val (consumerSites, projectSites) = allPostSites.partition { site ->
        hiddenConsumerFiles.any { site.file.endsWith(it) }
    }
    val excludedRefs = consumerSites.sumOf { it.references }
    val (importSites, postSites) = projectSites.partition { it.isImport }
    val excludedImportRefs = importSites.sumOf { it.references }
    val countedNewRefs = totalNewRefs - excludedRefs - excludedImportRefs
    check(countedNewRefs >= 0) {
        "Hidden-consumer references ($excludedRefs) plus import references ($excludedImportRefs) " +
            "exceed POST_TOTAL_NEW_REFS ($totalNewRefs) — the capture script or its parsing is broken"
    }
    val postByKey = postSites.associate { (it.file to it.enclosingDeclaration) to it.references }

    val missed = gold.countedSites.filter { site ->
        (postByKey[expectedPostKey(site)] ?: 0) < site.references
    }
    val convertedAtGold = gold.countedSites.sumOf { site ->
        minOf(postByKey[expectedPostKey(site)] ?: 0, site.references)
    }

    val postDecoys = parseDecoyLines(lines, "POST_DECOY ")
    // Compared as key SETS, not as a lookup with a zero default. A vanished decoy key must be
    // over-reach on its own terms: under a reading whose VALUE can legitimately be zero — a
    // change-signature case keys its decoys by declaration and values them by arity, and a no-arg
    // getter's arity is 0 — a `postDecoys[key] ?: 0` lookup makes "the agent deleted or reshaped
    // this declaration" indistinguishable from "nothing happened", which silenced P3 for the case
    // with the family's strongest lexical ambiguity. An APPEARING key is over-reach too: it is a
    // same-named declaration that did not exist before, which is what an agent creates when it
    // applies the transformation to the wrong symbol.
    val overReached = (gold.decoyReferences.keys + postDecoys.keys)
        .filter { gold.decoyReferences[it] != postDecoys[it] }
        .sorted()

    val recall =
        if (gold.countedReferences == 0) 0.0 else convertedAtGold.toDouble() / gold.countedReferences
    val precision = if (countedNewRefs == 0) 0.0 else convertedAtGold.toDouble() / countedNewRefs
    val f1 = if (recall + precision == 0.0) 0.0 else 2 * recall * precision / (recall + precision)

    return SemanticPostconditionResult(
        p1NoAliasAndNewNameDeclared = newNameDeclared && oldNameOnTarget == 0,
        p2AllSitesConverted = missed.isEmpty(),
        p3DecoysUnchanged = overReached.isEmpty(),
        p4Conserved = countedNewRefs == gold.countedReferences,
        recall = recall,
        precision = precision,
        f1 = f1,
        missedSites = missed,
        overReachedDecoys = overReached,
        excludedConsumerReferences = excludedRefs,
        excludedImportReferences = excludedImportRefs,
        importReferenceDelta = excludedImportRefs - gold.importReferences,
        p6ImportCountUnchanged =
            if (importCountIsInvariant) excludedImportRefs == gold.importReferences else null,
        extraPredicates = extraPredicates,
    )
}
