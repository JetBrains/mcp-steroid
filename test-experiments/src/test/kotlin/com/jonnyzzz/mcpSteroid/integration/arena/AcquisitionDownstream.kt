/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.io.File

/**
 * One knowledge state of one research trajectory, addressed as a downstream condition.
 *
 * The understanding-note experiment could name a note by the run that produced it, because a run
 * produced exactly one note. Here a single forty-interaction trajectory carries FOUR knowledge states,
 * and the pair that identifies one of them is (trajectory, checkpoint) — the same pair every point of
 * the acquisition curve is indexed by. Reusing the note-id shape for this would have been possible and
 * wrong: `mcp-b10-l2000-r2` reads as "a run whose budget was ten", and the whole design of this round
 * is that no such run exists.
 *
 * The written form is `checkpoint:<trajectoryId>@<B>`.
 */
data class AcquisitionCheckpointNote(val trajectoryId: String, val checkpoint: Int) :
    UnderstandingCondition {

    init {
        check(trajectoryId.isNotBlank()) { "a checkpoint note must name the trajectory it was distilled from" }
        check(checkpoint in ACQUISITION_CHECKPOINTS) {
            "a checkpoint note can only be read at a pre-registered checkpoint $ACQUISITION_CHECKPOINTS, " +
                "got $checkpoint: a note distilled at some other prefix has no U to be plotted against"
        }
    }

    override val label: String = "$trajectoryId@$checkpoint"

    /** The arm the trajectory belonged to. Secondary analysis only — never shown to any agent. */
    val arm: String get() = if (trajectoryId.startsWith("mcp")) "mcp" else "shell"

    override fun noteText(case: UnderstandingCase): String {
        val file = acquisitionNoteFile(case, this)
        check(file.isFile) {
            "no distilled note at ${file.path}. A downstream cell cannot invent the knowledge state it " +
                "is named after; distil it from the committed transcript first (AcquisitionRecomputeTest " +
                "-D${AcquisitionRecomputeTest.RECOMPUTE_OUT_PROPERTY}=… then analysis/distill_and_judge.py)"
        }
        val text = file.readText().trim()
        check(text.isNotEmpty()) { "the committed note ${file.path} is empty" }
        return text
    }
}

/**
 * The twelve knowledge states the downstream wave buys, fixed before any of them was graded.
 *
 * Four trajectories, two per arm, three checkpoints each. The selection rule is written out in
 * `DESIGN-DOWNSTREAM.md` and its second half is the one that matters: the two arms must OVERLAP in
 * `U` at some checkpoints. Without an overlap, "the note was better" and "the note came from the
 * other arm" are the same column and no amount of analysis afterwards can separate them; here they
 * meet at .60 and .73.
 *
 * Held in code so the matrix cannot quietly grow after the first results are in. A thirteenth cell
 * chosen once the shape of the answer is visible is not a measurement.
 */
val ACQUISITION_DOWNSTREAM_MATRIX: List<AcquisitionCheckpointNote> =
    listOf("mcp-b40-l2000-r2", "mcp-b40-l2000-r3", "none-b40-l2000-r1", "none-b40-l2000-r3")
        .flatMap { trajectory ->
            listOf(5, 10, 20).map { AcquisitionCheckpointNote(trajectory, it) }
        }

/**
 * The downstream matrix of every case that has one, addressed by case id.
 *
 * The replication round asks the SAME question as the round that produced [ACQUISITION_DOWNSTREAM_MATRIX]
 * — does a higher `U` leave the weak agent less work? — on the two cases the generalization round added,
 * and it asks it with the same instrument on purpose. What is new is only that the question is now asked
 * where the acquisition advantage was measured to be LARGEST (`oauth-grant-type`, +0.38 at B=10) and
 * where it was middling (`client-auth-method`, +0.18), so a positive answer would connect the two halves
 * of the evidence rather than repeat one of them.
 *
 * The per-case selection rule is the one written in `DESIGN-DOWNSTREAM.md` and repeated in
 * `DESIGN-DOWNSTREAM-3.md`: two trajectories per arm, chosen so the arms OVERLAP in `U`. Without an
 * overlap "the note was better" and "the note came from the other arm" are the same column, and no
 * analysis afterwards can separate them. The chosen pairs, with their measured `U` at 5/10/20:
 *
 * - `client-auth-method`: mcp r1 (.47/.60/.80), mcp r2 (.53/.53/.53), shell r1 (.00/.27/.40),
 *   shell r3 (.27/.47/.47) — range .00–.80, arms meet at .47 and .53.
 * - `oauth-grant-type`: mcp r1 (.53/.73/.73), mcp r2 (.47/.67/.73), shell r1 (.20/.40/.60),
 *   shell r2 (.00/.27/.53) — range .00–.73, arms meet at .53 and .60.
 *
 * `mcp r2` of `client-auth-method` is in the matrix precisely because its curve is FLAT: three notes
 * from three different prefixes that carry the same `U`. If the outcome tracked the research budget
 * rather than the understanding, those three cells would separate, and nothing else in the design would
 * reveal it.
 */
val ACQUISITION_DOWNSTREAM_MATRICES: Map<String, List<AcquisitionCheckpointNote>> = mapOf(
    "acquisition__keycloak__cc-refresh-token" to listOf(
            "mcp-b40-l2000-r1", "mcp-b40-l2000-r2", "mcp-b40-l2000-r3",
            "none-b40-l2000-r1", "none-b40-l2000-r2", "none-b40-l2000-r3",
        ).flatMap { trajectory -> listOf(5, 10, 20).map { AcquisitionCheckpointNote(trajectory, it) } },
    "acquisition__keycloak__client-auth-method" to listOf(
            "mcp-b40-l2000-r1", "mcp-b40-l2000-r2", "mcp-b40-l2000-r3",
            "none-b40-l2000-r1", "none-b40-l2000-r2", "none-b40-l2000-r3",
        ).flatMap { trajectory -> listOf(5, 10, 20).map { AcquisitionCheckpointNote(trajectory, it) } },
    "acquisition__keycloak__oauth-grant-type" to listOf(
            "mcp-b40-l2000-r1", "mcp-b40-l2000-r2", "mcp-b40-l2000-r3",
            "none-b40-l2000-r1", "none-b40-l2000-r2", "none-b40-l2000-r3",
        ).flatMap { trajectory -> listOf(5, 10, 20).map { AcquisitionCheckpointNote(trajectory, it) } },
)

/**
 * Why these matrices grew from four trajectories to six, after a wave had already been read.
 *
 * A matrix held in code so it "cannot quietly grow after the first results are in" did grow, and the
 * reason has to survive being read by someone suspicious.
 *
 * The four-trajectory wave was not inconclusive because its effect was small. It was inconclusive
 * because of ARITHMETIC: the unit of replication is the trajectory, so significance is established by
 * permuting whole trajectories, and four of them admit 4! = 24 arrangements. The smallest p that can
 * exist is 1/24 = 0.042 against a conventional threshold of 0.05 — the design could at best scrape
 * past it, and the observed 2/24 = 0.083 is the second rung of a two-rung ladder. Six trajectories
 * admit 720 arrangements and a floor of 0.0014.
 *
 * What makes this a power correction rather than result-shopping:
 *
 * - it adds EVERY remaining trajectory, not a chosen subset. Each case had six research trajectories
 *   all along, three per arm; the matrix took two per arm because the selection rule asked only for
 *   arms that OVERLAP in `U`, and two per arm satisfied it. Nothing about which two were added depends
 *   on any downstream reading.
 * - the added notes were distilled and committed BEFORE any cell of the four-trajectory wave was
 *   bought, so they cannot have been written to fit it.
 * - the four-trajectory reading stays published exactly as measured. The six-trajectory analysis
 *   replaces nothing, it is reported beside it, and a disagreement between them IS the finding.
 *
 * The failure mode this does NOT protect against, written down so it is not forgotten: with six the
 * temptation becomes choosing WHICH six. There are no more than six here, so the question cannot
 * arise on these cases — and on any future case the whole set is the matrix or the case is not in.
 */
const val ACQUISITION_MATRIX_TRAJECTORIES_PER_ARM: Int = 3

/**
 * The matrix of one case, refusing a case that has none.
 *
 * A cell queued against a case with no pre-registered matrix is not a smaller experiment, it is an
 * unregistered one: the whole point of holding these lists in code is that the thirteenth cell cannot
 * be chosen after the first twelve are in.
 */
fun acquisitionDownstreamMatrixOf(caseId: String): List<AcquisitionCheckpointNote> =
    checkNotNull(ACQUISITION_DOWNSTREAM_MATRICES[caseId]) {
        "'$caseId' has no pre-registered downstream matrix. The cases that do are " +
            "${ACQUISITION_DOWNSTREAM_MATRICES.keys.sorted()}; add one to ACQUISITION_DOWNSTREAM_MATRICES " +
            "with its selection rule BEFORE queueing any cell of it"
    }

/**
 * How many times the harness hands a cell its own compiler errors back and lets it fix them.
 *
 * Three, and the number comes from a measurement rather than a preference. Across the 108 cells of the
 * six-trajectory wave the within-note noise was 2.6-3.1 obligations against a between-note signal of
 * 2.2 — the same note, run twice, returned 7 and 0 in about four cells of ten. Among the pairs where
 * BOTH replicates compiled the within-note noise collapsed to 0.4-1.3. So essentially all of it was one
 * coin flip: did this run get its own code to build inside the allowance.
 *
 * The repair turn removes the flip without giving anything away. The harness runs the build and reads
 * the files javac named; the agent is handed the diagnostics and those file contents, spends no
 * interaction, and issues no command whose text could hide a question about the repository. It cannot
 * learn where anything lives — only what is wrong with what it already wrote.
 *
 * Bounded at three because an unbounded loop measures persistence instead of understanding, and
 * [UnderstandingDownstreamOutcome.repairRounds] publishes how many were actually used, so a cell that
 * compiled first time is never confused with one that needed all three.
 */
const val ACQUISITION_REPAIR_ROUNDS: Int = 3

/**
 * What a repair turn is told. It receives diagnostics and file contents and nothing else.
 *
 * Deliberately narrow in what it asks for: "fix these errors" and not "finish the task". A turn that
 * invited more work would let a cell continue developing after its allowance ran out, which is the one
 * thing the allowance exists to prevent.
 */
fun acquisitionRepairPrompt(compilerOutput: String, files: Map<String, String>): String = buildString {
    appendLine("Your change does not compile. Below are the compiler's errors and the current contents")
    appendLine("of every file it named. Fix ONLY these compilation errors.")
    appendLine()
    appendLine("Do not add features, do not write tests, do not write notes or documentation, and do not")
    appendLine("start anything new. If an error is caused by an API you assumed and it does not exist,")
    appendLine("remove or simplify the code that assumed it rather than inventing another API.")
    appendLine()
    appendLine("You may read and edit the files named below, and only those. You cannot search or build")
    appendLine("— those are exhausted, and every fact you need is below. Reading a file before editing it")
    appendLine("costs you nothing here.")
    appendLine()
    appendLine("## Compiler output")
    appendLine()
    appendLine("```")
    appendLine(compilerOutput.trim())
    appendLine("```")
    files.forEach { (path, content) ->
        appendLine()
        appendLine("## $path")
        appendLine()
        appendLine("```java")
        appendLine(content.trimEnd())
        appendLine("```")
    }
}

/**
 * The repository interactions a downstream cell of this round is allowed.
 *
 * Twenty, and the number is a measurement rather than a preference. The first downstream wave gave the
 * weak agent no allowance at all; its floor anchor spent EIGHTY-NINE interactions and reached seven of
 * eight assertions with no note whatsoever. Against that, nothing a note can say matters — the agent
 * simply performs the research the note was meant to replace, so every condition converges and the
 * whole wave measures the ceiling of the task instead of the value of understanding it.
 *
 * Twenty sits between the two things the case already knows about itself. Its own recorded shell audit
 * reaches four fifths of the checklist in ten well-chosen commands, so twenty cannot make the task
 * impossible for an agent that knows where to look; and it is a quarter of what the unbudgeted floor
 * anchor spent, so it is nowhere near enough to rediscover the chain from nothing. Whether that gap is
 * real is exactly what the calibration wave asks, and the answer is allowed to move this number: the
 * pre-registration fixes the rule (a floor that still reaches the ceiling means tighten, a gold-note
 * ceiling that cannot finish means loosen), not the value.
 *
 * File edits do not count against it — see [UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS].
 */
const val ACQUISITION_DOWNSTREAM_BUDGET: Int = 20

/**
 * The only allowances a downstream cell of this round may run under.
 *
 * Three values, fixed before the first calibration cell, because the budget is the one parameter that
 * can manufacture any result this round could report. A wave that may be re-run at any number until
 * the notes separate is not an experiment; a wave that may be re-run at the two neighbours of a
 * pre-registered default, under a written rule for which direction to move, is a calibration.
 */
val ACQUISITION_DOWNSTREAM_BUDGETS: Set<Int> = setOf(15, ACQUISITION_DOWNSTREAM_BUDGET, 25)

/*
 * 30 was added to this set on 2026-08-27 and removed again on 2026-08-28, and the round between the
 * two dates is why the set is three values.
 *
 * It was added under the calibration rule's own remedy — loosen when the ceiling cannot be finished —
 * because no note the experiment could produce was landing between floor and ceiling. Every case was
 * then re-anchored at its raised allowance, which is what the extension was made conditional on, and
 * the anchors refused it: with no note at all the solver read 4 and 7 of 9 on `cc-refresh-token` and
 * 6 of 9 on `client-auth-method`, against a rule that allows it one obligation above the pristine
 * floor. Extra interactions are handed to both arms, and on these cases they are worth more to the arm
 * that has nothing to read than the note is to the arm that has one.
 *
 * What made the ceiling unreachable was not the allowance. It was the repair turn, which could not
 * edit a file because `Read` was walled and the CLI will not edit an unread file — see
 * UNDERSTANDING_REPAIR_READABLE_FILE. The allowance was being raised to pay for a defect.
 */

/**
 * Reads the allowance a cell was queued with, defaulting to the pre-registered one.
 *
 * Refuses an unlisted value rather than honouring it. The failure this prevents is not a typo: it is a
 * cell queued at 60 during a bad afternoon, landing in the same table as the cells queued at 20 and
 * indistinguishable from them once the build log has scrolled past.
 */
fun acquisitionDownstreamBudgetOf(raw: String?): Int? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return ACQUISITION_DOWNSTREAM_BUDGET
    if (value.equals(ACQUISITION_DOWNSTREAM_BUDGET_NONE, ignoreCase = true)) return null
    val budget = value.toIntOrNull()
    check(budget != null && (budget in ACQUISITION_DOWNSTREAM_BUDGETS || budget in ACQUISITION_FLOOR_PROBE_BUDGETS)) {
        "'$value' is not a pre-registered downstream allowance. The WAVE runs at " +
            "${ACQUISITION_DOWNSTREAM_BUDGETS.sorted()}; the floor probes run at " +
            "${ACQUISITION_FLOOR_PROBE_BUDGETS.sorted()} or '$ACQUISITION_DOWNSTREAM_BUDGET_NONE'. The " +
            "calibration rule in DESIGN-DOWNSTREAM.md says which way to move within the wave's numbers"
    }
    return budget
}

/**
 * The one non-numeric allowance: no wall at all.
 *
 * Not a fourth value of [ACQUISITION_DOWNSTREAM_BUDGETS] and deliberately outside it, because it does
 * not compete with 15, 20 and 25 — those are candidate settings for the WAVE, and this is a probe of a
 * different question: what does the unaided agent achieve when the allowance is not what stops it.
 * Round 1 ran the whole downstream unbudgeted and the two readings it got were 7 of 8 and 0 of 8 on a
 * case since retired, against an oracle since found to be one assertion wearing eight names — so the
 * question has no usable prior answer and has to be measured again.
 *
 * A cell queued this way can never be mistaken for a wave cell: it prints no `budget=` column at all.
 */
const val ACQUISITION_DOWNSTREAM_BUDGET_NONE: String = "none"

/**
 * Allowances a FLOOR PROBE may run at, and which no wave cell may run at.
 *
 * Kept apart from [ACQUISITION_DOWNSTREAM_BUDGETS] on purpose. That set is closed because the wave's
 * allowance is the one parameter that can manufacture any result the round could report, and widening
 * it would reopen exactly that door. A floor probe asks a different question — what does the UNAIDED
 * solver achieve — so its allowance is not a candidate setting for anything, and a cell run at one can
 * never be mistaken for a wave cell.
 *
 * **Sixty**, and it is derived rather than chosen. A note cell is handed, for free, the research a
 * stronger agent (`claude-opus-5`) already paid for; a no-note cell at the wave's twenty has to do
 * that research itself out of the same twenty. Comparing them measures the head start, not the note.
 * Sixty removes the head start by giving the unaided solver the whole bill:
 *
 * - the research agent was allowed [ACQUISITION_RESEARCH_BUDGET] = 40 interactions, and the shell arm
 *   spent all of them on `oauth-grant-type` (40, 40, 40) and most of them on `client-auth-method`
 *   (40, 25, 29) — `docs/acquisition-curve-experiment/data/generalization-curves.csv`, checkpoint 40;
 * - plus the [ACQUISITION_DOWNSTREAM_BUDGET] = 20 the solver gets.
 *
 * It is also the conservative end of what the unbudgeted probe measured the same solver actually
 * spending unaided — 55 to 96 charged interactions, mean 77 (round 4 step 4). So a no-note cell at
 * sixty is given less than it takes when nothing stops it, and more than the note's whole bill on the
 * arm that researched most expensively. A floor that still falls short at sixty is short for reasons
 * other than the wall.
 *
 * The semantic arm's research cost is far lower — roughly seventeen interactions on both cases — so
 * sixty is a GENEROUS control for a semantic note and an exact one for a shell note. That asymmetry is
 * the acquisition result rather than an accident, and it is left visible instead of averaged away.
 *
 * **Forty** is the same derivation with one term dropped: the research bill alone, without the
 * solver's own allowance on top. It exists because a floor read at one allowance is a point and the
 * question round 8 asks is a shape — at which allowance does the unaided solver start doing the work
 * the note was supposed to buy. Two derived rungs answer that; one cannot. It is deliberately not a
 * round number chosen for feeling generous, which is the thing [ACQUISITION_DOWNSTREAM_BUDGETS] is
 * closed against, and 45 is still refused.
 */
val ACQUISITION_FLOOR_PROBE_BUDGETS: Set<Int> = setOf(ACQUISITION_RESEARCH_BUDGET, 60)

/**
 * Solvers a FLOOR PROBE may run on, besides the weak model every other cell runs on.
 *
 * The allowance is one of the two things that could be starving the solver; the solver itself is the
 * other, and round 8 probes both because eliminating one without the other leaves the reading
 * ambiguous in exactly the way round 7 ended. The set is closed for the same reason
 * [ACQUISITION_FLOOR_PROBE_BUDGETS] is closed, and it is closed one rung up: `claude-sonnet-5` is the
 * next model above the wave's `claude-haiku-4-5`, not the top of the ladder.
 *
 * An Opus stays refused, and not on price. A cell that clears the endpoint on the strongest model
 * available answers a question nobody asked — the notes were distilled to be read by an agent that
 * cannot do the research itself, and a solver that can no longer needs one. The probe has to stay
 * inside the range where a note could still matter, or its result cannot be carried back to the wave.
 *
 * Every cell prints its resolved model before the agent starts, so a probe cell names its solver in
 * the log the same way it names its allowance.
 */
val ACQUISITION_FLOOR_PROBE_SOLVERS: Set<String> = setOf("claude-sonnet-5")

/**
 * True for a semantic-arm trajectory that never made a semantic call.
 *
 * Such a cell is not a measurement of the arm it is labelled with: the first pilot produced two of
 * them out of three because the CLI keeps MCP schemas behind a discovery call the model never made,
 * and their curves were control-arm curves under the treatment label. The cell that produces a
 * trajectory refuses to publish one, and the offline re-reader refuses to distil a note from one —
 * one predicate, so the two cannot disagree about which trajectories exist.
 */
fun armDegenerate(trajectory: AcquisitionTrajectory): Boolean =
    trajectory.arm == "mcp" && trajectory.calls.none { it.toolName.contains("steroid") }

/**
 * Where the distilled checkpoint notes of one case live.
 *
 * Deliberately NOT the understanding experiment's note directory. Those notes were written by the
 * research agent under a length limit it could feel; these are read-outs of a prefix, written by a
 * distiller that never saw the repository. Mixing them in one directory would make it possible to
 * queue one where the other is meant, and the two answer different questions.
 */
fun acquisitionNoteDir(case: UnderstandingCase): File =
    File("src/test/resources/acquisition-notes/${case.instanceId}")

/** The file one checkpoint note is committed as. */
fun acquisitionNoteFile(case: UnderstandingCase, note: AcquisitionCheckpointNote): File =
    acquisitionNoteDir(case).resolve("${note.trajectoryId}-at${note.checkpoint}.md")

/**
 * Parses `<trajectoryId>@<B>`, refusing anything else.
 *
 * Loud on a malformed value rather than lenient, for the reason the note-id parser gives: a downstream
 * cell that silently ran a different condition than the one it is labelled with corrupts the table it
 * lands in, and nothing later can detect it.
 */
fun parseAcquisitionCheckpointNote(raw: String): AcquisitionCheckpointNote {
    val at = raw.lastIndexOf('@')
    check(at > 0 && at < raw.length - 1) {
        "a checkpoint condition is `checkpoint:<trajectoryId>@<B>`, e.g. " +
            "`checkpoint:mcp-b40-l2000-r2@10`; got '$raw'"
    }
    val checkpoint = raw.substring(at + 1).trim().toIntOrNull()
    check(checkpoint != null) { "'${raw.substring(at + 1)}' is not a checkpoint number, in '$raw'" }
    return AcquisitionCheckpointNote(trajectoryId = raw.substring(0, at).trim(), checkpoint = checkpoint)
}

/**
 * What one downstream cell established, beyond the binary verdict.
 *
 * The count is the point. This round's question is whether a higher `U` buys a functionally better
 * starting position, and a pass/fail throws away most of the answer: a note that carries the agent to
 * eight assertions of nine and a note that leaves it unable to compile are both `Y=0`. The oracle is
 * one class of nine INDEPENDENT assertions, one per obligation of the change, so the number that still
 * fail is a usable reading of the work the note did not do.
 *
 * Independence is the part that had to be built rather than assumed. The first version of this oracle
 * discovered the implementation through the profile JSON, so every assertion failed until that one
 * line existed and the scale was `{0} u {5..8}`; the first downstream wave was graded on it and could
 * not have detected anything. The current oracle finds the implementation by scanning the module's
 * compiled classes, and its measured scale over five trees is 1, 7, 8, 8, 9.
 *
 * [oracleTestsPassed] is counted against [oracleTestsTotal] taken from the case, never against what
 * the run happened to execute: a cell that ran fewer classes than the case declares would otherwise
 * report "0 of 0" and quietly drop the worst outcomes out of every average.
 *
 * It is NULL when the tree did not compile, which is a different statement from zero. The third
 * downstream round published twelve cells at zero of ten, every one of them a compile failure and not
 * one of them a failed assertion; read as zeros they said "the note taught nothing", when what they
 * said was "nothing could be read". [compiled] carries that fact as its own diagnostic, exactly as the
 * assertions carry theirs.
 */
data class UnderstandingDownstreamOutcome(
    val success: Boolean?,
    val verdict: String,
    val oracleTestsPassed: Int?,
    val oracleTestsTotal: Int,
    /**
     * Whether the graded tree compiled — a diagnostic beside the obligations, never folded into them.
     *
     * Null when the cell carries no build evidence at all (an ungraded cell, or a hand-built outcome
     * in a test). A false here is the one value that makes [oracleTestsPassed] unmeasurable, and a
     * reader of the aggregate must decide what to do with such a cell explicitly rather than have the
     * decision made for it by an average.
     */
    val compiled: Boolean? = null,
    val cost: UnderstandingCellCost,
    /**
     * How many times the downstream agent reached for a tool.
     *
     * The second half of "functionally useful": a note that saves the reader nothing but still gets
     * there, and a note that gets there in a third of the work, are different findings. Nullable
     * because a stream that ended without any `tool_use` block really has an unknown count, and a zero
     * there would read as an agent that solved the task by thinking about it.
     */
    val toolCalls: Int? = null,
    /** The allowance this cell ran under, or null for the unbudgeted shape of the first wave. */
    val budget: Int? = null,
    /** Budgeted interactions the agent actually spent, as the in-container hook counted them. */
    val budgetUsed: Int? = null,
    /**
     * Calls refused after the wall.
     *
     * Not an error count and not a failure of the cell: it measures how much MORE the agent still
     * wanted to look at the repository when its allowance ran out, which is the clearest single
     * reading of what the note failed to supply. A wave in which every cell ends with dozens of
     * denials is a wave whose budget was set too small to distinguish notes.
     */
    val budgetDenied: Int? = null,
    /**
     * Test sources the agent ADDED, discarded before grading so the cell is graded on its change.
     *
     * Recorded rather than merely done. Four of the five gold-note failures of the fourth round's
     * anchors were an agent's own scratch unit test failing `testCompile` after its implementation
     * had compiled clean, and a repair that silently deletes files is a repair nobody can audit —
     * a cell reporting `agentTestsDiscarded=3` says what was taken out of its reading.
     *
     * Null for a cell that ran before amendment 3, which is not the same as a cell that had none to
     * discard; zero says the tree really carried no invented test.
     */
    val agentTestsDiscarded: Int? = null,
    /**
     * Files the agent added OUTSIDE every source root — prose about the change instead of the change.
     *
     * Reads are charged and edits are free, so an agent that hits the interaction wall can no longer
     * investigate but can still write. One cell answered that with twenty-eight files — scripts meant
     * to apply its own edits and fifteen documents describing what remained to be done — and scored
     * zero, correctly, with nothing in its published row explaining why.
     *
     * Recorded, never forbidden: free edits exist so a note is not priced in keystrokes. What was
     * missing is a column that puts the behaviour in the table rather than in a transcript.
     */
    val agentNonSourceFiles: Int? = null,
    /**
     * Repair turns the harness spent handing this cell its own compiler errors, or null for a cell that
     * ran before the loop existed.
     *
     * Zero means it compiled on its own. A reader comparing cells has to be able to tell that apart
     * from a cell that needed three attempts, or the loop would hide exactly the variance it removes.
     */
    val repairRounds: Int? = null,
) {
    /**
     * Assertions the note's reader never satisfied — the residual work, in the oracle's own units.
     *
     * Null exactly when [oracleTestsPassed] is: residual work on a tree that never built is unknown,
     * and reporting it as "all of them" would be the same collapse wearing the opposite sign.
     */
    val residualTests: Int? get() = oracleTestsPassed?.let { oracleTestsTotal - it }

    /**
     * True when the agent both solved the task AND never hit the wall.
     *
     * The two halves are one outcome on purpose. A cell that passed every assertion having exhausted
     * its allowance and pushed against it twenty times did not demonstrate that the note carried it
     * there; it demonstrated that the wall arrived after the work was done. Null whenever either half
     * is unknown — an ungraded cell and an unbudgeted one are both unanswerable here, and a `false`
     * would quietly enter an average as evidence of failure.
     */
    val successWithinBudget: Boolean?
        get() = when {
            success == null || budgetDenied == null -> null
            else -> success && budgetDenied == 0
        }
}

/**
 * The one line the downstream aggregate of this round reads per cell.
 *
 * Carries the pair that identifies the knowledge state and the residual count, so the table of
 * `U -> downstream` can be rebuilt from build logs alone. `U` itself is deliberately NOT printed here:
 * it is a property of the trajectory prefix, recomputed offline from the committed transcript, and a
 * copy of it travelling through a build parameter is a copy that can disagree.
 */
fun acquisitionDownstreamLine(
    caseId: String,
    condition: UnderstandingCondition,
    replicate: Int,
    outcome: UnderstandingDownstreamOutcome,
): String = buildString {
    val note = condition as? AcquisitionCheckpointNote
    append("[ACQUISITION-DOWN] case=$caseId condition=${condition.label} ")
    append("trajectory=${note?.trajectoryId ?: "-"} checkpoint=${note?.checkpoint ?: "-"} ")
    append("arm=${note?.arm ?: "-"} replicate=$replicate ${outcome.verdict} ")
    append("oraclePassed=${outcome.oracleTestsPassed ?: "unmeasured"}/${outcome.oracleTestsTotal} ")
    append("residual=${outcome.residualTests ?: "unmeasured"}")
    outcome.compiled?.let { append(" compiled=${if (it) 1 else 0}") }
    outcome.agentTestsDiscarded?.let { append(" agentTestsDiscarded=$it") }
    outcome.agentNonSourceFiles?.let { append(" agentNonSourceFiles=$it") }
    outcome.repairRounds?.let { append(" repairRounds=$it") }
    outcome.toolCalls?.let { append(" toolCalls=$it") }
    outcome.budget?.let { append(" budget=${outcome.budgetUsed ?: "?"}/$it") }
    outcome.budgetDenied?.let { append(" denied=$it") }
    outcome.successWithinBudget?.let { append(" withinBudget=${if (it) 1 else 0}") }
    outcome.cost.usd?.let { append(" usd=%.4f".format(java.util.Locale.ROOT, it)) }
    outcome.cost.agentSeconds?.let { append(" agentSeconds=$it") }
    outcome.cost.outputTokens?.let { append(" outputTokens=$it") }
}
