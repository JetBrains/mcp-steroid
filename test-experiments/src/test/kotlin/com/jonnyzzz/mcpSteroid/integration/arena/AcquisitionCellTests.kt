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
        check(!armDegenerate(trajectory)) {
            "ARM DEGENERATE: ${trajectory.trajectoryId} is a semantic-arm cell that made no semantic " +
                "call in ${trajectory.budgetedCalls} interactions " +
                "(${trajectory.calls.groupingBy { it.toolName }.eachCount()}). Its transcript is " +
                "published under ${artifacts.absolutePath}, but it must NOT enter the arm comparison: " +
                "re-run the cell and check that the session settings still disable lazy tool discovery"
        }
    }
}

/**
 * One downstream validation cell: the weak agent, a pristine tree, and ONE knowledge state.
 *
 * This family does not ask which arm produced the note. It asks whether `U` — the share of a
 * pre-registered checklist a trajectory prefix had observed — predicts how far a competent-but-weak
 * agent gets from the note that prefix supports. The arm is recorded and analysed afterwards as a
 * secondary reading, and the agent is never told it.
 *
 * The two calibration conditions run through this same cell: `baseline` for the floor and
 * `oracle:gold` for the ceiling. They are what makes a flat `U -> success` curve interpretable — a
 * task nobody can do and a task everybody can do both produce one.
 *
 * The model is defaulted to the weak one here and restored afterwards, exactly as the note-bottleneck
 * cell does it: the Gradle test JVM is shared and a research cell in the same JVM must not inherit a
 * haiku.
 */
class AcquisitionDownstreamTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun downstream() {
        val caseId = System.getProperty(UNDERSTANDING_CASE_PROPERTY)?.trim().orEmpty()
        check(caseId.isNotEmpty()) { "no case given. Pass -D$UNDERSTANDING_CASE_PROPERTY=<instanceId>" }
        val case = AcquisitionCases.byId(caseId)
        // Refused here, before a container exists. The generalization round buys research trajectories
        // on cases that carry no hidden oracle at all, and such a case run through this cell would
        // spend its half hour and report "0 of 0" — a reading that enters an average as a zero and
        // looks like an agent that solved nothing.
        check(case.gradable) {
            "'${case.instanceId}' is a research-only case: it has no hidden oracle, so this cell has " +
                "nothing to grade. Its endpoint is the acquisition curve — queue the research build " +
                "instead, or give the case an oracle first"
        }
        val condition = understandingConditionOf(System.getProperty(UNDERSTANDING_CONDITION_PROPERTY))
        // A note id of the OTHER family would resolve, read a file from the other directory and grade
        // a cell nobody can place on this round's curve. Refused rather than run.
        check(condition !is UnderstandingCondition.Research) {
            "'${condition.label}' is a note of the note-bottleneck rounds. This round's conditions are " +
                "`${CHECKPOINT_CONDITION_PREFIX}<trajectoryId>@<B>`, `baseline` and `oracle:<name>`"
        }
        val replicate = System.getProperty(UNDERSTANDING_REPLICATE_PROPERTY)?.trim()?.toIntOrNull()
        check(replicate != null && replicate >= 1) {
            "the replicate must be a positive number — pass -D$UNDERSTANDING_REPLICATE_PROPERTY"
        }

        // A ladder rung runs no agent at all, so it leaves before the model is chosen: it deploys a
        // deliberately partial tree and reads the oracle. It shares this cell rather than getting one
        // of its own because it shares everything that matters — the container, the reactor install,
        // the grading build — and a second copy of that path would be a second place for the two to
        // drift apart, which is precisely the drift the ladder exists to detect.
        if (condition is AcquisitionLadderCondition) {
            runAcquisitionLadderCell(
                case = case,
                rung = acquisitionLadderRung(case, condition.rungName),
                replicate = replicate,
            )
            return
        }
        // Asked only of the note cells, and only here. `baseline`, `oracle:gold` and the rungs are the
        // cells that PRODUCE the evidence this gate demands, so gating them would make the block
        // impossible to lift; a note cell bought before the evidence exists is the mistake three
        // rounds in a row have paid for.
        if (condition is AcquisitionCheckpointNote) {
            requireAcquisitionAdmission(case)
        }

        val previousModel = System.getProperty(RippleCheckpointProbeTest.CLAUDE_MODEL_PROPERTY)
        if (previousModel == null) {
            System.setProperty(
                RippleCheckpointProbeTest.CLAUDE_MODEL_PROPERTY,
                RippleCheckpointProbeTest.PROBE_MODEL,
            )
        }
        try {
            runUnderstandingDownstream(
                case = case,
                condition = condition,
                replicate = replicate,
                budget = acquisitionDownstreamBudgetOf(System.getProperty(UNDERSTANDING_BUDGET_PROPERTY)),
            )
        } finally {
            if (previousModel == null) {
                System.clearProperty(RippleCheckpointProbeTest.CLAUDE_MODEL_PROPERTY)
            }
        }
    }
}
