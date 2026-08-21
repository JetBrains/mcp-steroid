/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The markers a research agent wraps its hand-off note in.
 *
 * The note is read out of the agent's FINAL MESSAGE and not out of a file it writes, for the one
 * reason the whole experiment turns on: the research phase must leave the work tree byte-identical,
 * and a note written into the repository would be an edit. Writing it outside the repository would
 * work too, but it would spend one of the interactions the budget is measuring — the note would then
 * cost a different fraction of the budget in the two arms.
 *
 * Explicit markers rather than "whatever the agent said last": the final message also carries the
 * model's own framing ("Here is my note for the next developer:"), and that framing is not part of
 * what the downstream agent should be charged for reading. A run whose message has no markers is
 * still usable — [extractUnderstandingNote] falls back to the whole message and says so — because
 * losing a paid Opus run over a formatting slip would be the more expensive failure.
 */
const val UNDERSTANDING_NOTE_OPEN_MARKER: String = "<NOTE>"

/** Closing counterpart of [UNDERSTANDING_NOTE_OPEN_MARKER]. */
const val UNDERSTANDING_NOTE_CLOSE_MARKER: String = "</NOTE>"

/**
 * One extracted note plus everything about HOW it was extracted, because every one of those facts can
 * bias the comparison it feeds.
 *
 * [text] is what a downstream cell really receives: already truncated to the cell's limit, so nothing
 * downstream can accidentally send more than the condition it claims to be.
 */
data class UnderstandingNote(
    /** The note as the downstream agent will receive it — never longer than [limitChars]. */
    val text: String,
    /** The character limit this note was produced under. */
    val limitChars: Int,
    /** How long the note was BEFORE truncation; equals `text.length` when nothing was cut. */
    val originalChars: Int,
    /** True when the agent exceeded [limitChars] and the harness cut the note. */
    val truncated: Boolean,
    /** True when the agent did not use the markers and the whole final message was taken. */
    val markersMissing: Boolean,
    /**
     * The share of note lines that look like verbatim tool output — a pasted diff, a grep listing, a
     * file dump — as a percentage, rounded down.
     *
     * Reported, never enforced. The hard character limit already makes a dump self-defeating (a note
     * that spends 5 000 characters on grep output has no room left for a model of the repository), and
     * a rule that REJECTED such a note would silently delete the very runs where an arm failed to
     * distil anything, which is exactly the outcome the experiment must be able to observe.
     */
    val verbatimLinePercent: Int,
) {
    fun describe(): String =
        "chars=${text.length}/$limitChars original=$originalChars truncated=$truncated " +
            "markers=${if (markersMissing) "MISSING" else "present"} verbatimLines=$verbatimLinePercent%"
}

/**
 * Pull the note out of a research run's final message and hold it to [limitChars].
 *
 * Truncation is a hard cut at the character limit, not a refusal: the limit is one of the experiment's
 * two note-length conditions, and the downstream cell must receive exactly the number of characters
 * its condition names. A cut mid-sentence costs the arm that overran, which is the correct incentive —
 * both arms are told the same limit in the same words.
 *
 * @throws IllegalStateException when the run produced no final message at all; that is an instrument
 *   failure (a crashed CLI, a lost transcript), not a short note, and must never reach a downstream
 *   cell as an empty note that would silently read as "the note did not help".
 */
fun extractUnderstandingNote(finalMessage: String?, limitChars: Int): UnderstandingNote {
    require(limitChars > 0) { "a note limit must be positive, got $limitChars" }
    val message = finalMessage?.trim()
    check(!message.isNullOrBlank()) {
        "the research run produced no final message, so there is no note to hand downstream — this is " +
            "an instrument failure (the agent CLI ended without a result event), not an empty note"
    }

    val open = message.indexOf(UNDERSTANDING_NOTE_OPEN_MARKER)
    val close = message.lastIndexOf(UNDERSTANDING_NOTE_CLOSE_MARKER)
    val markersMissing = open < 0 || close < open
    val body = if (markersMissing) {
        message
    } else {
        message.substring(open + UNDERSTANDING_NOTE_OPEN_MARKER.length, close)
    }.trim()

    check(body.isNotBlank()) {
        "the research run emitted the note markers with nothing between them"
    }

    return UnderstandingNote(
        text = body.take(limitChars),
        limitChars = limitChars,
        originalChars = body.length,
        truncated = body.length > limitChars,
        markersMissing = markersMissing,
        verbatimLinePercent = verbatimLinePercent(body),
    )
}

/**
 * How much of [body] reads as pasted tool output rather than as prose the agent wrote.
 *
 * The three shapes that matter are the ones a note can be padded with for free: a unified diff, a
 * `path:line:` grep hit, and a block of source lines. Prose about a file ("`OwnerController` renders
 * the form") does NOT match, because the marker is a line that BEGINS like machine output.
 */
fun verbatimLinePercent(body: String): Int {
    val lines = body.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return 0
    val verbatim = lines.count { line ->
        val trimmed = line.trim()
        VERBATIM_LINE_SHAPES.any { it.containsMatchIn(trimmed) }
    }
    return verbatim * 100 / lines.size
}

/**
 * Line shapes that only appear when a tool's own output was pasted.
 *
 * Anchored at the START of a line on purpose: `see Foo.java:120` inside a sentence is a useful
 * pointer written by the agent, while a line that IS `Foo.java:120: public void x()` is a grep hit.
 */
private val VERBATIM_LINE_SHAPES: List<Regex> = listOf(
    Regex("""^diff --git """),
    Regex("""^[+-]{3} [ab]/"""),
    Regex("""^@@ -\d+"""),
    Regex("""^\S+\.\w+:\d+:"""),
    Regex("""^\d+[:\t]\s*\S"""),
    Regex("""^(public|private|protected|package|import|class|interface|@Override)\b"""),
)

/**
 * The note and its provenance as one artifact, so a downstream cell can be traced back to the exact
 * research run that produced it without joining two build logs by hand.
 *
 * Every field is a coordinate of the experiment's matrix or a denominator of its two efficiency axes
 * (see `docs/understanding-note-experiment/DESIGN.md`): the arm and the budget say which cell produced
 * the note, `researchOutputTokens` and `researchToolCalls` are the two denominators that must never be
 * mixed, and `pristine` says whether the note is admissible at all.
 */
data class UnderstandingNoteRecord(
    val noteId: String,
    val case: String,
    val arm: String,
    val budget: Int,
    val limitChars: Int,
    val replicate: Int,
    val model: String,
    val note: UnderstandingNote,
    /** Interactions the budget hook actually charged, which is at most `budget`. */
    val budgetedCalls: Int,
    /** Calls the hook refused after the budget was spent — how hard the agent pushed against the wall. */
    val deniedCalls: Int,
    /** Every tool call the recorder saw, including the ones the budget exempts. */
    val rawToolCalls: Int,
    /** Cumulative model output tokens of the research run — the SECOND denominator, never mixed with calls. */
    val researchOutputTokens: Long?,
    val researchCostUsd: Double?,
    val researchSeconds: Long,
    /** Whether the work tree was byte-identical after the research run; a false invalidates the note. */
    val pristine: Boolean,
    /** What changed when [pristine] is false — empty otherwise. */
    val pristineViolations: List<String>,
) {
    fun toJson(): String = understandingJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("noteId", noteId)
        put("case", case)
        put("arm", arm)
        put("budget", budget)
        put("limitChars", limitChars)
        put("replicate", replicate)
        put("model", model)
        put("noteChars", note.text.length)
        put("noteOriginalChars", note.originalChars)
        put("noteTruncated", note.truncated)
        put("noteMarkersMissing", note.markersMissing)
        put("noteVerbatimLinePercent", note.verbatimLinePercent)
        put("budgetedCalls", budgetedCalls)
        put("deniedCalls", deniedCalls)
        put("rawToolCalls", rawToolCalls)
        put("researchOutputTokens", researchOutputTokens)
        put("researchCostUsd", researchCostUsd)
        put("researchSeconds", researchSeconds)
        put("pristine", pristine)
        put("pristineViolations", pristineViolations.joinToString("; "))
    })

    /**
     * The one line a build log must carry for this note, in the shape the aggregator greps for.
     *
     * Both denominators are on it, next to each other and labelled, because the whole point of the
     * two-axis reporting is that a reader can never quote one of them as if it were the other.
     */
    fun logLine(): String =
        "[UNDERSTANDING-NOTE] id=$noteId case=$case arm=$arm budget=$budget limit=$limitChars " +
            "replicate=$replicate calls=$budgetedCalls denied=$deniedCalls rawCalls=$rawToolCalls " +
            "outputTokens=${researchOutputTokens ?: "unknown"} usd=${researchCostUsd ?: "unknown"} " +
            "seconds=$researchSeconds noteChars=${note.text.length} truncated=${note.truncated} " +
            "pristine=$pristine"
}

/**
 * The identity of one research cell, used as the note's file name and as the downstream cell's only
 * coordinate.
 *
 * A single string, because a downstream build must be addressable by ONE property: the pilot's probe
 * builds already showed that three separate coordinates are three chances to queue the wrong cell.
 */
fun understandingNoteId(arm: String, budget: Int, limitChars: Int, replicate: Int): String =
    "$arm-b$budget-l$limitChars-r$replicate"

/** Parses back what [understandingNoteId] wrote, so a downstream cell can print the cell it inherited. */
fun parseUnderstandingNoteId(noteId: String): UnderstandingNoteCoordinates {
    val match = NOTE_ID_SHAPE.matchEntire(noteId)
        ?: error(
            "'$noteId' is not a note id. Expected <arm>-b<budget>-l<limit>-r<replicate>, " +
                "e.g. mcp-b10-l5000-r1"
        )
    val (arm, budget, limit, replicate) = match.destructured
    return UnderstandingNoteCoordinates(arm, budget.toInt(), limit.toInt(), replicate.toInt())
}

private val NOTE_ID_SHAPE = Regex("""^(mcp|none)-b(\d+)-l(\d+)-r(\d+)$""")

data class UnderstandingNoteCoordinates(
    val arm: String,
    val budget: Int,
    val limitChars: Int,
    val replicate: Int,
)

/** Reads back the metadata [UnderstandingNoteRecord.toJson] wrote, for the downstream cell's log line. */
fun readUnderstandingNoteMetadata(json: String): UnderstandingNoteCoordinates {
    val obj = Json.parseToJsonElement(json).jsonObject
    return UnderstandingNoteCoordinates(
        arm = obj["arm"]?.jsonPrimitive?.contentOrNull ?: error("note metadata has no arm"),
        budget = obj["budget"]?.jsonPrimitive?.int ?: error("note metadata has no budget"),
        limitChars = obj["limitChars"]?.jsonPrimitive?.int ?: error("note metadata has no limitChars"),
        replicate = obj["replicate"]?.jsonPrimitive?.int ?: error("note metadata has no replicate"),
    )
}

private val understandingJson = Json { prettyPrint = true }
