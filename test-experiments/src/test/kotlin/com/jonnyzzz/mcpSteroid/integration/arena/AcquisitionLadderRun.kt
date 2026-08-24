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
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver

/**
 * One rung of the calibration ladder: a deliberately incomplete tree, graded exactly like a solver's.
 *
 * No agent, no note, no model tokens — the cell deploys a patch and reads the oracle. That is what
 * makes the ladder affordable enough to demand of every case: the expensive part of a downstream wave
 * is the solver, and the question a rung answers has nothing to do with one.
 *
 * The question is whether the oracle MEASURES. Round 2 shipped nine assertions that were one assertion
 * — they all discovered the change through a single line of a JSON file, so a tree missing that line
 * scored zero no matter how much of the work it had done. Nothing about reading the oracle's source
 * reveals that; only building trees that are wrong in known, different ways and watching the number
 * move does. A ladder whose rungs land on the same count has told you the scale is a lie, for the
 * price of two container starts.
 *
 * The grading path is deliberately the SOLVER'S path, down to [ArenaVerifier.verify] and the case's
 * own scope selector: a ladder measured through a different build would certify a build nobody is
 * graded by. That is the third round's lesson in miniature — its gold reached the ceiling through a
 * grading scope that no imitation of the repository's own precedent could survive.
 */
fun runAcquisitionLadderCell(
    case: UnderstandingCase,
    rung: AcquisitionPartialRung,
    replicate: Int,
): AcquisitionLadderOutcome {
    check(case.gradable) {
        "'${case.instanceId}' has no hidden oracle, so a ladder rung of it would grade nothing"
    }
    val patch = rung.patch(case.goldPatch())
    val patchedPaths = extractPatchFilePaths(patch)
    println(
        "[ACQUISITION-LADDER] cell case=${case.instanceId} rung=${rung.name} replicate=$replicate " +
            "expected=${rung.expectedObligations}/${case.oracleTestCount} files=${patchedPaths.size} " +
            "isolates=${rung.isolates}"
    )

    val lifetime = CloseableStackHost()
    try {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "acq-ladder-${rung.name}".take(40),
            project = IntelliJProject.ProjectFromGitCommitAndPatch(
                cloneUrl = case.cloneUrl,
                repoOwnerAndName = case.repoOwnerAndName,
                baseCommit = case.baseCommit,
                testPatch = "",
                displayName = case.instanceId,
                buildSystem = "maven",
            ),
            aiMode = AiMode.NONE,
            mcpConnectionMode = McpConnectionMode.None,
            mountDockerSocket = false,
        )).waitForProjectReady(
            timeoutMillis = case.projectReadyTimeoutMs,
            projectJdkVersion = case.projectJdkVersion,
            buildSystem = BuildSystem.MAVEN,
            compileProject = true,
            requireCleanCompile = false,
        )

        val projectDir = session.intellijDriver.getGuestProjectDir()
        if (case.needsReactorInstall) {
            installReactorWithNetworkRetries(session.scope, projectDir)
        }

        val git = GitDriver(session.scope)
        // Loud, not lenient. A rung whose patch does not apply is a rung whose declaration has drifted
        // from the gold it is cut from, and grading the pristine tree instead would publish the FLOOR
        // under this rung's name — the one reading the ladder exists to place.
        git.applyPatch(projectDir, patch)
        val testCase = case.dpaiaCase()
        git.applyPatch(projectDir, testCase.testPatch)

        val verifier = ArenaVerifier(session.scope, projectDir, testCase.buildSystem)
        val verification = verifier.verify(
            failToPass = testCase.failToPass,
            projectJdkVersion = case.projectJdkVersion,
            testPatch = testCase.testPatch,
            // Applied by this cell moments ago and untouched since: there is no agent here to tamper
            // with anything, so the snapshot can only ever agree with itself. Taken because `verify`
            // refuses to grade without one.
            preAgentSnapshot = verifier.snapshotTestFiles(testCase.testPatch),
            baseline = null,
            mavenProjectSelector = case.gradingScopeSelector,
            mavenAlsoMakeDependencies = case.gradingBuildsDependencyClosure,
            purgeScopedBuildOutput = true,
        )

        val outcome = AcquisitionLadderOutcome(
            caseId = case.instanceId,
            rungName = rung.name,
            expectedObligations = rung.expectedObligations,
            measuredObligations = oracleAssertionsPassed(verification, case),
            totalObligations = case.oracleTestCount,
            compiled = verification.compiled,
        )
        println(acquisitionLadderLine(outcome, replicate))
        // Reported, never asserted. A rung that lands somewhere else is a FINDING about the oracle —
        // the two `oauth-grant-type` obligations that turned out to be one, say — and a cell that
        // threw would destroy the reading on its way out. The admission gate is where a disagreement
        // between prediction and measurement blocks a wave.
        if (outcome.measuredObligations != rung.expectedObligations) {
            println(
                "[ACQUISITION-LADDER] PREDICTION MISSED: rung `${rung.name}` was predicted at " +
                    "${rung.expectedObligations} and measured " +
                    "${outcome.measuredObligations ?: "unmeasured"}. Record the measurement in " +
                    "ACQUISITION_CASE_ADMISSIONS and decide there whether the ladder or the oracle is wrong"
            )
        }
        return outcome
    } finally {
        lifetime.closeAllStacks()
    }
}

/** What one ladder cell established about the oracle's scale. */
data class AcquisitionLadderOutcome(
    val caseId: String,
    val rungName: String,
    val expectedObligations: Int,
    /** Null when the rung's tree did not compile — see [oracleAssertionsPassed]. */
    val measuredObligations: Int?,
    val totalObligations: Int,
    val compiled: Boolean?,
)

/**
 * The one line an operator copies into [ACQUISITION_CASE_ADMISSIONS].
 *
 * Same shape as the downstream cell's line so both can be read out of a build log the same way, and
 * carrying the prediction beside the measurement so the two can never be compared from memory.
 */
fun acquisitionLadderLine(outcome: AcquisitionLadderOutcome, replicate: Int): String = buildString {
    append("[ACQUISITION-LADDER] case=${outcome.caseId} rung=${outcome.rungName} ")
    append("replicate=$replicate ")
    append("measured=${outcome.measuredObligations ?: "unmeasured"}/${outcome.totalObligations} ")
    append("expected=${outcome.expectedObligations}/${outcome.totalObligations}")
    outcome.compiled?.let { append(" compiled=${if (it) 1 else 0}") }
}
