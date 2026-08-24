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
    /** The rungs between floor and ceiling, plus the ceiling itself as the last entry. */
    val rungs: List<AcquisitionPartialRung>,
    /** Weak-agent cells run with the hand-written gold note — the reachability evidence. */
    val goldNoteRollouts: List<AcquisitionRolloutEvidence>,
    /** Weak-agent cells run with no note at all — the floor evidence. */
    val baselineRollouts: List<AcquisitionRolloutEvidence>,
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
        val baselineCeilingReading = baselineRollouts.mapNotNull { it.obligations }.maxOrNull()
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
        rollouts.forEach { rollout ->
            if (rollout.compiled == null) {
                add(
                    "baseline rollout ${rollout.buildId} does not say whether its tree compiled. A " +
                        "no-note cell that failed to build reads as a beautifully low floor and is not one"
                )
            }
            val obligations = rollout.obligations
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
)

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
        // Round 2's ceiling anchors. Both read 9 of 9, so both certainly compiled; recorded as null
        // anyway, because "it must have" is the kind of inference this protocol exists to refuse, and
        // because the round-3 repair changed the grading scope under them.
        goldNoteRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1039289688", obligations = 9),
            AcquisitionRolloutEvidence(buildId = "1039289690", obligations = 9),
        ),
        // All four read ZERO, which is BELOW the pristine floor of one — a tree that compiled cannot
        // score less than an untouched tree. So either these solvers broke the shipped profiles or
        // their trees never built, and nobody recorded which, because at the time there was nothing to
        // record it in. That is precisely the reading this protocol refuses to accept as a floor.
        baselineRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1039289680", obligations = 0),
            AcquisitionRolloutEvidence(buildId = "1039289682", obligations = 0),
            AcquisitionRolloutEvidence(buildId = "1039289684", obligations = 0),
            AcquisitionRolloutEvidence(buildId = "1039289686", obligations = 0),
        ),
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
        // 9, then 0, 0, 0. The case that made three the minimum — and the three zeros are not three
        // failures to understand: their trees did not compile (an import of an internal JDK type), so
        // under this protocol they carry no obligation count at all.
        goldNoteRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1039700655", obligations = 9),
            AcquisitionRolloutEvidence(buildId = "1039761044", obligations = null, compiled = false),
            AcquisitionRolloutEvidence(buildId = "1039761157", obligations = null, compiled = false),
        ),
        baselineRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1039700643", obligations = 0),
            AcquisitionRolloutEvidence(buildId = "1039700645", obligations = 0),
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
        goldNoteRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1039700657", obligations = 10),
            AcquisitionRolloutEvidence(buildId = "1039700653", obligations = 10),
        ),
        baselineRollouts = listOf(
            AcquisitionRolloutEvidence(buildId = "1039700647", obligations = 0),
            AcquisitionRolloutEvidence(buildId = "1039700649", obligations = 0),
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
fun requireAcquisitionAdmission(case: UnderstandingCase) {
    val admission = checkNotNull(ACQUISITION_CASE_ADMISSIONS[case.instanceId]) {
        "'${case.instanceId}' has no admission record. A case joins a downstream wave through " +
            "ACQUISITION_CASE_ADMISSIONS, with its ladder and its thresholds written down BEFORE the " +
            "first note cell is queued"
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
