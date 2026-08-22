/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * One research trajectory of the acquisition-curve experiment: one arm, one replicate, forty
 * interactions, and a curve rather than a note.
 *
 * The cell is one paid run and produces four points. That is the whole reason this family exists as
 * something separate from the understanding-note cells, which spent one paid run per budget and could
 * therefore never tell the shape of an acquisition curve apart from the variance between the runs that
 * drew it. Here the four checkpoints are slices of the same transcript, so the shape is a within-run
 * quantity and every additional purchase buys what replication actually needs: another INDEPENDENT
 * trajectory.
 *
 * A class of its own with a single `@Test`, for the reason the understanding cells document: a build
 * queues exactly one cell, and a class that inherited graded scenarios would spend four more agent runs
 * per build.
 */
class AcquisitionResearchTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun research() {
        val coordinates = understandingResearchCoordinates(
            caseId = System.getProperty(UNDERSTANDING_CASE_PROPERTY),
            arm = System.getProperty(UNDERSTANDING_ARM_PROPERTY),
            budget = System.getProperty(UNDERSTANDING_BUDGET_PROPERTY) ?: ACQUISITION_RESEARCH_BUDGET.toString(),
            noteLimit = System.getProperty(UNDERSTANDING_NOTE_LIMIT_PROPERTY)
                ?: ACQUISITION_NOTE_LIMIT_CHARS.toString(),
            replicate = System.getProperty(UNDERSTANDING_REPLICATE_PROPERTY),
            // The acquisition family runs at ONE budget and slices it. A cell queued at 10 would produce
            // a curve with three of its four points missing and no error, so the grid refuses the value
            // instead of the report having to explain it later.
            allowedBudgets = setOf(ACQUISITION_RESEARCH_BUDGET),
            allowedNoteLimits = setOf(ACQUISITION_NOTE_LIMIT_CHARS),
        )
        val case = AcquisitionCases.byId(coordinates.caseId)
        val checklist = AcquisitionCases.checklistFor(coordinates.caseId)

        var rawTranscript = ""
        val record = runUnderstandingResearch(
            case = case,
            arm = coordinates.arm,
            budget = coordinates.budget,
            noteLimitChars = coordinates.noteLimitChars,
            replicate = coordinates.replicate,
            onRawTranscript = { rawTranscript = it },
        )

        check(rawTranscript.isNotBlank()) {
            "the research run produced no transcript, so no curve can be drawn from it. The note was " +
                "${record.note.text.length} characters, which means the run itself worked and the " +
                "transcript hand-off is what broke"
        }

        val trajectory = parseAcquisitionTrajectory(
            rawNdjson = rawTranscript,
            trajectoryId = record.noteId,
            caseId = case.instanceId,
            // `none` is what the research harness calls the control arm; the curve calls it `shell`,
            // because "none" says what was taken away and the report has to say what was used.
            arm = if (coordinates.arm == "none") "shell" else coordinates.arm,
        )

        println("[ACQUISITION] trajectory ${trajectory.trajectoryId} arm=${trajectory.arm} " +
            "model=${trajectory.model} calls=${trajectory.budgetedCalls} refused=${trajectory.refusedCalls} " +
            "exempt=${trajectory.exemptCalls} tokens=${trajectory.totalOutputTokens} " +
            "accounting=${trajectory.tokenAccounting}")
        val semanticCalls = trajectory.calls.count { it.toolName.contains("steroid") }
        println("[ACQUISITION] tools: ${trajectory.calls.groupingBy { it.toolName }.eachCount()} " +
            "semantic=$semanticCalls")
        println("[ACQUISITION-CURVE] ${AcquisitionPoint.CSV_HEADER}")
        for (point in observedCurve(trajectory, checklist)) {
            println("[ACQUISITION-CURVE] ${point.csvRow()}")
        }

        // Everything the offline distil-and-judge step needs, written by the cell that produced the
        // trajectory. The slicing lives in exactly one place: a script that re-implemented it would be a
        // second definition of "after ten interactions", and the two would drift silently.
        val artifacts = java.io.File(System.getProperty("acquisition.artifacts") ?: "build/acquisition")
            .resolve(trajectory.trajectoryId)
        artifacts.mkdirs()
        artifacts.resolve("transcript.ndjson").writeText(rawTranscript)
        artifacts.resolve("checklist.json").writeText(checklistAsJson(checklist))
        artifacts.resolve("statement.md").writeText(case.problemStatement)
        for (checkpoint in ACQUISITION_CHECKPOINTS) {
            val prompt = buildAcquisitionDistillPrompt(
                problemStatement = case.problemStatement,
                prefixTranscript = renderPrefixTranscript(trajectory.prefix(checkpoint)),
            )
            artifacts.resolve("distill-b$checkpoint.txt").writeText(prompt)
        }
        println("[ACQUISITION] artifacts: ${artifacts.absolutePath}")

        // Two independent counts of the same thing: the in-container hook's, and this reader's. Every
        // checkpoint of the curve is a position in that count, so a drift of one would move all four
        // points without changing how the report looks.
        //
        // Checked only now, AFTER the transcript is on disk. The comment here used to claim the
        // transcript was "already published" while the check sat twenty lines above the code that
        // published it, and the claim was tested the expensive way: a control cell disagreed by three,
        // failed, and took its transcript with it — leaving nothing to diagnose the disagreement WITH.
        // A rejected trajectory is still a paid recording of an agent; only its admission to the curve
        // is in question.
        val drift = trajectory.budgetedCalls - record.budgetedCalls
        if (drift != 0) {
            println(
                "[ACQUISITION] COUNT DRIFT: the transcript reader charged ${trajectory.budgetedCalls} " +
                    "interactions, the in-container hook charged ${record.budgetedCalls}. The curve above " +
                    "is drawn on the reader's numbering."
            )
        }
        check(kotlin.math.abs(drift) <= 2 && trajectory.budgetedCalls > 0) {
            "the transcript reader counted ${trajectory.budgetedCalls} budgeted interactions and the " +
                "in-container hook counted ${record.budgetedCalls}; the two are not describing the same " +
                "run. The transcript is published under ${artifacts.absolutePath} — diff the two counts " +
                "there before re-running"
        }

        // Asserted LAST, so a rejected cell still leaves its transcript and its curve behind: the
        // trajectory is paid for either way and it is evidence about tool adoption even when it is not
        // admissible as evidence about semantic access.
        //
        // A cell of the semantic arm that never called a semantic tool is not a measurement of that arm.
        // Pilot 1 produced two such trajectories out of three (`Bash=25` and `Bash=26, Read=1`) because
        // the CLI keeps MCP schemas behind a `ToolSearch` call and the model simply did not make it; the
        // curves they drew were control-arm curves wearing the mcp label, and averaging them in would
        // have understated the very effect the experiment exists to measure. `enable_tool_search=false`
        // (see [understandingHookSettingsJson]) is the fix; this check is what makes a regression of it
        // impossible to publish by accident.
        check(trajectory.arm != "mcp" || semanticCalls > 0) {
            "ARM DEGENERATE: ${trajectory.trajectoryId} is a semantic-arm cell that made no semantic " +
                "call in ${trajectory.budgetedCalls} interactions " +
                "(${trajectory.calls.groupingBy { it.toolName }.eachCount()}). Its transcript is " +
                "published under ${artifacts.absolutePath}, but it must NOT enter the arm comparison: " +
                "re-run the cell and check that the session settings still disable lazy tool discovery"
        }
    }
}
