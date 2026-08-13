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

/** Lexical ambiguity is required of BOTH members of a wide/narrow pair, so the pair varies fan-out alone. */
private const val MIN_SAME_NAME_DECLARATIONS = 3

fun SurveyCandidate.qualifiesAsWide(): Boolean =
    references >= 100 && files >= 20 && modules >= 3 && sameNameDeclarations >= MIN_SAME_NAME_DECLARATIONS

fun SurveyCandidate.qualifiesAsNarrow(): Boolean =
    references in 5..20 && files <= 3 && sameNameDeclarations >= MIN_SAME_NAME_DECLARATIONS

fun SurveyCandidate.qualifiesForPullUp(): Boolean = qualifiesAsWide() && hierarchyBreadth >= 8
