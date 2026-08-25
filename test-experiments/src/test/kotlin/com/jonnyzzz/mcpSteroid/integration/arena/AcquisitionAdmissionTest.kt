/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

/**
 * The offline half of the case-admission protocol: everything about it that can be established without
 * a container.
 *
 * Worth having as tests rather than as a document, because the protocol's whole claim is that it CANNOT
 * be talked around. A rule that lives in prose is a rule an operator remembers differently at midnight
 * with a queue half-bought; three rounds of this experiment are the evidence.
 */
class AcquisitionAdmissionTest {

    @Test
    fun `a rung cut from the gold patch keeps exactly the files it names`() {
        val gold = AcquisitionCases.ccRefreshToken.goldPatch()
        val executor = "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
            "RejectClientCredentialsRefreshTokenExecutor.java"
        val factory = "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
            "RejectClientCredentialsRefreshTokenExecutorFactory.java"

        val cut = filterPatchToPaths(gold, listOf(executor, factory))

        assertEquals(setOf(executor, factory), extractPatchFilePaths(cut))
        // Byte-for-byte, not merely path-for-path: the point of cutting a rung out of the gold instead
        // of maintaining a separate artifact is that the rung cannot drift from the reference
        // implementation. A filter that re-serialized the hunks would give that up quietly.
        assertTrue(cut in gold || gold.contains(cut.trimEnd()), "the cut must be a slice of the gold")
        assertTrue(
            "keycloak-default-client-profiles.json" !in cut,
            "the profile JSON is not in this rung and must not travel with it",
        )
    }

    @Test
    fun `cutting a rung refuses a path the gold never touches`() {
        val gold = AcquisitionCases.ccRefreshToken.goldPatch()
        // Not a typo-catcher for its own sake: a rung that silently deployed three of its four files
        // would be MEASURED, published, and read as a statement about a tree nobody built.
        assertThrows<IllegalStateException> {
            filterPatchToPaths(gold, listOf("services/src/main/java/org/keycloak/Nonexistent.java"))
        }
    }

    @Test
    fun `every rung of every case can be materialised, or says why not`() {
        for ((caseId, admission) in ACQUISITION_CASE_ADMISSIONS) {
            val case = AcquisitionCases.byId(caseId)
            val gold = case.goldPatch()
            for (rung in admission.rungs) {
                if (rung.patchResource != null) {
                    if (!rung.patchResourceExists) {
                        // Declared but not yet exported. A legitimate state — the rung is a work item
                        // the admission gate names — and the only thing that must not happen is for it
                        // to be silently skipped.
                        val problems = admission.problems(case)
                        assertTrue(
                            problems.isNotEmpty(),
                            "$caseId declares the unexported rung `${rung.name}` yet reports no problem",
                        )
                        continue
                    }
                    // Exported: it has to materialise, and it has to differ from the gold. An invariant
                    // trap is NOT a subset of the gold — "the whole change with the neighbour's shortcut
                    // reused" touches the same files — so the file-count check below cannot apply to it,
                    // and demanding it would force the trap to be written as something it is not.
                    val trap = rung.patch(gold)
                    assertTrue(trap.isNotBlank(), "$caseId rung `${rung.name}` materialised to nothing")
                    assertTrue(
                        trap != gold,
                        "$caseId rung `${rung.name}` is byte-identical to the gold, so it is the ceiling " +
                            "under another name and measures nothing",
                    )
                    continue
                }
                val patch = rung.patch(gold)
                assertTrue(patch.isNotBlank(), "$caseId rung `${rung.name}` materialised to nothing")
                if (rung.isWholeGold) {
                    assertEquals(gold, patch, "$caseId ceiling rung must be the gold patch itself")
                } else {
                    assertTrue(
                        extractPatchFilePaths(patch).size < extractPatchFilePaths(gold).size,
                        "$caseId rung `${rung.name}` is not partial",
                    )
                }
            }
        }
    }

    @Test
    fun `every case is admitted, each at the allowance its own floor and ceiling asked for`() {
        // All three, and the allowance is per case because the pre-registered calibration rule is per
        // case. Applied honestly to each case's own readings it gave two different answers: tighten to
        // 15 where a no-note cell reached 5 and 6 of 9, loosen to 25 where the gold note reached the
        // ceiling but no note the experiment can produce did.
        val expected = mapOf(
            "acquisition__keycloak__cc-refresh-token" to 15,
            "acquisition__keycloak__client-auth-method" to 15,
            "acquisition__keycloak__oauth-grant-type" to 25,
        )
        assertEquals(expected.keys, ACQUISITION_CASE_ADMISSIONS.keys)

        for ((caseId, allowance) in expected) {
            val record = ACQUISITION_CASE_ADMISSIONS.getValue(caseId)
            val case = AcquisitionCases.byId(caseId)
            val problems = record.problems(case)
            println("[ACQUISITION-ADMISSION] $caseId at allowance ${record.solverAllowance}: " +
                if (problems.isEmpty()) "ADMITTED" else "blocked by ${problems.size}")
            problems.forEach { println("[ACQUISITION-ADMISSION]   - $it") }

            assertEquals(allowance, record.solverAllowance, "$caseId runs at a different allowance")
            assertTrue(problems.isEmpty(), "$caseId should be admitted; blocked by $problems")
            requireAcquisitionAdmission(case)

            // The separation every one of them was re-anchored to produce: the ceiling every time, the
            // floor never. Zero variance in both groups is what makes a twelve-note wave readable.
            assertEquals(
                List(3) { case.oracleTestCount },
                record.goldNoteRollouts.map { it.endpointScore },
                "$caseId's gold note must reach the ceiling in all three rollouts",
            )
            assertEquals(
                List(2) { 0 },
                record.baselineRollouts.map { it.endpointScore },
                "$caseId's no-note cells must all sit on the floor",
            )
            // And the floor is a MEASURED zero, not an unrecorded one: every baseline carries a compile
            // verdict, and its raw obligation count stays null so the endpoint decision remains visible.
            record.baselineRollouts.forEach {
                assertEquals(false, it.compiled, "${it.buildId} must carry a compile verdict")
                assertNull(it.obligations, "${it.buildId} must not invent an obligation count")
            }
        }
    }

    @Test
    fun `a note cell queued at another case's allowance is refused before a container starts`() {
        val case = AcquisitionCases.oauthGrantType
        val record = ACQUISITION_CASE_ADMISSIONS.getValue(case.instanceId)
        // Its own allowance passes.
        requireAcquisitionAdmission(case, record.solverAllowance)
        // Any other pre-registered number does not: the floor and ceiling this case was admitted on
        // were measured at 25, and a wave at 15 or 20 would be graded against readings that do not
        // exist while looking like an ordinary row in the table.
        for (wrong in ACQUISITION_DOWNSTREAM_BUDGETS.filter { it != record.solverAllowance }) {
            val thrown = assertThrows<IllegalStateException> { requireAcquisitionAdmission(case, wrong) }
            assertTrue("calibrated at an allowance of 25" in thrown.message.orEmpty(), thrown.message.orEmpty())
        }
    }

    @Test
    fun `every case with a pre-registered note matrix has an admission record`() {
        // Otherwise the matrix is the only thing standing between a queue and a wave, and a matrix
        // says WHICH cells to buy, never whether they can measure anything.
        for (caseId in ACQUISITION_DOWNSTREAM_MATRICES.keys) {
            assertTrue(
                caseId in ACQUISITION_CASE_ADMISSIONS,
                "$caseId has a note matrix but no admission record",
            )
        }
    }

    @Test
    fun `a fully calibrated case is admitted, and each missing piece alone blocks it`() {
        val case = admissibleCase()
        val admission = admissibleAdmission()
        assertEquals(emptyList<String>(), admission.problems(case))

        // One violation at a time, because a gate that only fires when everything is wrong is a gate
        // that never fires.
        assertBlocked(
            "an unmeasured ceiling",
            admission.copy(rungs = admission.rungs.dropLast(1) + CEILING.copy(measuredObligations = null)),
            case,
        )
        assertBlocked(
            "a gold that misses its own ceiling",
            admission.copy(rungs = admission.rungs.dropLast(1) + CEILING.copy(measuredObligations = 8)),
            case,
        )
        assertBlocked(
            "one rung only",
            admission.copy(rungs = listOf(admission.rungs.first(), CEILING)),
            case,
        )
        assertBlocked(
            "two rungs losing the same obligations",
            admission.copy(
                rungs = listOf(
                    admission.rungs[0],
                    admission.rungs[1].copy(
                        losesAxes = admission.rungs[0].losesAxes,
                        measuredAxes = admission.rungs[0].measuredAxes,
                    ),
                    CEILING,
                ),
            ),
            case,
        )
        // The reading amendment 1 exists for: same count, different obligation. It must NOT block,
        // because that is a scale, not a cascade — `cc-refresh-token` really is shaped that way.
        assertEquals(
            emptyList<String>(),
            admission.copy(
                rungs = listOf(
                    admission.rungs[0].copy(
                        expectedObligations = 7,
                        measuredObligations = 7,
                        losesAxes = listOf("someOtherAxis"),
                        measuredAxes = listOf("someOtherAxis"),
                    ),
                    admission.rungs[1],
                    CEILING,
                ),
            ).problems(case),
            "two rungs that cost the same but lose DIFFERENT obligations are a scale, not a cascade",
        )
        assertBlocked(
            "a rung measured before anyone recorded which obligations it loses",
            admission.copy(
                rungs = listOf(
                    admission.rungs[0].copy(measuredAxes = null),
                    admission.rungs[1],
                    CEILING,
                ),
            ),
            case,
        )
        assertBlocked(
            "a rung that loses obligations other than the ones predicted",
            admission.copy(
                rungs = listOf(
                    admission.rungs[0].copy(measuredAxes = listOf("anEntirelyDifferentAxis")),
                    admission.rungs[1],
                    CEILING,
                ),
            ),
            case,
        )
        assertBlocked(
            "a rung that measured something else than predicted",
            admission.copy(
                rungs = listOf(
                    admission.rungs[0].copy(measuredObligations = 5),
                    admission.rungs[1],
                    CEILING,
                ),
            ),
            case,
        )
        assertBlocked(
            "two gold-note rollouts",
            admission.copy(goldNoteRollouts = admission.goldNoteRollouts.take(2)),
            case,
        )
        assertBlocked(
            "a gold-note rollout that did not compile",
            admission.copy(
                goldNoteRollouts = admission.goldNoteRollouts.dropLast(1) +
                    AcquisitionRolloutEvidence("x", obligations = null, compiled = false),
            ),
            case,
        )
        assertBlocked(
            "a gold-note rollout that scored low",
            admission.copy(
                goldNoteRollouts = admission.goldNoteRollouts.dropLast(1) +
                    AcquisitionRolloutEvidence("x", obligations = 3, compiled = true),
            ),
            case,
        )
        assertBlocked(
            "one baseline",
            admission.copy(baselineRollouts = admission.baselineRollouts.take(1)),
            case,
        )
        assertBlocked(
            "a baseline that solved the task on its own",
            admission.copy(
                baselineRollouts = admission.baselineRollouts.dropLast(1) +
                    AcquisitionRolloutEvidence("x", obligations = 7, compiled = true),
            ),
            case,
        )
        assertBlocked(
            "a grading build that skips the dependency closure",
            admission,
            case.copy(gradingBuildsDependencyClosure = false),
        )
        assertBlocked("a case with no gold patch", admission, case.copy(goldPatchResource = null))
    }

    @Test
    fun `a ladder rung is queued by name and refuses an unknown one`() {
        val condition = understandingConditionOf("${LADDER_CONDITION_PREFIX}implementation-only")
        assertTrue(condition is AcquisitionLadderCondition)
        assertEquals("ladder-implementation-only", condition.label)
        assertEquals(
            8,
            acquisitionLadderRung(AcquisitionCases.oauthGrantType, "implementation-only")
                .expectedObligations,
        )
        assertThrows<IllegalStateException> {
            acquisitionLadderRung(AcquisitionCases.oauthGrantType, "implementation-and-spi")
        }
        assertThrows<IllegalStateException> { understandingConditionOf(LADDER_CONDITION_PREFIX) }
        // The three older shapes must keep meaning exactly what they meant.
        assertTrue(understandingConditionOf("baseline") is UnderstandingCondition.Baseline)
        assertTrue(understandingConditionOf("oracle:gold") is UnderstandingCondition.Oracle)
    }

    @Test
    fun `the grading build can be told to rebuild the dependency closure`() {
        assertEquals("-pl :keycloak-services", mavenProjectScopeFlag(":keycloak-services"))
        assertEquals(
            "-pl :keycloak-services -am",
            mavenProjectScopeFlag(":keycloak-services", alsoMakeDependencies = true),
        )
        // Never `-am` alone: without a `-pl` it means nothing, and the whole-reactor callers must keep
        // their command line byte for byte.
        assertEquals("", mavenProjectScopeFlag(null, alsoMakeDependencies = true))
    }

    @Test
    fun `a compile failure is read as its own diagnostic, and a failing test is not`() {
        val compileFailure = """
            [INFO] --- compiler:3.13.0:compile (default-compile) @ keycloak-services ---
            [INFO] Compiling 12 source files
            [INFO] -------------------------------------------------------------
            [ERROR] COMPILATION ERROR :
            [INFO] -------------------------------------------------------------
            [ERROR] /work/services/src/main/java/org/keycloak/Grants.java:[31,44] cannot find symbol
              symbol:   variable OFFLINE_REFRESH
            [ERROR] /work/services/src/main/java/org/keycloak/Grants.java:[40,9] cannot find symbol
            [INFO] 2 errors
            [INFO] BUILD FAILURE
        """.trimIndent()
        val testFailure = """
            [ERROR] Failures:
            [ERROR]   ResidualContractTest.theShippedProfileListsIt:295 expected:<1> but was:<0>
            [INFO] BUILD FAILURE
        """.trimIndent()

        assertEquals(2, mavenCompilationDiagnostics(compileFailure).size)
        assertTrue(mavenCompilationFailed(compileFailure))
        // The distinction the third round did not have: `BUILD FAILURE` is what a run with merely
        // failing assertions prints too, and reading it as a compile failure would move every honest
        // zero into the unmeasured column.
        assertEquals(emptyList<String>(), mavenCompilationDiagnostics(testFailure))
        assertFalse(mavenCompilationFailed(testFailure))
    }

    @Test
    fun `obligations of a tree that did not compile are unmeasured, not zero`() {
        val case = AcquisitionCases.ccRefreshToken
        val classResult = SurefireClassResult(case.failToPass.first(), testsRun = 9, failures = 2, errors = 0, skipped = 0)

        val compiled = verificationOf(listOf(classResult), diagnostics = emptyList())
        assertEquals(7, oracleAssertionsPassed(compiled, case))
        assertEquals(true, compiled.compiled)

        val broken = verificationOf(emptyList(), diagnostics = listOf("[ERROR] X.java:[1,1] cannot find symbol"))
        assertNull(
            oracleAssertionsPassed(broken, case),
            "a tree that never built has an unknown number of satisfied obligations",
        )
        assertEquals(false, broken.compiled)

        // A result carrying no build evidence at all stays unknown-compiled but keeps its old reading,
        // so every older round's numbers are reproduced byte for byte.
        val legacy = ArenaVerificationResult(
            perClass = listOf(classResult),
            failToPassTampered = false,
            collateralTestFilesEdited = emptyList(),
            regressions = emptyList(),
            baselineAvailable = false,
            verificationDurationMs = 1,
        )
        assertNull(legacy.compiled)
        assertEquals(7, oracleAssertionsPassed(legacy, case))
    }

    @Test
    fun `the cell line says unmeasured rather than zero when nothing compiled`() {
        val line = acquisitionDownstreamLine(
            caseId = "acquisition__keycloak__oauth-grant-type",
            condition = AcquisitionCheckpointNote("mcp-b40-l2000-r1", 10),
            replicate = 1,
            outcome = UnderstandingDownstreamOutcome(
                success = false,
                verdict = "Y=0",
                oracleTestsPassed = null,
                oracleTestsTotal = 10,
                compiled = false,
                cost = UnderstandingCellCost(null, null, null),
            ),
        )
        assertTrue(line.contains("oraclePassed=unmeasured/10"), line)
        assertTrue(line.contains("residual=unmeasured"), line)
        assertTrue(line.contains("compiled=0"), line)
    }

    @Test
    fun `the report says WHICH obligations failed, not only how many`() {
        // The reading that tells `implementation-and-spi` from `naive-partial-update`: both cost one
        // assertion of nine, and only the names say they are two different assertions.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="org.example.ResidualContractTest" tests="3" failures="1" errors="1" skipped="0">
              <testcase name="theShippedStrictProfileListsIt" classname="org.example.ResidualContractTest"/>
              <testcase name="partialUpdateOfAClientThatAlreadyHasItOnIsRejected" classname="org.example.ResidualContractTest">
                <failure message="Expected a ClientPolicyException on event UPDATE">boom</failure>
              </testcase>
              <testcase name="registerWithTheSettingOnIsRejected" classname="org.example.ResidualContractTest">
                <error message="NullPointerException">boom</error>
              </testcase>
            </testsuite>
        """.trimIndent()

        val suite = parseSurefireXml(xml)
        assertEquals(
            listOf("partialUpdateOfAClientThatAlreadyHasItOnIsRejected", "registerWithTheSettingOnIsRejected"),
            suite.failedMethods,
            "a failure and an error are both unmet obligations and must both be named",
        )
        assertEquals(suite.failedMethods, verificationOf(listOf(suite), emptyList()).failedAxes)

        // A green suite names nothing, so a ceiling rung cannot be mistaken for a partial one.
        val green = parseSurefireXml(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="org.example.ResidualContractTest" tests="1" failures="0" errors="0" skipped="0">
              <testcase name="everythingHolds" classname="org.example.ResidualContractTest"/>
            </testsuite>
            """.trimIndent()
        )
        assertEquals(emptyList<String>(), green.failedMethods)
    }

    private fun assertBlocked(
        what: String,
        admission: AcquisitionCaseAdmission,
        case: UnderstandingCase,
    ) {
        assertTrue(
            admission.problems(case).isNotEmpty(),
            "$what must block a note wave, and does not",
        )
    }

    private fun verificationOf(
        perClass: List<SurefireClassResult>,
        diagnostics: List<String>,
    ) = ArenaVerificationResult(
        perClass = perClass,
        failToPassTampered = false,
        collateralTestFilesEdited = emptyList(),
        regressions = emptyList(),
        baselineAvailable = false,
        verificationDurationMs = 1,
        compilationDiagnostics = diagnostics,
        gradingExitCode = if (diagnostics.isEmpty()) 0 else 1,
    )

    private fun admissibleCase(): UnderstandingCase = UnderstandingCase(
        instanceId = "acquisition__keycloak__probe",
        problemStatement = "behaviour only",
        oracleTestPatchResource = "acquisition-cases/acquisition__keycloak__cc-refresh-token/oracle-v2.patch",
        failToPass = listOf("org.example.ProbeTest"),
        oracleTestCount = 9,
        gradingScopeSelector = ":keycloak-services",
        gradingBuildsDependencyClosure = true,
        goldPatchResource = "acquisition-cases/acquisition__keycloak__cc-refresh-token/gold.patch",
        statementLeakageTokens = mapOf("strict" to 592),
        precedentPaths = listOf("services/src/main/java/Whatever.java"),
        goldRolePaths = mapOf("behaviour" to listOf("services/src/main/java/Whatever.java")),
    )

    private fun admissibleAdmission(): AcquisitionCaseAdmission = AcquisitionCaseAdmission(
        caseId = "acquisition__keycloak__probe",
        pristineFloor = 1,
        rungs = listOf(
            AcquisitionPartialRung(
                name = "implementation-only",
                goldPaths = listOf(
                    "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
                        "RejectClientCredentialsRefreshTokenExecutor.java",
                ),
                expectedObligations = 4,
                measuredObligations = 4,
                losesAxes = listOf("registeredThroughTheSpi", "listedInTheShippedProfile"),
                measuredAxes = listOf("registeredThroughTheSpi", "listedInTheShippedProfile"),
                isolates = "the registrations",
            ),
            AcquisitionPartialRung(
                name = "implementation-and-spi",
                goldPaths = listOf(
                    "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
                        "RejectClientCredentialsRefreshTokenExecutor.java",
                    "services/src/main/resources/META-INF/services/" +
                        "org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory",
                ),
                expectedObligations = 7,
                measuredObligations = 7,
                losesAxes = listOf("listedInTheShippedProfile"),
                measuredAxes = listOf("listedInTheShippedProfile"),
                isolates = "the second mechanism",
            ),
            CEILING,
        ),
        solverAllowance = ACQUISITION_DOWNSTREAM_BUDGET,
        goldNoteRollouts = List(3) { AcquisitionRolloutEvidence("g$it", obligations = 9, compiled = true) },
        baselineRollouts = List(2) { AcquisitionRolloutEvidence("b$it", obligations = 1, compiled = true) },
    )

    private companion object {
        val CEILING = AcquisitionPartialRung(
            name = "gold",
            goldPaths = emptyList(),
            expectedObligations = 9,
            measuredObligations = 9,
            isolates = "nothing — this is the ceiling",
        )
    }
}
