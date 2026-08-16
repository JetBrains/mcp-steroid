/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * What a TEXT search would get wrong about one target, measured rather than argued.
 *
 * **Why this exists.** Until now a rename case was admitted on FAN-OUT (references, files, modules)
 * and on how many declarations shared the simple name. Neither number says that a textual solution
 * must fail. The retarget of `rename-method-wide` is the worked example: `KeycloakContext#setRealm`
 * has 496 references and 37 same-named foreign declarations, and the arms still tied — 37
 * DECLARATIONS create no trap for `sed`, because a declaration nobody calls is never touched by a
 * replacement of CALL sites. What traps a textual replacement is a foreign CALL SITE that the same
 * pattern matches: `protocol.setRealm(realm)` is indistinguishable from `context.setRealm(realm)`
 * without the receiver's type.
 *
 * So the metric is three measured numbers, all from `RippleTargetSurveyScripts.textAmbiguity`:
 *
 * - [textualOccurrences] — how often the simple name occurs as a word IN CODE anywhere in the
 *   project. This is the upper bound of what any text tool sees.
 * - [resolvedReferences] — how many of those are real references to the target. This is what the
 *   IDE sees, and it is the same number a case pins as `expectedGoldReferences`.
 * - [foreignSameNameCallSites] — references to same-named declarations OUTSIDE the target's own
 *   override family. These are the sites a text replacement rewrites and must not.
 *
 * [discriminates] is the admission rule: `textualOccurrences <= resolvedReferences` means every
 * textual hit IS a target reference, so a blind textual replacement is CORRECT by construction and
 * the case measures nothing about semantics. Such a candidate is rejected — see [requireAdmissible].
 */
data class TextAmbiguity(
    val kind: String,
    val ownerFqn: String,
    val name: String,
    val textualOccurrences: Int,
    val resolvedReferences: Int,
    val foreignSameNameCallSites: Int,
) {

    /** The target this reading is about, in the same spelling [RippleTarget.targetDescription] uses. */
    val targetDescription: String
        get() = if (kind == "rename-type") ownerFqn else "$ownerFqn#$name"

    /**
     * Textual hits that are NOT references to the target — the sites a text tool cannot tell apart
     * from the ones it must change.
     */
    val textualOverReach: Int get() = textualOccurrences - resolvedReferences

    /**
     * Does a textual solution have to be wrong here?
     *
     * Strict `>`: equality means the text search and the reference search agree site for site, and
     * an experiment run on such a target compares two ways of spelling the same edit.
     */
    val discriminates: Boolean get() = textualOccurrences > resolvedReferences

    /**
     * The stronger of the two readings, and the one the retarget is judged on: a foreign call site
     * is a hit a text tool actively rewrites, not merely one it cannot classify.
     */
    val hasForeignTrap: Boolean get() = foreignSameNameCallSites > 0

    fun report(): String =
        "$kind $targetDescription: textual=$textualOccurrences resolved=$resolvedReferences " +
            "foreignCallSites=$foreignSameNameCallSites over-reach=$textualOverReach " +
            (if (discriminates) "DISCRIMINATES" else "REJECTED (text cannot be wrong here)") +
            (if (hasForeignTrap) "" else " — NO FOREIGN CALL-SITE TRAP")
}

/**
 * Parse `SURVEY_TEXT_AMBIGUITY` lines.
 *
 * Terminator-checked for the reason every other survey parser is: a truncated script would hand back
 * a shorter list that reads as a complete measurement, and a target would then be admitted on numbers
 * nobody finished measuring.
 */
fun parseTextAmbiguity(output: String): List<TextAmbiguity> {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "SURVEY_TEXT_AMBIGUITY_END" }) {
        "Text-ambiguity output has no SURVEY_TEXT_AMBIGUITY_END terminator — the script was " +
            "truncated or failed:\n$output"
    }
    return lines.filter { it.startsWith("SURVEY_TEXT_AMBIGUITY ") }.map { line ->
        val parts = line.removePrefix("SURVEY_TEXT_AMBIGUITY ").split('|')
        check(parts.size == 6) { "Malformed SURVEY_TEXT_AMBIGUITY line: $line" }
        val reading = TextAmbiguity(
            kind = parts[0],
            ownerFqn = parts[1],
            name = parts[2],
            textualOccurrences = parts[3].toInt(),
            resolvedReferences = parts[4].toInt(),
            foreignSameNameCallSites = parts[5].toInt(),
        )
        check(reading.resolvedReferences > 0) {
            "SURVEY_TEXT_AMBIGUITY reports 0 resolved references for ${reading.targetDescription} — " +
                "the index did not see the target, so no reading from this run means anything: $line"
        }
        // A foreign call site spells the name, so it is one of the textual occurrences; more of them
        // than there are occurrences means the two counters counted different things.
        //
        // Deliberately NOT compared against `textualOverReach`: the word index yields leaf
        // identifiers while `ReferencesSearch` yields reference elements, and a qualified reference
        // (`org.keycloak.validate.ValidationContext`) is one reference over one identifier — so the
        // two populations can overlap without either being wrong, and a tighter check here would
        // abort a survey phase over a shape that is expected.
        check(reading.foreignSameNameCallSites <= reading.textualOccurrences) {
            "SURVEY_TEXT_AMBIGUITY is internally inconsistent for ${reading.targetDescription}: " +
                "${reading.foreignSameNameCallSites} foreign call sites cannot fit into " +
                "${reading.textualOccurrences} textual occurrences of the name: $line"
        }
        reading
    }
}

/**
 * A case's text-ambiguity pin, with the measurement's absence spelled out rather than defaulted away.
 *
 * A pin is a number transcribed from a run of `RippleTargetSurveyScripts.textAmbiguity`. Until such a
 * run exists for a target, the case carries [Unmeasured] with the reason — never a plausible number,
 * and never silence, which would read as "measured and fine".
 */
sealed interface TextAmbiguityPin {

    /** Transcribed from a survey run named by [source]. */
    data class Measured(val reading: TextAmbiguity, val source: String) : TextAmbiguityPin {
        init {
            require(source.isNotBlank()) {
                "A measured text-ambiguity pin must name the run it was transcribed from"
            }
        }
    }

    /**
     * No reading exists yet. [reason] must say what is missing and what would produce it, because
     * this value is what a reader of the registry sees instead of a number.
     */
    data class Unmeasured(val reason: String) : TextAmbiguityPin {
        init {
            require(reason.isNotBlank()) { "An unmeasured text-ambiguity pin must state why" }
        }
    }

    /**
     * The metric is about a transformation that changes a NAME. A move-class changes the package and
     * leaves every occurrence of the simple name valid, so there is no textual answer to be wrong.
     */
    data object NotApplicable : TextAmbiguityPin
}

/** The kinds whose transformation changes a name, and which therefore need a reading. */
fun RippleTarget.needsTextAmbiguityPin(): Boolean = this is RenameMethod || this is RenameType

/**
 * The tripwire: reject a candidate whose textual hits are all target references.
 *
 * Applied to [TextAmbiguityPin.Measured] only. An [TextAmbiguityPin.Unmeasured] pin is not a pass —
 * it is an explicit hole, reported by `KeycloakRippleTargetSurveyTest`'s text-ambiguity phase and
 * tracked in `TODO.md`; failing the registry on it would take every rename case out of the family
 * before the measuring run that could fill it has been made.
 */
fun TextAmbiguityPin.requireAdmissible(instanceId: String) {
    if (this !is TextAmbiguityPin.Measured) return
    check(reading.discriminates) {
        "$instanceId is not admissible: ${reading.targetDescription} has " +
            "${reading.textualOccurrences} textual occurrences of '${reading.name}' and " +
            "${reading.resolvedReferences} resolved references, so a textual replacement of every " +
            "occurrence is correct by construction and the case cannot separate a text search from " +
            "a semantic one. Choose a target whose simple name is also spelled by code that does " +
            "NOT reference it (measured with RippleTargetSurveyScripts.textAmbiguity, source: $source)."
    }
}
