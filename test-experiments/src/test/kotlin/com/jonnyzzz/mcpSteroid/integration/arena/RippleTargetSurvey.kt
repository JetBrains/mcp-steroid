/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * One transformation target the survey measured, with every number a case's tripwires will pin.
 *
 * [hierarchyBreadth] is 0 for kinds that do not use it; only pull-up candidates carry a real count.
 */
data class SurveyCandidate(
    val kind: String,
    val ownerFqn: String,
    val name: String,
    val references: Int,
    val files: Int,
    val modules: Int,
    /** Other project declarations sharing this simple name — the lexical-ambiguity axis. */
    val sameNameDeclarations: Int,
    val hierarchyBreadth: Int,
)

/**
 * Parse the survey script's output.
 *
 * Requires the `SURVEY_END` terminator for the same reason the gold parser does: a truncated script
 * would otherwise produce a shorter list that reads as a complete measurement, and a target would be
 * chosen from candidates the script never finished ranking.
 */
fun parseSurveyCandidates(output: String): List<SurveyCandidate> {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "SURVEY_END" }) {
        "Survey output has no SURVEY_END terminator — the script was truncated or failed:\n$output"
    }
    return lines.filter { it.startsWith("SURVEY_CANDIDATE ") }.map { line ->
        val parts = line.removePrefix("SURVEY_CANDIDATE ").split('|')
        check(parts.size == 8) { "Malformed SURVEY_CANDIDATE line: $line" }
        SurveyCandidate(
            kind = parts[0],
            ownerFqn = parts[1],
            name = parts[2],
            references = parts[3].toInt(),
            files = parts[4].toInt(),
            modules = parts[5].toInt(),
            sameNameDeclarations = parts[6].toInt(),
            hierarchyBreadth = parts[7].toInt(),
        )
    }
}

/**
 * The IntelliJ modules holding references to one surveyed declaration.
 *
 * A case's `compileGateModules` is a list of MAVEN artifactIds, and mapping a reference back to one
 * needs the index that found it; the survey used to print only how MANY modules a candidate spanned,
 * so building a gate for a newly chosen target meant guessing from the repository layout. These are
 * IntelliJ module names, which for this reactor are the artifactIds, and every one of them still has
 * to be confirmed against the poms at the base commit before it is pinned.
 */
data class CandidateModules(val ownerFqn: String, val name: String, val modules: List<String>)

fun parseCandidateModules(output: String): List<CandidateModules> =
    parseModuleLines(output, "SURVEY_MODULE_NAMES ")

/**
 * The modules holding declarations that OVERRIDE a rename-method candidate.
 *
 * A second population, and a compile gate built from [parseCandidateModules] alone misses it: an
 * override is not a reference, so a module can hold three implementations of the target and appear in
 * no reference line at all. See [renameMethodGateModules] for what the two are worth together.
 */
fun parseOverrideModules(output: String): List<CandidateModules> =
    parseModuleLines(output, "SURVEY_OVERRIDE_MODULES ")

private fun parseModuleLines(output: String, prefix: String): List<CandidateModules> =
    output.lines().map { it.trim() }.filter { it.startsWith(prefix) }.map { line ->
        val parts = line.removePrefix(prefix).split('|')
        check(parts.size == 3) { "Malformed $prefix line: $line" }
        CandidateModules(
            ownerFqn = parts[0],
            name = parts[1],
            modules = parts[2].split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

/**
 * The compile gate of a **rename-method** case: reference modules ∪ override-family modules ∪ the
 * declaring module.
 *
 * **Why the union, and why only for this kind.** A rename changes a DECLARATION, and every
 * declaration that overrides it must change with it or the subtype stops compiling — `@Override` on a
 * method that overrides nothing, plus an unimplemented abstract member. Those overrides are invisible
 * everywhere else in the oracle: `ReferencesSearch` does not report a declaration, so they are not
 * gold sites; they are inside the target's own override family, which the decoy set excludes on
 * purpose because a correct solution must move them; and the post-condition's alias check inspects
 * the target interface alone. The compile gate is the ONLY layer that sees them, and a gate built
 * from reference modules alone does not compile the module they live in. The retargeted pilot is
 * exactly that shape: `keycloak-model-infinispan` holds three implementations of
 * `UserSessionProvider#getUserSession` and not one call site.
 *
 * **The type-level kinds do not need this.** A rename-type or a move-class changes no member
 * signature, so no subtype is forced to change and no implementation can be left behind; for those
 * kinds every affected file names the type, which makes it a reference, which puts its module in the
 * reference set. `ChangeSignature` DOES have the same override population and states it in its own
 * documentation — its wide case already lists `keycloak-model-jpa` and `keycloak-model-infinispan`
 * for that reason.
 */
fun renameMethodGateModules(
    referenceModules: Collection<String>,
    overrideModules: Collection<String>,
    declaringModule: String,
): List<String> = (referenceModules + overrideModules + declaringModule).distinct().sorted()

/**
 * How many Java string literals in the project spell a candidate's name exactly — the measurement
 * [RippleNameEscapeRule] exists for.
 *
 * Zero is the only value that lets a rename-method case be pinned: a literal is renamed by no
 * compiler and found by no reference search, so a rename that leaves one behind is graded as perfect
 * and is broken at runtime.
 */
data class LiteralNameOccurrences(val ownerFqn: String, val name: String, val occurrences: Int)

fun parseLiteralNameOccurrences(output: String): List<LiteralNameOccurrences> =
    output.lines().map { it.trim() }.filter { it.startsWith("SURVEY_STRING_LITERAL_NAMES ") }.map { line ->
        val parts = line.removePrefix("SURVEY_STRING_LITERAL_NAMES ").split('|')
        check(parts.size == 3) { "Malformed SURVEY_STRING_LITERAL_NAMES line: $line" }
        LiteralNameOccurrences(ownerFqn = parts[0], name = parts[1], occurrences = parts[2].toInt())
    }

/**
 * A read-back of one change-signature target's decoy count, measured through the index under the very
 * exclusion rule the capture script applies.
 *
 * [sameSimpleName] counts every declaration of the simple name in the project, the target included;
 * [decoys] is what remains once the target's own override family is excluded, and is therefore the
 * number a case's `expectedDecoyDeclarations` must equal.
 */
data class DecoyVerification(
    val targetDescription: String,
    val sameSimpleName: Int,
    val decoys: Int,
)

/**
 * Parse `DECOY_VERIFY` lines. Terminator-checked for the same reason the candidate parser is: a
 * truncated run would otherwise hand back a plausible-looking count that no complete measurement
 * stands behind, and the whole point of this read-back is to replace arithmetic with measurement.
 */
fun parseDecoyVerifications(output: String): List<DecoyVerification> {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "DECOY_VERIFY_END" }) {
        "Decoy verification output has no DECOY_VERIFY_END terminator — the script was truncated or failed:\n$output"
    }
    return lines.filter { it.startsWith("DECOY_VERIFY ") }.map { line ->
        val parts = line.removePrefix("DECOY_VERIFY ").split('|')
        check(parts.size == 3) { "Malformed DECOY_VERIFY line: $line" }
        DecoyVerification(
            targetDescription = parts[0],
            sameSimpleName = parts[1].toInt(),
            decoys = parts[2].toInt(),
        )
    }
}

/** Lexical ambiguity is required of BOTH members of a wide/narrow pair, so the pair varies fan-out alone. */
private const val MIN_SAME_NAME_DECLARATIONS = 3

/**
 * The fan-out floor of a wide case, shared with `RippleTargetSurveyScripts.survey`, which prints the
 * module names and the string-literal read-back only from this threshold up. The gate and the verdict
 * must be the same number or the survey would withhold the evidence a qualifying candidate needs.
 */
const val MIN_WIDE_REFERENCES = 100

/** The file-span floor of a wide case, and the word-index pre-gate of the rename-method query. */
const val MIN_WIDE_FILES = 20

/**
 * The ambiguity ceiling of the rename-method query's pool.
 *
 * Ambiguity is required of every case, but `getId` is declared 1021 times in Keycloak and this query
 * pays one reference search per declaration of a name it accepts. The ceiling is a POOL bound, not a
 * criterion: a verdict from that script is a verdict about names no more common than this.
 */
const val MAX_SAME_NAME_DECLARATIONS = 200

/**
 * **These functions answer fan-out, and fan-out alone.** A candidate that clears them is a candidate,
 * not a case: a case additionally has to be behaviour-preserving, and that is [RippleNameEscapeRule]'s
 * question. Nothing here can see a name that a string literal addresses — no compiler and no
 * reference search can — and the family's founding case was pinned on exactly that blind spot. Read
 * [RippleNameEscapeRule] before choosing any target whose NAME the transformation changes.
 */
fun SurveyCandidate.qualifiesAsWide(): Boolean =
    references >= MIN_WIDE_REFERENCES && files >= MIN_WIDE_FILES && modules >= 3 &&
        sameNameDeclarations >= MIN_SAME_NAME_DECLARATIONS

fun SurveyCandidate.qualifiesAsNarrow(): Boolean =
    references in 5..20 && files <= 3 && sameNameDeclarations >= MIN_SAME_NAME_DECLARATIONS

/**
 * The inheritor count a pull-up destination must have. Shared with `RippleTargetSurveyScripts.pullUp`,
 * which gates a supertype on it BEFORE searching any of its methods: the pre-gate and the verdict must
 * be the same number, or the script would omit candidates the verdict would have accepted.
 */
const val MIN_PULL_UP_BREADTH = 8

fun SurveyCandidate.qualifiesForPullUp(): Boolean =
    qualifiesAsWide() && hierarchyBreadth >= MIN_PULL_UP_BREADTH

/** One evaluated pull-up destination: a project-source interface supertype and its inheritor count. */
data class PullUpSuperType(val fqn: String, val breadth: Int)

/**
 * Parse `SURVEY_PULLUP_SUPER` lines — every supertype the pull-up script evaluated, whether or not it
 * cleared [MIN_PULL_UP_BREADTH]. This is the evidence behind a `NONE QUALIFYING` verdict: no candidate
 * below the threshold is ever emitted, so the near-miss on breadth can only be read here.
 */
fun parsePullUpSuperTypes(output: String): List<PullUpSuperType> =
    output.lines().map { it.trim() }.filter { it.startsWith("SURVEY_PULLUP_SUPER ") }.map { line ->
        val parts = line.removePrefix("SURVEY_PULLUP_SUPER ").split('|')
        check(parts.size == 2) { "Malformed SURVEY_PULLUP_SUPER line: $line" }
        PullUpSuperType(fqn = parts[0], breadth = parts[1].toInt())
    }
