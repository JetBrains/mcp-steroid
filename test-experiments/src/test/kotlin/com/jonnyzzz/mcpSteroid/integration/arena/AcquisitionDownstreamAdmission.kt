/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * What a case must have SHOWN about itself before anyone buys a downstream note wave on it.
 *
 * Three rounds bought note cells first and calibrated afterwards, and all three lost the wave rather
 * than the hypothesis:
 *
 * - round 1 ran the solver unbudgeted: the no-note anchor reached seven assertions of eight in
 *   eighty-nine interactions, so there was no room left for a note to matter and every condition
 *   converged;
 * - round 2 ran against a CASCADING oracle whose eight assertions all discovered the change through
 *   one line of a JSON file, so the scale was `{0} u {5..8}` — one boolean wearing eight names;
 * - round 3 ran against a grading build scoped to one module, so every solver that followed the
 *   repository's own precedent into an upstream module failed `javac`, and twelve cells published
 *   "zero of ten obligations" without a single failed assertion.
 *
 * Each of those is a property of the INSTRUMENT that was knowable before the wave and was not known,
 * because nothing forced it to be. This type is that forcing function: a note cell asks
 * [requireAcquisitionAdmission] before it starts a container, and the answer is derived from measured
 * readings rather than from an author's confidence.
 *
 * What it demands, and why each item exists:
 *
 * 1. **A measured ceiling.** The gold patch, replayed through the same grading build the solver is
 *    graded by, must satisfy EVERY obligation. A ceiling nobody replayed is the assumption round 3
 *    died on — its gold sidestepped the module boundary with a local literal, so the ceiling compiled
 *    while nothing that imitated the precedent did.
 * 2. **A ladder.** At least two deliberately partial trees, each measured, each landing on a DIFFERENT
 *    intermediate count strictly between the floor and the ceiling. Two of them separating is the only
 *    evidence that the oracle has a scale at all rather than a pass/fail in N costumes.
 * 3. **At least three weak-agent rollouts with the gold note**, all of which compiled and all of which
 *    scored high. Three, not two, because `client-auth-method` read 9 of 9 on its first ceiling run and
 *    then 0, 0, 0 on the next three: two replicates cannot tell a reachable task from a lucky one.
 * 4. **At least two no-note baselines**, all low, and separated from the gold-note rollouts by at least
 *    half the scale. Without a floor a flat result is uninterpretable — a task nobody can do and a task
 *    everybody can do both produce one.
 * 5. **A grading build that covers the dependency closure** the solver can reasonably reach, i.e.
 *    [UnderstandingCase.gradingBuildsDependencyClosure].
 * 6. **Compilation as its own reading**, which is a property of the harness rather than of the case and
 *    is therefore enforced here only through the demand that every recorded rollout SAY whether it
 *    compiled. A rollout recorded before that axis existed is not evidence for this protocol; see
 *    [AcquisitionRolloutEvidence.compiled].
 */
data class AcquisitionCaseAdmission(
    val caseId: String,
    /**
     * What the oracle scores on a PRISTINE tree.
     *
     * Rarely zero, and never assumed. Both Keycloak oracles carry a "did not break anything" axis that
     * is true of a tree that changed nothing, so their floor is one — and a reader who assumes zero
     * reads 1/9 as partial progress by an agent that did nothing at all.
     */
    val pristineFloor: Int,
    /**
     * The solver allowance this case's anchors were measured at, and the one its wave must run at.
     *
     * Per case, because the pre-registered calibration rule is per case: "a floor that still reaches
     * the ceiling means tighten, a ceiling that cannot be finished means loosen", applied to each
     * case's own floor and ceiling. Applied honestly it produced three different answers, and a single
     * number would have been a choice rather than the rule's output.
     *
     * The consequence is recorded here so nobody has to rediscover it: cells of DIFFERENT cases are no
     * longer measured under the same constraint, so the `U` to outcome relation is read WITHIN a case.
     * The wave is within-case by construction, so this costs the primary analysis nothing.
     */
    val solverAllowance: Int,
    /** The rungs between floor and ceiling, plus the ceiling itself as the last entry. */
    val rungs: List<AcquisitionPartialRung>,
    /** Weak-agent cells run with the hand-written gold note — the reachability evidence. */
    val goldNoteRollouts: List<AcquisitionRolloutEvidence>,
    /** Weak-agent cells run with no note at all — the floor evidence. */
    val baselineRollouts: List<AcquisitionRolloutEvidence>,
    /**
     * Why this case left the downstream family for good, or null while it is merely blocked.
     *
     * The difference matters and the type carries it rather than a document: every other entry in
     * [problems] names a cell somebody can queue, so a reader who works the list eventually empties
     * it. A retirement never empties, because the stopping rules of the design are about properties
     * of the CASE — the gold note cannot be built in the allowance, or the no-note solver already
     * scores what the note would buy — and no number of cells changes those.
     *
     * The acquisition curve of a retired case stays valid. `U(B)` is a property of the trajectory and
     * of the checklist, and it never depended on the oracle.
     */
    val retiredFromDownstream: String? = null,
) {
    /**
     * Everything that still stands between this case and a note wave, or an empty list when it is
     * ready.
     *
     * A LIST rather than a boolean, and phrased as work items, because the point is not to forbid the
     * wave but to say what it is waiting for. Every item here names a cell that can be queued.
     */
    fun problems(case: UnderstandingCase): List<String> = buildList {
        require(case.instanceId == caseId) {
            "admission record '$caseId' checked against case '${case.instanceId}'"
        }
        retiredFromDownstream?.let {
            add("the case has LEFT the downstream family and no cell can readmit it: $it")
        }
        if (!case.gradable) {
            add("the case has no hidden oracle, so nothing downstream can be graded on it")
            return@buildList
        }
        if (!case.gradingBuildsDependencyClosure) {
            add(
                "the grading build is scoped to ${case.gradingScopeSelector} without its dependency " +
                    "closure: a solver that follows the repository's own precedent into an upstream " +
                    "module would be graded on a `cannot find symbol` it did not cause. Set " +
                    "gradingBuildsDependencyClosure = true"
            )
        }
        if (case.goldPatchResource == null) {
            add("the case declares no gold patch, so its ceiling cannot be replayed")
        }

        val ceiling = rungs.lastOrNull()
        if (ceiling == null || ceiling.goldPaths != null && !ceiling.isWholeGold) {
            add("the last rung must be the whole gold patch — that is what a ceiling means")
        }
        val ceilingReading = ceiling?.measuredObligations
        when {
            ceilingReading == null ->
                add(
                    "the ceiling has never been replayed. Queue the ladder cell " +
                        "`${LADDER_CONDITION_PREFIX}${ceiling?.name ?: "gold"}` and record what it scores"
                )
            ceilingReading != case.oracleTestCount ->
                add(
                    "the gold tree scores $ceilingReading of ${case.oracleTestCount} obligations. A gold " +
                        "that cannot reach its own ceiling means the oracle grades something the " +
                        "reference solution does not do"
                )
        }

        val intermediate = rungs.dropLast(1)
        val measured = intermediate.mapNotNull { it.measuredObligations }
        for (rung in intermediate) {
            if (rung.measuredObligations == null) {
                add(
                    "rung `${rung.name}` is declared (expected ${rung.expectedObligations}) but never " +
                        "measured. Queue `${LADDER_CONDITION_PREFIX}${rung.name}`"
                )
            } else if (rung.measuredObligations != rung.expectedObligations) {
                add(
                    "rung `${rung.name}` was predicted at ${rung.expectedObligations} obligations and " +
                        "measured at ${rung.measuredObligations}. One of the two is wrong about the " +
                        "case, and publishing before knowing which is how a scale becomes a story"
                )
            }
            if (rung.patchResource == null && rung.goldPaths.isNullOrEmpty()) {
                add("rung `${rung.name}` names neither a subset of the gold patch nor a patch resource")
            }
            if (rung.patchResource != null && !rung.patchResourceExists) {
                add(
                    "rung `${rung.name}` names the patch resource ${rung.patchResource}, which is not on " +
                        "the test classpath. The invariant rungs are the ones that cannot be cut out of " +
                        "the gold — the whole change with one thing done the neighbour's way — so they " +
                        "have to be written and exported before the rung can be measured"
                )
            }
        }
        if (intermediate.size < MIN_INTERMEDIATE_RUNGS) {
            add(
                "${intermediate.size} intermediate rung(s) declared, $MIN_INTERMEDIATE_RUNGS needed. " +
                    "Fewer than two cannot show the oracle has a scale rather than a verdict"
            )
        }
        // Amendment 1, written 2026-08-24 after the first ladder came back and BEFORE any note cell of
        // the fourth round: identity is the SET of unmet obligations, not their number.
        //
        // The rule as first written compared counts, and `cc-refresh-token` immediately produced
        // [7, 8, 8] — its registration rung and its invariant rung cost one obligation each, which is
        // the same number and a different obligation. Read as counts that is a coarse scale; read as
        // sets it is the exact opposite, two independent axes priced alike. What the rule is for is
        // catching N assertions that are one assertion, and two rungs failing DIFFERENT test methods
        // is a stronger refutation of that than two different totals, which a cascade can also produce.
        //
        // So the count comparison is replaced, not relaxed: a rung must now declare which axes it
        // loses, the ladder cell measures them by name, and a rung whose declaration disagrees with
        // what the cell read is a blocker of its own.
        val axesByRung = intermediate.filter { it.measuredObligations != null }
            .associate { it.name to it.measuredAxes?.toSet() }
        intermediate.filter { it.measuredObligations != null && it.measuredAxes == null }.forEach {
            add(
                "rung `${it.name}` was measured before the ladder recorded WHICH obligations it loses. " +
                    "Re-queue `${LADDER_CONDITION_PREFIX}${it.name}`: two rungs are told apart by the " +
                    "axes they fail, and a count cannot distinguish two axes that cost the same"
            )
        }
        intermediate.forEach { rung ->
            val measuredAxes = rung.measuredAxes ?: return@forEach
            if (measuredAxes.toSet() != rung.losesAxes.toSet()) {
                add(
                    "rung `${rung.name}` was declared to lose ${rung.losesAxes} and measured losing " +
                        "$measuredAxes. A rung whose prediction and reading name different obligations " +
                        "is not calibrating anything"
                )
            }
        }
        val named = axesByRung.values.filterNotNull()
        if (named.size >= 2 && named.distinct().size != named.size) {
            add(
                "two measured rungs lose the SAME obligations $named: they are one rung wearing two " +
                    "names, and the oracle's scale is coarser than it looks"
            )
        }
        measured.filterNot { it > pristineFloor && it < case.oracleTestCount }.forEach {
            add(
                "a rung measured $it, outside the open interval ($pristineFloor, ${case.oracleTestCount}). " +
                    "A rung at the floor teaches nothing and a rung at the ceiling is not partial"
            )
        }

        addAll(rolloutProblems(goldNoteRollouts, case))
        addAll(baselineProblems(baselineRollouts, case))

        val goldFloorReading = goldNoteRollouts.mapNotNull { it.obligations }.minOrNull()
        val baselineCeilingReading = baselineRollouts.mapNotNull { it.endpointScore }.maxOrNull()
        if (goldFloorReading != null && baselineCeilingReading != null) {
            val gap = goldFloorReading - baselineCeilingReading
            if (gap * 2 < case.oracleTestCount) {
                add(
                    "the gold note buys $gap obligations over no note at all, less than half the " +
                        "${case.oracleTestCount}-point scale. A wave measured on that gap is measuring " +
                        "its own noise"
                )
            }
        }
    }

    private fun rolloutProblems(
        rollouts: List<AcquisitionRolloutEvidence>,
        case: UnderstandingCase,
    ): List<String> = buildList {
        if (rollouts.size < MIN_GOLD_NOTE_ROLLOUTS) {
            add(
                "${rollouts.size} gold-note rollout(s) recorded, $MIN_GOLD_NOTE_ROLLOUTS needed. Queue " +
                    "`oracle:gold` replicates until there are $MIN_GOLD_NOTE_ROLLOUTS"
            )
        }
        val required = (case.oracleTestCount * GOLD_NOTE_FLOOR_FRACTION).toInt()
        rollouts.forEach { rollout ->
            when {
                rollout.compiled == null -> add(
                    "gold-note rollout ${rollout.buildId} does not say whether its tree compiled: it " +
                        "predates the compilation axis, so its count cannot be told apart from a " +
                        "`javac` failure. Re-run it"
                )
                !rollout.compiled -> add(
                    "gold-note rollout ${rollout.buildId} did not compile. A task the weak agent cannot " +
                        "even build from a perfect note is not measuring the note"
                )
                rollout.obligations == null || rollout.obligations < required -> add(
                    "gold-note rollout ${rollout.buildId} scored ${rollout.obligations ?: "unmeasured"} " +
                        "of ${case.oracleTestCount}, under the $required needed. The implementation is " +
                        "out of reach of the solver, which tests a different hypothesis than the one " +
                        "this round asks"
                )
            }
        }
    }

    private fun baselineProblems(
        rollouts: List<AcquisitionRolloutEvidence>,
        case: UnderstandingCase,
    ): List<String> = buildList {
        if (rollouts.size < MIN_BASELINE_ROLLOUTS) {
            add(
                "${rollouts.size} baseline rollout(s) recorded, $MIN_BASELINE_ROLLOUTS needed. Without a " +
                    "measured floor a flat wave cannot be told from an easy task"
            )
        }
        // Amendment 2, as revised 2026-08-25. It first demanded baselines that DEMONSTRABLY BUILT,
        // because in rounds 2 and 3 a floor of zeros hid compile failures and nothing could tell "did
        // not understand" from "did not build". That reason is now served by a different mechanism:
        // `compiled` is its own recorded column, so a zero beside `compiled=0` is fully interpretable
        // and hides nothing. What the rule demands is therefore the VERDICT, not a successful build.
        //
        // And the question of whether a tree that never built deserves a zero is now settled by
        // measurement rather than by argument: the same solver, given 60 interactions or none at all,
        // compiled 15 of 15 (round 4, steps 3 and 4). So a no-note cell that fails to build inside the
        // wave's allowance did not meet an impossible task — it ran out of room, which is a failure of
        // the work and scores what a failure scores.
        val withVerdict = rollouts.count { it.compiled != null }
        if (withVerdict < MIN_BASELINE_ROLLOUTS) {
            add(
                "$withVerdict of ${rollouts.size} baseline rollout(s) carry a compile verdict, " +
                    "$MIN_BASELINE_ROLLOUTS needed. Without one a zero cannot be told apart from a " +
                    "`javac` failure, which is what produced three rounds of uninterpretable floors"
            )
        }
        rollouts.forEach { rollout ->
            if (rollout.compiled == null) {
                add(
                    "baseline rollout ${rollout.buildId} does not say whether its tree compiled. A " +
                        "no-note cell that failed to build reads as a beautifully low floor and is not one"
                )
            }
            val obligations = rollout.endpointScore
            if (obligations != null && obligations > pristineFloor + BASELINE_SLACK) {
                add(
                    "baseline rollout ${rollout.buildId} scored $obligations of ${case.oracleTestCount} " +
                        "with NO note. The solver is re-deriving the understanding instead of reading " +
                        "it, so tighten its interaction allowance before buying notes"
                )
            }
        }
    }

    companion object {
        /** Two, because one rung cannot show that two partial trees separate. */
        const val MIN_INTERMEDIATE_RUNGS: Int = 2

        /**
         * Three, and the number is a scar. `client-auth-method` read 9 of 9 on its first gold-note
         * anchor, which admitted it, and then read 0, 0, 0 on the next three replicates — the wave it
         * admitted was never bought only because the fourth run happened before the twelve note cells.
         */
        const val MIN_GOLD_NOTE_ROLLOUTS: Int = 3

        const val MIN_BASELINE_ROLLOUTS: Int = 2

        /** How much of the scale a gold note must buy for the task to count as reachable. */
        const val GOLD_NOTE_FLOOR_FRACTION: Double = 0.8

        /**
         * How far above the pristine floor a no-note cell may land.
         *
         * One obligation. An agent with no note that satisfies two of them beyond what an untouched
         * tree already satisfies is finding the architecture for itself, and no note can be measured
         * against that.
         */
        const val BASELINE_SLACK: Int = 1
    }
}

/**
 * One deliberately incomplete tree, and what it is expected to score.
 *
 * The rungs are the answer to a question the score alone cannot answer: does the oracle MEASURE, or
 * does it merely detect? Round 2 discovered the hard way that nine assertions can be one assertion,
 * and the only way to know is to build trees that are wrong in known, different ways and watch the
 * number move.
 *
 * A rung is either a SUBSET of the gold patch — the cheap and honest kind, because the harness can cut
 * it out of the reference implementation with no new artifact to maintain — or a hand-written patch
 * when the interesting partial tree is not a subset of anything. The invariant traps are all of the
 * second kind: "the whole change, but the neighbour's shortcut reused" is not a smaller gold, it is a
 * different one.
 */
data class AcquisitionPartialRung(
    val name: String,
    /** Which gold files this rung keeps, or null when it is a standalone patch. */
    val goldPaths: List<String>? = null,
    /** Classpath resource of a standalone partial patch, or null when this rung is a gold subset. */
    val patchResource: String? = null,
    /** What the case's author predicted, BEFORE the ladder cell ran. */
    val expectedObligations: Int,
    /** What the ladder cell actually read, or null until one has run. */
    val measuredObligations: Int? = null,
    /**
     * The build that read it, so a recorded rung can be re-read rather than believed.
     *
     * Same reason [AcquisitionRolloutEvidence] carries one: every previous round's calibration lived
     * in prose, and prose cannot say which of two runs of a condition it is quoting.
     */
    val measuredIn: String? = null,
    /**
     * The oracle's test methods this rung is predicted to fail, by name.
     *
     * Written BEFORE the ladder cell runs, like [expectedObligations], and compared against what the
     * cell reads. Names rather than a count because a count cannot distinguish two independent axes
     * that happen to cost the same — which is exactly what this case family produces.
     */
    val losesAxes: List<String> = emptyList(),
    /** Which axes the ladder cell actually saw fail, or null until one has run. */
    val measuredAxes: List<String>? = null,
    /** Which obligation this rung is meant to isolate, in one sentence. */
    val isolates: String,
) {
    /** True for the rung that keeps every gold file, i.e. the ceiling. */
    val isWholeGold: Boolean get() = goldPaths != null && goldPaths.isEmpty()

    /** Whether a standalone rung's patch is on the test classpath; trivially true for a gold subset. */
    val patchResourceExists: Boolean
        get() = patchResource == null ||
            AcquisitionPartialRung::class.java.classLoader.getResource(patchResource) != null

    /**
     * The patch this rung deploys, cut from [gold] or read from its own resource.
     *
     * An empty [goldPaths] means "all of it": the ceiling rung is the gold patch itself, and spelling
     * out its file list here would be a second copy of the gold's manifest that can drift from it.
     */
    fun patch(gold: String): String = when {
        patchResource != null -> checkNotNull(
            AcquisitionPartialRung::class.java.classLoader.getResourceAsStream(patchResource)
        ) {
            "rung `$name` names a patch resource that is not on the test classpath: $patchResource"
        }.use { it.readBytes().decodeToString() }

        goldPaths.isNullOrEmpty() -> gold
        else -> filterPatchToPaths(gold, goldPaths)
    }
}

/**
 * One measured calibration cell, quoted by build id so the reading can be re-read.
 *
 * The build id is not decoration. Every previous round's calibration lived in prose, and prose does
 * not say which of two runs of the same condition it is describing — which is how a case with readings
 * 9, 0, 0, 0 was remembered as "the ceiling is nine".
 */
data class AcquisitionRolloutEvidence(
    val buildId: String,
    /** Obligations satisfied, or null when the tree did not compile and the count is unmeasured. */
    val obligations: Int?,
    /**
     * Whether that cell's tree compiled.
     *
     * Null means the cell ran BEFORE compilation became its own reading, so nobody knows — and under
     * this protocol nobody-knows is not evidence. Every such rollout has to be bought again, which is
     * cheap next to publishing a correlation computed over `javac` failures.
     */
    val compiled: Boolean? = null,
) {
    /**
     * The obligation count as the ENDPOINT reads it: a tree that did not build satisfied none.
     *
     * Deliberately a second property rather than a change to [obligations]. The raw reading stays
     * honest — a cell publishes `oraclePassed=unmeasured/N ... compiled=0`, and the two facts remain
     * separable in the CSV forever — while the decision to score an unbuilt tree as zero is one named
     * place a reader can find and disagree with, instead of an averaging convention nobody voted on.
     *
     * Null only when nobody recorded whether the tree built, which is not a score but a missing cell.
     */
    val endpointScore: Int?
        get() = when (compiled) {
            null -> null
            false -> 0
            true -> obligations
        }
}

/**
 * The pre-registered admission record of every acquisition case, addressed by id.
 *
 * Held in code beside the matrices for the reason the matrices are held in code: a protocol whose
 * thresholds can be edited after the readings are in is a description of the readings.
 */
val ACQUISITION_CASE_ADMISSIONS: Map<String, AcquisitionCaseAdmission> = listOf(
    AcquisitionCaseAdmission(
        caseId = "acquisition__keycloak__cc-refresh-token",
        // A4 (`noOtherShippedProfileGainsIt`) is true of a tree that changed nothing.
        pristineFloor = 1,
        rungs = listOf(
            AcquisitionPartialRung(
                name = "implementation-only",
                goldPaths = listOf(
                    "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
                        "RejectClientCredentialsRefreshTokenExecutor.java",
                    "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
                        "RejectClientCredentialsRefreshTokenExecutorFactory.java",
                ),
                expectedObligations = 7,
                measuredObligations = 7,
                measuredIn = "1040026994",
                losesAxes = listOf(
                    "factoryIsRegisteredThroughTheServiceLoader",
                    "theShippedStrictProfileListsIt",
                ),
                measuredAxes = listOf(
                    "theShippedStrictProfileListsIt",
                    "factoryIsRegisteredThroughTheServiceLoader",
                ),
                isolates = "the two registration axes: the class exists and behaves, but nothing " +
                    "resolves it through the SPI or lists it in the shipped profile",
            ),
            AcquisitionPartialRung(
                name = "implementation-and-spi",
                goldPaths = listOf(
                    "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
                        "RejectClientCredentialsRefreshTokenExecutor.java",
                    "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
                        "RejectClientCredentialsRefreshTokenExecutorFactory.java",
                    "services/src/main/resources/META-INF/services/" +
                        "org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory",
                ),
                expectedObligations = 8,
                measuredObligations = 8,
                measuredIn = "1040026996",
                losesAxes = listOf("theShippedStrictProfileListsIt"),
                measuredAxes = listOf("theShippedStrictProfileListsIt"),
                isolates = "the second, non-obvious mechanism alone — the entry in the shipped " +
                    "high-security profile, which a `copy the neighbour` solution never adds",
            ),
            AcquisitionPartialRung(
                name = "naive-partial-update",
                // Not a subset of anything: the whole change, with the UPDATE branch reading only the
                // proposed representation, exactly as the misleading in-tree precedent does. The
                // oracle's authors built this tree and measured 8; it was never exported, which is why
                // it has to be re-created rather than merely re-run.
                patchResource = "acquisition-cases/acquisition__keycloak__cc-refresh-token/" +
                    "partial-naive-invariant.patch",
                expectedObligations = 8,
                measuredObligations = 8,
                measuredIn = "1040026998",
                // The same COUNT as `implementation-and-spi` and a different obligation — which is why
                // this ladder is compared by name. See amendment 1 in `problems`. Measured twice, on
                // two builds: 1040019633 read 8 before the axes were recorded, 1040026998 read the
                // same 8 and named the axis.
                losesAxes = listOf("partialUpdateOfAClientThatAlreadyHasItOnIsRejected"),
                measuredAxes = listOf("partialUpdateOfAClientThatAlreadyHasItOnIsRejected"),
                isolates = "the partial-update invariant, and only it",
            ),
            AcquisitionPartialRung(
                name = "gold",
                goldPaths = emptyList(),
                expectedObligations = 9,
                measuredObligations = 9,
                measuredIn = "1039952168",
                isolates = "nothing — this is the ceiling",
            ),
        ),
        // Re-bought 2026-08-24 under requirement 6, and the answer is the opposite of round 2's.
        // Round 2's anchors (1039289688/690 read 9, 1039289680/682/684/686 read 0) carried no compile
        // verdict and are superseded rather than kept beside these: a count that cannot be told apart
        // from a `javac` failure is not a second opinion, it is the reading this protocol refuses.
        //
        // All THREE gold-note rollouts failed to compile. The weak agent, handed the reference
        // description of the change, cannot build this tree inside twenty interactions.
        solverAllowance = 15,
        // Re-anchored at 15 a second time on 2026-08-28, with a repair turn that can finally act (see
        // UNDERSTANDING_REPAIR_READABLE_FILE). Three of three at the ceiling again, and all three
        // without needing the repair at all: a note that says where the change goes leaves the weak
        // agent nothing to be rescued from. The 2026-08-25 readings (1041682125, 1041794369/371) said
        // the same thing under an inert repair and are superseded by these rather than averaged with
        // them — the mechanism differed, so they are not replicates of each other.
        goldNoteRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1044788438", obligations = 9, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788440", obligations = 9, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788442", obligations = 9, compiled = true),
        ),
        // The floor, measured where it is hardest to defend: with a working repair turn, which hands a
        // no-note cell the one thing it lacks. Two of the three trees still did not build after three
        // repair rounds, and the one the repair DID rescue scored 1 of 9 — the pristine floor, the
        // obligation an untouched tree already satisfies. So on this case compilation and understanding
        // separate: the agent could be helped to build its code and still did not learn where the
        // change belongs. That is what makes the case measurable, and it is not true of its two
        // siblings, where a rescued no-note tree read 5 of 9 and 6 of 10.
        // At 25 (1044593892/894/897) this same floor read 4, unbuilt and 7 — which is why the raised
        // allowance was withdrawn, and why those readings belong to that allowance, not this one.
        baselineRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1044788444", obligations = null, compiled = false),
            AcquisitionRolloutEvidence(buildId = "1044788446", obligations = 1, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788448", obligations = null, compiled = false),
        ),
        // RETIREMENT WITHDRAWN 2026-08-25. It was declared on 1040174097/099/101, three gold-note
        // rollouts that produced no gradable tree — but those cells were bought BEFORE amendment 3,
        // and two of the three failed `testCompile` on a unit test the agent wrote itself, after its
        // implementation had compiled clean. Amendment 3 discards exactly those files, so the reading
        // the retirement rested on no longer exists and the case has to be re-anchored rather than
        // buried. Only 1040174099 failed on the agent's own implementation.
        //
        // This is a correction of a decision, not a relaxation of a rule: the stopping rule is intact
        // and may fire again on the re-bought anchors.
    ),
    AcquisitionCaseAdmission(
        caseId = "acquisition__keycloak__client-auth-method",
        pristineFloor = 1,
        rungs = listOf(
            AcquisitionPartialRung(
                name = "implementation-only",
                goldPaths = listOf(
                    "services/src/main/java/org/keycloak/authentication/authenticators/client/" +
                        "SelfSignedX509ClientAuthenticator.java",
                    "services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java",
                ),
                expectedObligations = 7,
                measuredObligations = 7,
                measuredIn = "1040027000",
                losesAxes = listOf(
                    "theAuthenticatorIsRegisteredThroughTheProviderSpi",
                    "theShippedCertificateAwareProfilesAllowTheNewAuthenticator",
                ),
                measuredAxes = listOf(
                    "theAuthenticatorIsRegisteredThroughTheProviderSpi",
                    "theShippedCertificateAwareProfilesAllowTheNewAuthenticator",
                ),
                isolates = "both registrations; the protocol-method constant travels with the class " +
                    "because the class does not compile without it",
            ),
            AcquisitionPartialRung(
                name = "implementation-and-spi",
                goldPaths = listOf(
                    "services/src/main/java/org/keycloak/authentication/authenticators/client/" +
                        "SelfSignedX509ClientAuthenticator.java",
                    "services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java",
                    "services/src/main/resources/META-INF/services/" +
                        "org.keycloak.authentication.ClientAuthenticatorFactory",
                ),
                expectedObligations = 8,
                measuredObligations = 8,
                measuredIn = "1040027002",
                losesAxes = listOf("theShippedCertificateAwareProfilesAllowTheNewAuthenticator"),
                measuredAxes = listOf("theShippedCertificateAwareProfilesAllowTheNewAuthenticator"),
                isolates = "the allow-list in the shipped client profiles",
            ),
            AcquisitionPartialRung(
                name = "gold",
                goldPaths = emptyList(),
                expectedObligations = 9,
                measuredObligations = 9,
                measuredIn = "1039952878",
                isolates = "nothing — this is the ceiling",
            ),
        ),
        solverAllowance = 15,
        // Re-anchored at 15 on 2026-08-28 with a working repair turn, and the ceiling did not survive
        // the second look: 9, 6 and 7 of 9 across three rollouts, with 6 under the reachability floor
        // the rule asks for. The three 9s of 2026-08-25 (1041682138, 1041794373/375) were a run of the
        // same condition, and this case has produced that pattern before — 9 of 9 on its first anchor,
        // then 0, 0, 0 on the next three. Three rollouts is the minimum for exactly this reason, and
        // the honest reading of six of them is a ceiling this solver reaches sometimes.
        goldNoteRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1044788450", obligations = 6, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788452", obligations = 7, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788454", obligations = 9, compiled = true),
        ),
        // And the floor rose the moment the repair turn could act: three rounds of repair carried one
        // no-note tree to a build, and it scored 5 of 9 — four obligations above the pristine floor,
        // where the rule allows one. Repair is given to both arms and only the arm with no note needs
        // it, so on this case it subtracts from the gap it was bought to clean up.
        baselineRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1044788456", obligations = 5, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788462", obligations = null, compiled = false),
            AcquisitionRolloutEvidence(buildId = "1044788464", obligations = null, compiled = false),
        ),
    ),
    AcquisitionCaseAdmission(
        caseId = "acquisition__keycloak__oauth-grant-type",
        pristineFloor = 1,
        rungs = listOf(
            AcquisitionPartialRung(
                name = "implementation-only",
                goldPaths = listOf(
                    "services/src/main/java/org/keycloak/protocol/oidc/grants/" +
                        "OfflineRefreshTokenGrantType.java",
                    "services/src/main/java/org/keycloak/protocol/oidc/grants/" +
                        "OfflineRefreshTokenGrantTypeFactory.java",
                ),
                expectedObligations = 8,
                measuredObligations = 8,
                measuredIn = "1040027004",
                losesAxes = listOf(
                    "theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt",
                    "theGrantAppearsInThePublishedGrantTypesSupported",
                ),
                measuredAxes = listOf(
                    "theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt",
                    "theGrantAppearsInThePublishedGrantTypesSupported",
                ),
                isolates = "the ServiceLoader registration, which is simultaneously what makes the " +
                    "grant dispatchable and what puts it in the discovery document",
            ),
            AcquisitionPartialRung(
                name = "naive-shortcut",
                // The whole change with the neighbour's two-character code reused. Not a subset: the
                // trap of this case is a collision detected in ANOTHER subsystem at start-up, and a
                // tree that merely lacks a file never reaches it.
                patchResource = "acquisition-cases/acquisition__keycloak__oauth-grant-type/" +
                    "partial-naive-shortcut.patch",
                expectedObligations = 9,
                measuredObligations = 9,
                measuredIn = "1040027006",
                // Also measured at 9 by 1040019635, before the axes were recorded.
                losesAxes = listOf("theTokenContextShortCodeIsGloballyUnique"),
                measuredAxes = listOf("theTokenContextShortCodeIsGloballyUnique"),
                isolates = "the global uniqueness invariant enforced by the token-context encoder",
            ),
            AcquisitionPartialRung(
                name = "gold",
                goldPaths = emptyList(),
                expectedObligations = 10,
                measuredObligations = 10,
                measuredIn = "1039952882",
                isolates = "nothing — this is the ceiling",
            ),
        ),
        // Re-bought under amendment 3; 1040174130/132/134 (unmeasured, 10, 10 — the first failed
        // `testCompile` on the agent's own test) and the round-3 pair 1039700657/653 are superseded.
        // Three of three at the ceiling, each having discarded exactly one agent-authored test.
        solverAllowance = 25,
        // LOOSENED, not tightened, and for the opposite reason. This case's floor was already zero at
        // 20 and its gold reached the ceiling 3 of 3 — but its NOTE WAVE sat on the floor: three of
        // twenty-four cells produced a gradable tree. Nothing the experiment can produce landed between
        // floor and ceiling, so the allowance moves up. At 15 even the gold note failed once
        // (1041682140, did not compile), which is the evidence that this case needs more room, not less.
        goldNoteRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1044788466", obligations = 10, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788468", obligations = 10, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788470", obligations = 10, compiled = true),
        ),
        // The floor of 2026-08-25 was "neither no-note tree builds", and it was a reading of a repair
        // turn that could not edit a file. With one that can, two of three no-note trees were carried
        // to a build and both scored 6 of 10 — five obligations above the pristine floor, reproducibly.
        // What the earlier zero measured was the compile flip, not the value of knowing where the
        // change goes; on this case the unaided solver knows most of it.
        baselineRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1044788472", obligations = 6, compiled = true),
            AcquisitionRolloutEvidence(buildId = "1044788474", obligations = null, compiled = false),
            AcquisitionRolloutEvidence(buildId = "1044803876", obligations = 6, compiled = true),
        ),
    ),
).associateBy { it.caseId }

/**
 * Refuses a note wave on a case that has not shown what [AcquisitionCaseAdmission] asks of it.
 *
 * Called by the downstream cell BEFORE it starts a container, and deliberately not by the calibration
 * conditions: `baseline`, `oracle:gold` and the ladder rungs are exactly the cells that produce the
 * missing evidence, so gating them would make the block impossible to lift.
 */
fun requireAcquisitionAdmission(case: UnderstandingCase, budget: Int? = null) {
    val admission = checkNotNull(ACQUISITION_CASE_ADMISSIONS[case.instanceId]) {
        "'${case.instanceId}' has no admission record. A case joins a downstream wave through " +
            "ACQUISITION_CASE_ADMISSIONS, with its ladder and its thresholds written down BEFORE the " +
            "first note cell is queued"
    }
    // The allowance is per case now, so a note cell queued at another case's number would be graded
    // against a floor and a ceiling that were never measured under it — and would look like an ordinary
    // cell in the table. Refused before a container starts, like every other admission failure.
    check(budget == null || budget == admission.solverAllowance) {
        "'${case.instanceId}' is calibrated at an allowance of ${admission.solverAllowance} " +
            "interactions and this cell was queued at $budget. Its floor and ceiling were measured at " +
            "${admission.solverAllowance}; a wave run at anything else is measured against readings " +
            "that do not exist. Queue it with -D$UNDERSTANDING_BUDGET_PROPERTY=${admission.solverAllowance}"
    }
    val problems = admission.problems(case)
    check(problems.isEmpty()) {
        "'${case.instanceId}' is NOT admitted to a downstream note wave. What it is waiting for:\n" +
            problems.joinToString("\n") { " - $it" } +
            "\n\nThe calibration conditions (`baseline`, `oracle:gold`, " +
            "`${LADDER_CONDITION_PREFIX}<rung>`) are always allowed — they are how this list gets shorter."
    }
}

/**
 * How a ladder cell is queued: `ladder:<rungName>`.
 *
 * A prefix of its own rather than another `oracle:` name, because a ladder cell is a different KIND of
 * cell — it deploys a patch and runs no agent at all. Sharing the oracle prefix would let a rung land
 * in a table of agent rollouts, where its perfect score would read as a downstream success.
 */
const val LADDER_CONDITION_PREFIX: String = "ladder:"

/**
 * One rung of the calibration ladder, as a cell condition.
 *
 * It carries no note because there is no agent to give one to: the cell applies the rung's patch to a
 * pristine tree, applies the oracle on top and reads the obligations. That is the whole measurement,
 * and it costs container minutes and no model tokens — which is why the protocol can afford to demand
 * a ladder of every case.
 */
data class AcquisitionLadderCondition(val rungName: String) : UnderstandingCondition {
    override val label: String = "ladder-$rungName"

    override fun noteText(case: UnderstandingCase): String? =
        error("`${label}` is a ladder cell: it deploys a patch and runs no agent, so it has no note")
}

/** Parses `ladder:<rungName>` into the rung of [case] it names, refusing an unknown name. */
fun acquisitionLadderRung(case: UnderstandingCase, rungName: String): AcquisitionPartialRung {
    val admission = checkNotNull(ACQUISITION_CASE_ADMISSIONS[case.instanceId]) {
        "'${case.instanceId}' has no admission record, so it declares no ladder"
    }
    return checkNotNull(admission.rungs.firstOrNull { it.name == rungName }) {
        "'${case.instanceId}' declares no rung `$rungName`. Its rungs are " +
            "${admission.rungs.map { it.name }}"
    }
}

private val PATCH_FILE_HEADER = Regex("""^diff --git a/(\S+) b/\S+$""", RegexOption.MULTILINE)

/**
 * Cuts a unified diff down to the files named in [paths], keeping their hunks byte for byte.
 *
 * The ladder's cheap half depends on this: a partial tree that is a subset of the gold change needs no
 * artifact of its own, so it cannot drift from the gold and cannot be quietly edited into agreeing
 * with a reading. Refuses a path it cannot find rather than returning a smaller patch — a rung that
 * silently deployed three of its four files would be measured under the wrong name.
 */
fun filterPatchToPaths(patch: String, paths: Collection<String>): String {
    val wanted = paths.toSet()
    val headers = PATCH_FILE_HEADER.findAll(patch).toList()
    check(headers.isNotEmpty()) { "no `diff --git` header in the patch to filter" }
    val kept = StringBuilder()
    val seen = LinkedHashSet<String>()
    headers.forEachIndexed { index, header ->
        val path = header.groupValues[1]
        if (path !in wanted) return@forEachIndexed
        seen += path
        val end = headers.getOrNull(index + 1)?.range?.first ?: patch.length
        kept.append(patch, header.range.first, end)
    }
    val missing = wanted - seen
    check(missing.isEmpty()) {
        "the patch does not touch ${missing.sorted()}; it touches " +
            "${headers.map { it.groupValues[1] }.sorted()}"
    }
    return kept.toString()
}
