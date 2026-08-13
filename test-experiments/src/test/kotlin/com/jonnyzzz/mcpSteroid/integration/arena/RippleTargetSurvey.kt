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

fun SurveyCandidate.qualifiesAsWide(): Boolean =
    references >= 100 && files >= 20 && modules >= 3 && sameNameDeclarations >= MIN_SAME_NAME_DECLARATIONS

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
