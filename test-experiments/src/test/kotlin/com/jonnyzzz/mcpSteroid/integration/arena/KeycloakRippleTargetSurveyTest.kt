/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Selects the ripple family's targets by measuring Keycloak, and is the go/no-go for every slot: a
 * kind with no qualifying candidate is reported empty rather than filled with an easier target.
 *
 * No agent, no oracle, no grading — this run only prints. Its output is transcribed into the case
 * registry as pinned constants, and `RippleCaseRegistryTest` later asserts the registry matches what
 * was transcribed.
 *
 * **Phases are selectable, and each is its own `execute_code` call.** Once a kind's numbers are
 * transcribed and locked, re-measuring it buys nothing and costs the IDE's whole budget — which is
 * how the pull-up query came to die twice sharing a script with three already-pinned kinds. Pass
 * `-Dripple.survey.phases=<csv>` to run a subset; the default runs everything.
 */
class KeycloakRippleTargetSurveyTest {

    /** One measurement the survey can perform, selected by [SURVEY_PHASES_PROPERTY]. */
    enum class SurveyPhase(val id: String) {
        /** rename-type, change-signature and move-class in one script — SLOTs 1-6, all pinned. */
        KINDS("kinds"),

        /** The pull-up query alone, with the whole process budget — SLOT 7. */
        PULL_UP("pull-up"),

        /**
         * rename-method over the pool [KINDS] cannot reach — methods of uniquely-named interfaces.
         * This is where the retargeted pilot comes from; see [RippleTargetSurveyScripts.renameMethod].
         */
        RENAME_METHOD("rename-method"),

        /** Reads the two change-signature cases' pinned decoy counts back out of the index. */
        DECOYS("decoys"),

        /**
         * Runs the retargeted pilot's own CAPTURE script and its tripwires — the exact code an arm
         * runs before the agent starts. See [verifyPilotPins].
         */
        PINS("pins"),
    }

    private fun selectedPhases(): List<SurveyPhase> {
        val raw = System.getProperty(SURVEY_PHASES_PROPERTY)?.trim()
        if (raw.isNullOrEmpty()) return SurveyPhase.entries.toList()
        val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return ids.map { id ->
            SurveyPhase.entries.firstOrNull { it.id == id }
                ?: error("Unknown survey phase '$id'. Known: ${SurveyPhase.entries.joinToString { it.id }}")
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `survey keycloak for ripple targets of every kind`() {
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ripple-target-survey",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = SemanticRippleSpec.cloneUrl,
                    repoOwnerAndName = SemanticRippleSpec.repoOwnerAndName,
                    baseCommit = SemanticRippleSpec.baseCommit,
                    testPatch = "",
                    displayName = "keycloak-ripple-survey",
                    buildSystem = "maven",
                ),
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = false,
                // Keycloak is the largest project this harness imports (189 modules) and its
                // cold-start VFS-refresh/import storm is the longest. The raised bound covers a
                // dialog-less modal progress that is ALREADY up when a script arrives; the storm's
                // other shape — modality entered between the pre-flight wait and the gate — is a
                // race no bound can close, and `mcpExecuteCode` retries that one.
                dialoglessModalWaitMs = 600_000,
            )).waitForProjectReady(
                timeoutMillis = SemanticRippleSpec.projectReadyTimeoutMs,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                requireCleanCompile = false,
            )

            val phases = selectedPhases()
            println("[SURVEY] phases: ${phases.joinToString { it.id }}")
            for (phase in phases) {
                when (phase) {
                    SurveyPhase.KINDS -> surveyCheapKinds(session)
                    SurveyPhase.PULL_UP -> surveyPullUp(session)
                    SurveyPhase.RENAME_METHOD -> surveyRenameMethod(session)
                    SurveyPhase.PINS -> verifyPilotPins(session)
                    SurveyPhase.DECOYS -> verifyPinnedDecoyCounts(session)
                }
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun report(label: String, qualified: List<SurveyCandidate>) {
        println("[SURVEY] $label — ${qualified.size} qualifying")
        qualified.sortedByDescending { it.references }.take(10).forEach {
            println("[SURVEY]   ${it.ownerFqn}#${it.name} refs=${it.references} files=${it.files} " +
                "modules=${it.modules} sameName=${it.sameNameDeclarations} breadth=${it.hierarchyBreadth}")
        }
    }

    /** The four kinds one script can afford together — everything except pull-up. */
    private fun surveyCheapKinds(session: IntelliJContainer) {
        val output = session.mcpSteroid.mcpExecuteCode(
            code = RippleTargetSurveyScripts.survey(),
            reason = "Survey Keycloak for qualifying rename-method, rename-type, change-signature and move-class targets",
            taskId = "ripple-target-survey",
            timeout = 3_600,
        ).stdout

        val candidates = parseSurveyCandidates(output)
        assertTrue(candidates.isNotEmpty()) { "The survey found no candidates at all:\n$output" }
        for (kind in listOf("rename-method", "rename-type", "change-signature", "move-class")) {
            val ofKind = candidates.filter { it.kind == kind }
            report("$kind WIDE", ofKind.filter { it.qualifiesAsWide() })
            report("$kind NARROW", ofKind.filter { it.qualifiesAsNarrow() })
        }
        reportRenameMethodEvidence(candidates, output)
    }

    /**
     * Everything a rename-method case needs beyond the candidate line, printed for the ones that
     * qualify as wide: the modules a compile gate would have to cover, and the string-literal count
     * that decides whether the rename is behaviour-preserving at all.
     *
     * The literal count is the reason this report exists. A candidate with a non-zero count is
     * disqualified however good its fan-out is — see [RippleNameEscapeRule] — and the pilot's own
     * target is the worked example of what happens when nobody looks.
     */
    private fun reportRenameMethodEvidence(candidates: List<SurveyCandidate>, output: String) {
        val modules = parseCandidateModules(output).associateBy { it.ownerFqn to it.name }
        val literals = parseLiteralNameOccurrences(output).associateBy { it.ownerFqn to it.name }
        val wide = candidates.filter { it.kind == "rename-method" && it.qualifiesAsWide() }
        println("[SURVEY] rename-method WIDE evidence — ${wide.size} candidates")
        wide.sortedByDescending { it.references }.forEach { candidate ->
            val key = candidate.ownerFqn to candidate.name
            val literalCount = literals[key]?.occurrences
            val verdict = when (literalCount) {
                null -> "NO LITERAL READING — do not pin"
                0 -> "no string literal names it"
                else -> "DISQUALIFIED: $literalCount string literals name it"
            }
            println("[SURVEY]   ${candidate.ownerFqn}#${candidate.name} refs=${candidate.references} " +
                "files=${candidate.files} modules=${candidate.modules} " +
                "sameName=${candidate.sameNameDeclarations} — $verdict")
            println("[SURVEY]     modules: ${modules[key]?.modules?.joinToString(", ") ?: "not printed"}")
        }
        val jaxRs = output.lines().map { it.trim() }.filter { it.startsWith("SURVEY_JAXRS_EXCLUDED ") }
        println("[SURVEY] rename-method candidates excluded as JAX-RS resource methods: ${jaxRs.size}")
        jaxRs.take(20).forEach { println("[SURVEY]   $it") }
    }

    /**
     * Runs the retargeted pilot's CAPTURE script and its tripwires against the tree.
     *
     * The other cases were pinned from survey lines alone. That is how an ill-posed case got pinned:
     * the survey answers reach, and the capture answers whether the case can be graded at all — it is
     * the capture that refuses a JAX-RS target and that reports every string literal naming the
     * method. Running it here, agentless, costs one script and replaces "the arithmetic agrees" with
     * "the code that grades the arm agrees".
     *
     * The overlay is deliberately absent from this container, and the pins are unaffected by that:
     * they describe the repository at the base commit, and `parseSemanticGold` excludes the hidden
     * consumer's own files precisely so they keep doing so.
     */
    private fun verifyPilotPins(session: IntelliJContainer) {
        val case = RippleCases.renameMethodWide
        val output = session.mcpSteroid.mcpExecuteCode(
            code = RippleOracleScripts.capture(case),
            reason = "Verify the retargeted pilot's pinned gold measurement against the index",
            taskId = "ripple-pin-verify",
            timeout = 1_800,
        ).stdout

        val gold = parseSemanticGold(output, case.hiddenConsumerFiles())
        println("[PIN-VERIFY] ${case.target.targetDescription}: measured ${gold.totalReferences} " +
            "references over ${gold.files} files, ${gold.decoyReferences.size} decoys, " +
            "${gold.newNameDeclarations} declarations of '${case.target.destinationDescription}', " +
            "${gold.literalNameSites.size} files with a string literal naming it")
        println("[PIN-VERIFY] pinned: ${case.expectedGoldReferences} references, " +
            "${case.expectedGoldFiles} files, ${case.expectedDecoyDeclarations} decoys")
        // Asserted, unlike the reporting phases: these are the tripwires that would abort every arm.
        gold.checkTripwires(case)
        println("[PIN-VERIFY] tripwires PASS — the case can be graded")
    }

    /**
     * The rename-method query on its own call, and the full evidence for every candidate it emits.
     *
     * Every line printed here is a line a case would pin, so it prints all of them rather than a top
     * ten: the pool is small by construction (the script emits nothing below the wide fan-out floor)
     * and a candidate omitted from the report is a candidate nobody can choose.
     */
    private fun surveyRenameMethod(session: IntelliJContainer) {
        val output = session.mcpSteroid.mcpExecuteCode(
            code = RippleTargetSurveyScripts.renameMethod(),
            reason = "Survey Keycloak for qualifying rename-method targets outside the ambiguous-owner pool",
            taskId = "ripple-rename-method-survey",
            timeout = 3_600,
        ).stdout

        val candidates = parseSurveyCandidates(output).filter { it.kind == "rename-method" }
        val modules = parseCandidateModules(output).associateBy { it.ownerFqn to it.name }
        val literals = parseLiteralNameOccurrences(output).associateBy { it.ownerFqn to it.name }
        val overrides = output.lines().map { it.trim() }
            .filter { it.startsWith("SURVEY_OVERRIDES ") }
            .associate { line ->
                val parts = line.removePrefix("SURVEY_OVERRIDES ").split('|')
                (parts[0] to parts[1]) to parts[2].toInt()
            }
        val jaxRs = output.lines().map { it.trim() }
            .filter { it.startsWith("SURVEY_JAXRS_EXCLUDED ") }
            .map { it.removePrefix("SURVEY_JAXRS_EXCLUDED ").replace('|', '#') }
            .toSet()

        println("[SURVEY] rename-method (wide pool) — ${candidates.size} candidates")
        candidates.sortedByDescending { it.references }.forEach { candidate ->
            val key = candidate.ownerFqn to candidate.name
            val literalCount = literals[key]?.occurrences
            val verdict = when {
                "${candidate.ownerFqn}#${candidate.name}" in jaxRs ->
                    "DISQUALIFIED: JAX-RS resource method, addressable by name"
                literalCount == null -> "NO LITERAL READING — do not pin"
                literalCount > 0 -> "DISQUALIFIED: $literalCount string literals name it"
                !candidate.qualifiesAsWide() -> "does not clear the wide thresholds"
                else -> "ELIGIBLE"
            }
            println("[SURVEY]   ${candidate.ownerFqn}#${candidate.name} refs=${candidate.references} " +
                "files=${candidate.files} modules=${candidate.modules} " +
                "sameName=${candidate.sameNameDeclarations} " +
                "overrides=${overrides[key] ?: "?"} — $verdict")
            println("[SURVEY]     modules: ${modules[key]?.modules?.joinToString(", ") ?: "not printed"}")
        }
    }

    /**
     * The pull-up query on its own call, and the near-miss evidence a `NONE QUALIFYING` verdict needs.
     *
     * The script emits no candidate whose destination is below [MIN_PULL_UP_BREADTH] — that gate is
     * what makes the query survivable — so the breadth near-miss is read from the `SURVEY_PULLUP_SUPER`
     * lines, which cost one index line per evaluated supertype and no search.
     */
    private fun surveyPullUp(session: IntelliJContainer) {
        val output = session.mcpSteroid.mcpExecuteCode(
            code = RippleTargetSurveyScripts.pullUp(),
            reason = "Survey Keycloak for qualifying pull-up targets",
            taskId = "ripple-pull-up-survey",
            timeout = 3_600,
        ).stdout

        val candidates = parseSurveyCandidates(output).filter { it.kind == "pull-up" }
        // Distinct by FQN: an FQN can resolve to two `PsiClass` entries (the same class visible
        // through two source roots), and counting those twice would overstate how many destinations
        // the query actually evaluated.
        val supers = parsePullUpSuperTypes(output).distinctBy { it.fqn }
        println("[SURVEY] pull-up destinations evaluated: ${supers.size}, " +
            "of them at breadth >= $MIN_PULL_UP_BREADTH: ${supers.count { it.breadth >= MIN_PULL_UP_BREADTH }}")
        supers.sortedByDescending { it.breadth }.take(10).forEach {
            println("[SURVEY]   destination ${it.fqn} breadth=${it.breadth}")
        }
        report("pull-up", candidates.filter { it.qualifiesForPullUp() })
        // Every candidate the script emitted already clears breadth, so a candidate listed here failed
        // one of the wide thresholds — which one is exactly what a NONE QUALIFYING verdict must name.
        report("pull-up NEAR-MISS (breadth cleared, wide thresholds not)",
            candidates.filterNot { it.qualifiesForPullUp() })
    }

    /**
     * Read the two change-signature cases' decoy counts back out of the index, in the same container
     * that just surveyed it.
     *
     * Both pins were arrived at by subtracting hand-counted implementers from a grepped total, and a
     * wrong `expectedDecoyDeclarations` aborts its case before the agent ever runs — so the number
     * has to come from the same code the capture uses. Reported, never asserted: this class prints
     * measurements for transcription, and a mismatch is a registry edit, not a broken harness.
     */
    private fun verifyPinnedDecoyCounts(session: IntelliJContainer) {
        val pinned = listOf(
            RippleCases.changeSignatureWideTarget to RippleCases.changeSignatureWide.expectedDecoyDeclarations,
            RippleCases.changeSignatureNarrowTarget to RippleCases.changeSignatureNarrow.expectedDecoyDeclarations,
        )
        val script = pinned.joinToString("\n") { (target, _) -> target.decoyCountFragment() }
        val output = session.mcpSteroid.mcpExecuteCode(
            code = RippleOracleScripts.preamble + "\n" + script,
            reason = "Verify the pinned change-signature decoy counts against the index",
            taskId = "ripple-decoy-verify",
            timeout = 1_800,
        ).stdout

        val measured = parseDecoyVerifications(output).associateBy { it.targetDescription }
        for ((target, pin) in pinned) {
            val found = measured[target.targetDescription]
                ?: error("No DECOY_VERIFY line for ${target.targetDescription}:\n$output")
            val verdict = if (found.decoys == pin) "MATCHES" else "MISMATCH — the pin would abort this case"
            println("[DECOY-VERIFY] ${target.targetDescription}: measured decoys=${found.decoys} " +
                "(same simple name=${found.sameSimpleName}), pinned=$pin — $verdict")
        }
    }

    companion object {
        /** Comma-separated [SurveyPhase.id] values; unset runs every phase. */
        const val SURVEY_PHASES_PROPERTY = "ripple.survey.phases"
    }
}
