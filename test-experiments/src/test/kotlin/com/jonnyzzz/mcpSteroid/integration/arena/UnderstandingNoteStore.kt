/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.io.File

/**
 * The one condition a downstream cell runs under.
 *
 * Three shapes, and the type exists so a build can be queued with ONE property that cannot silently
 * mean something else: `baseline` is the no-note control, `oracle:<name>` is a hand-written note used
 * ONLY for calibration, and a note id is a real research run's output. The pilot's probe builds showed
 * what three independent coordinates cost — every one of them is a chance to measure the wrong cell.
 */
sealed interface UnderstandingCondition {
    /**
     * How the cell appears in the log line and in the aggregate.
     *
     * A fourth shape, [AcquisitionCheckpointNote], joined the three below when the acquisition round
     * needed to address a knowledge state rather than a run. It lives in its own file because it is
     * indexed by a pair — trajectory and checkpoint — that means nothing to the note-bottleneck rounds.
     */
    val label: String

    /** The note text the downstream prompt embeds, or null for the control. */
    fun noteText(case: UnderstandingCase): String?

    data object Baseline : UnderstandingCondition {
        override val label: String = "baseline"
        override fun noteText(case: UnderstandingCase): String? = null
    }

    /**
     * A note written by a research run of one arm, budget and length.
     *
     * The note is read from the committed resource tree rather than fetched from the research build,
     * for the same reason the checkpoint probes read committed patches: the note is data an operator
     * copied in from a build artifact, and reading it where it was committed keeps that copy
     * verifiable with `git diff`.
     */
    data class Research(val noteId: String) : UnderstandingCondition {
        override val label: String = noteId
        override fun noteText(case: UnderstandingCase): String = readUnderstandingNoteFile(case, noteId)
    }

    /**
     * A hand-written, gold-derived note. CALIBRATION ONLY.
     *
     * It answers one question and no other: is the downstream agent capable of this task at all when
     * the understanding is handed to it? A cell run under this condition must never appear in an
     * mcp-versus-shell comparison — an oracle note is written by someone who has seen the solution, so
     * comparing an arm against it measures nothing about acquiring understanding.
     */
    data class Oracle(val name: String) : UnderstandingCondition {
        override val label: String = "oracle-$name"
        override fun noteText(case: UnderstandingCase): String =
            readUnderstandingNoteFile(case, "oracle-$name")
    }
}

/**
 * Parses the single property a downstream build carries into the condition it names.
 *
 * Refuses everything it does not recognise instead of defaulting to the baseline: a typo that silently
 * became "no note" would publish a control cell under a note's label, and the arm it belonged to would
 * be scored with a run that never saw a note.
 */
fun understandingConditionOf(raw: String?): UnderstandingCondition {
    val value = raw?.trim().orEmpty()
    check(value.isNotEmpty()) {
        "no downstream condition given. Pass -D$UNDERSTANDING_CONDITION_PROPERTY=baseline, " +
            "=oracle:<name>, =<noteId> such as mcp-b10-l5000-r1, or " +
            "=${CHECKPOINT_CONDITION_PREFIX}<trajectoryId>@<B>"
    }
    if (value == "baseline") return UnderstandingCondition.Baseline
    if (value.startsWith(CHECKPOINT_CONDITION_PREFIX)) {
        return parseAcquisitionCheckpointNote(value.removePrefix(CHECKPOINT_CONDITION_PREFIX))
    }
    if (value.startsWith("oracle:")) {
        val name = value.removePrefix("oracle:")
        check(name.isNotBlank()) { "an oracle condition must be named: -D$UNDERSTANDING_CONDITION_PROPERTY=oracle:<name>" }
        return UnderstandingCondition.Oracle(name)
    }
    // Throws with the expected shape when it is not a note id, which is the whole point of parsing here.
    parseUnderstandingNoteId(value)
    return UnderstandingCondition.Research(value)
}

/**
 * What a checkpoint-note condition is written with.
 *
 * A prefix rather than a bare `<trajectory>@<B>`, so that the parser can refuse an unknown value
 * instead of guessing: the three older shapes are matched exactly, and anything else has to say which
 * kind of thing it is before it is read.
 */
const val CHECKPOINT_CONDITION_PREFIX: String = "checkpoint:"

/** The property a downstream build sets to name its cell. */
const val UNDERSTANDING_CONDITION_PROPERTY: String = "understanding.condition"

/** The property that names the case, shared by both phases. */
const val UNDERSTANDING_CASE_PROPERTY: String = "understanding.case"

/** The research phase's three coordinates. */
const val UNDERSTANDING_ARM_PROPERTY: String = "understanding.arm"

/** How many environment interactions the research agent may spend. */
const val UNDERSTANDING_BUDGET_PROPERTY: String = "understanding.budget"

/** The note's hard character limit. */
const val UNDERSTANDING_NOTE_LIMIT_PROPERTY: String = "understanding.noteLimit"

/** Which replicate of a cell this build is; part of every cell's identity. */
const val UNDERSTANDING_REPLICATE_PROPERTY: String = "understanding.replicate"

/**
 * Where the committed notes of one case live.
 *
 * A plain relative path for the reason [RippleCheckpointProbeTest]'s checkpoint directory uses one:
 * Gradle runs a test with the module directory as its working directory, and the cell needs the SOURCE
 * tree, not the processed resources.
 */
fun understandingNoteDir(case: UnderstandingCase): File =
    File("src/test/resources/understanding-notes/${case.instanceId}")

/**
 * Reads one committed note, refusing to run rather than sending an empty one.
 *
 * A missing note file is an operator error — the research build's artifact was never copied in — and a
 * downstream cell that ran anyway would publish a baseline result under a note's label.
 */
fun readUnderstandingNoteFile(case: UnderstandingCase, noteId: String): String {
    val file = understandingNoteDir(case).resolve("$noteId.md")
    check(file.isFile) {
        "no note committed at ${file.path}. A downstream cell cannot invent the note it is named after; " +
            "copy it from the research build's artifacts first."
    }
    val text = file.readText().trim()
    check(text.isNotEmpty()) { "the committed note ${file.path} is empty" }
    return text
}

/**
 * Everything wrong with a case's committed note directory, or an empty list when it is usable.
 *
 * Usable includes EMPTY: notes are committed only after research runs have happened, so a directory
 * with nothing but its README is the normal state before that and must not redden a build. What is
 * refused is a note whose file name is not addressable by any cell — a note nobody can queue is a note
 * that will be silently skipped, which is how an arm loses replicates without anyone noticing.
 */
fun understandingNoteProblems(fileNames: List<String>): List<String> = buildList {
    fileNames.filter { it.endsWith(".md") }.forEach { name ->
        val id = name.removeSuffix(".md")
        if (id.startsWith("oracle-") || id == "README") return@forEach
        try {
            parseUnderstandingNoteId(id)
        } catch (e: IllegalStateException) {
            add("$name is not addressable by any cell: ${e.message}")
        }
    }
}
