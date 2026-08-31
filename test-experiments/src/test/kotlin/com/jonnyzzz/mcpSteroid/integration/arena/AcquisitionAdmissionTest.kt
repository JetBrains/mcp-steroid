/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `two cases separate their floor from their ceiling, and the third is blocked by its own readings`() {
        // Every case ran three gold-note and three no-note rollouts at its own allowance on
        // 2026-08-28, with a repair turn that could finally act on the tree. One case came back
        // measurable and two did not, and the difference is not the allowance: it is whether the
        // no-note solver, once helped to a compiling tree, knows where the change belongs.
        val allowances = mapOf(
            "acquisition__keycloak__cc-refresh-token" to 15,
            "acquisition__keycloak__client-auth-method" to 15,
            "acquisition__keycloak__oauth-grant-type" to 25,
        )
        assertEquals(allowances.keys, ACQUISITION_CASE_ADMISSIONS.keys)
        for ((caseId, allowance) in allowances) {
            assertEquals(
                allowance,
                ACQUISITION_CASE_ADMISSIONS.getValue(caseId).solverAllowance,
                "$caseId runs at a different allowance",
            )
        }

        for (caseId in allowances.keys) {
            val record = ACQUISITION_CASE_ADMISSIONS.getValue(caseId)
            val case = AcquisitionCases.byId(caseId)
            val problems = record.problems(case)
            println("[ACQUISITION-ADMISSION] $caseId at allowance ${record.solverAllowance}: " +
                if (problems.isEmpty()) "ADMITTED" else "blocked by ${problems.size}")
            problems.forEach { println("[ACQUISITION-ADMISSION]   - $it") }
        }

        val cc = AcquisitionCases.byId("acquisition__keycloak__cc-refresh-token")
        val ccRecord = ACQUISITION_CASE_ADMISSIONS.getValue(cc.instanceId)
        assertTrue(
            ccRecord.problems(cc).isEmpty(),
            "cc-refresh-token should be admitted; blocked by ${ccRecord.problems(cc)}",
        )
        requireAcquisitionAdmission(cc)
        // The ceiling every time, without a single repair round, and a floor that stays at the
        // obligation an untouched tree already satisfies even when the repair carries it to a build.
        assertEquals(
            List(3) { cc.oracleTestCount },
            ccRecord.goldNoteRollouts.map { it.endpointScore },
            "the gold note must reach the ceiling in all three rollouts",
        )
        assertEquals(listOf(0, 1, 0), ccRecord.baselineRollouts.map { it.endpointScore })
        ccRecord.baselineRollouts.forEach {
            assertNotNull(it.compiled, "${it.buildId} must carry a compile verdict")
        }

        // `client-auth-method` stays blocked, and by a different half of the rule than the one
        // `oauth-grant-type` was blocked by: its ceiling wobbles, which no re-weighting of the endpoint
        // can fix, because the weak solver holding the gold note cannot finish the implementation the
        // remaining axes ask for.
        val clientAuth = AcquisitionCases.byId("acquisition__keycloak__client-auth-method")
        val clientAuthProblems = ACQUISITION_CASE_ADMISSIONS.getValue(clientAuth.instanceId)
            .problems(clientAuth)
        assertTrue(
            clientAuthProblems.any { "1044788450 scored 6 of 9" in it },
            "the gold note that missed the reachability floor must block: $clientAuthProblems",
        )
        assertTrue(
            clientAuthProblems.any { "1044788456 scored 5 of 9 with NO note" in it },
            "the rescued no-note tree must block: $clientAuthProblems",
        )

        // Re-weighted to `oracle-v2`: the six axes a compiling implementation does not discharge. Every
        // reading this case had was taken on the ten-axis contract, so it carries NO rollout and NO
        // measured rung until fresh cells are bought — and it must be blocked for exactly that reason,
        // naming every cell that lifts the block, rather than for the floor it used to fail.
        val oauth = AcquisitionCases.oauthGrantType
        val oauthRecord = ACQUISITION_CASE_ADMISSIONS.getValue(oauth.instanceId)
        val oauthProblems = oauthRecord.problems(oauth)
        assertEquals(6, oauth.oracleTestCount, "the re-weighted endpoint scores six obligations")
        // The ceiling is reachable by the weak solver holding the gold note, three of three, on the
        // re-weighted scale — which is what round 6 could not show here, because four of its ten axes
        // were passed with no note at all.
        assertEquals(
            List(3) { 6 },
            oauthRecord.goldNoteRollouts.map { it.endpointScore },
            "the gold note must reach the six-axis ceiling in all three rollouts",
        )
        oauthRecord.goldNoteRollouts.forEach {
            assertEquals(true, it.compiled, "${it.buildId} must carry a compile verdict")
        }
        // And the floor, three of three, every tree carried to a build so no reading is a `javac`
        // failure wearing a zero: two of them score both traps and nothing else, the third does not
        // even produce a factory. Against a pristine floor of 1 and `BASELINE_SLACK` of 1, admissible —
        // and the gold note buys four of the six obligations over having none.
        assertEquals(listOf(2, 2, 1), oauthRecord.baselineRollouts.map { it.endpointScore })
        oauthRecord.baselineRollouts.forEach {
            assertEquals(true, it.compiled, "${it.buildId} must carry a compile verdict")
        }
        assertTrue(
            oauthProblems.isEmpty(),
            "the re-weighted endpoint separates its floor from its ceiling: $oauthProblems",
        )
        requireAcquisitionAdmission(oauth, oauthRecord.solverAllowance)
        // The ceiling of the new scale IS replayed: build 1046476916 deployed the whole gold patch and
        // the six retained axes all passed, which also proves `oracle-v2` compiles — the type check
        // that could not be done anywhere cheaper than a graded cell.
        val ceiling = oauthRecord.rungs.last()
        assertEquals("gold", ceiling.name)
        assertEquals(6, ceiling.measuredObligations)
        assertEquals("1046476916", ceiling.measuredIn)
        assertTrue(
            oauthProblems.none { "the ceiling has never been replayed" in it },
            "the ceiling is measured and must stop blocking: $oauthProblems",
        )
        // Re-measured on the new scale: the registration rung reads four and loses exactly the two axes
        // the ServiceLoader line flips, which is what makes the six-point scale a scale here.
        val implOnly = oauthRecord.rungs.first { it.name == "implementation-only" }
        assertEquals(4, implOnly.measuredObligations)
        assertEquals(implOnly.losesAxes.toSet(), implOnly.measuredAxes?.toSet())
        // And the invariant rung reads five, losing only the uniqueness axis: three trees, three
        // different subsets of obligations, which is a scale rather than one boolean wearing six names.
        val naive = oauthRecord.rungs.first { it.name == "naive-shortcut" }
        assertEquals(5, naive.measuredObligations)
        assertEquals(naive.losesAxes.toSet(), naive.measuredAxes?.toSet())
        assertEquals(
            listOf(4, 5, 6),
            oauthRecord.rungs.map { it.measuredObligations },
            "the measured ladder must climb from the registration rung to the ceiling",
        )
    }

    @Test
    fun `a note cell queued at another case's allowance is refused before a container starts`() {
        // The one case a wave can be bought on, since a blocked case is refused whatever number it is
        // queued at and would prove nothing about the allowance guard.
        val case = AcquisitionCases.byId("acquisition__keycloak__cc-refresh-token")
        val record = ACQUISITION_CASE_ADMISSIONS.getValue(case.instanceId)
        // Its own allowance passes.
        requireAcquisitionAdmission(case, record.solverAllowance)
        // Any other pre-registered number does not: the floor and ceiling this case was admitted on
        // were measured at 15, and a wave at 20 or 25 would be graded against readings that do not
        // exist while looking like an ordinary row in the table. This case has such readings — at 25
        // its floor read 4 and 7 of 9 — so the mix-up is a live one, not a hypothetical.
        for (wrong in ACQUISITION_DOWNSTREAM_BUDGETS.filter { it != record.solverAllowance }) {
            val thrown = assertThrows<IllegalStateException> { requireAcquisitionAdmission(case, wrong) }
            assertTrue("calibrated at an allowance of 15" in thrown.message.orEmpty(), thrown.message.orEmpty())
        }
    }

    @Test
    fun `a note cell may be queued at a floor-probe allowance, and only at one`() {
        val case = AcquisitionCases.byId("acquisition__keycloak__cc-refresh-token")
        val record = ACQUISITION_CASE_ADMISSIONS.getValue(case.instanceId)
        // A probe cell is the deliberate off-calibration reading: it IS graded against a floor that does
        // not exist yet, because it is queued together with the cells that measure one. The guard it is
        // exempt from protects against something else — a wave quietly run at an uncalibrated number.
        for (probe in ACQUISITION_FLOOR_PROBE_BUDGETS) {
            requireAcquisitionAdmission(case, probe)
        }
        // What makes the exemption safe is that no probe allowance can ever be mistaken for a wave
        // setting. If the two sets ever overlapped, this exemption would silently reopen the door the
        // test above closes, and nothing in a build log would show it.
        assertTrue(
            ACQUISITION_FLOOR_PROBE_BUDGETS.none { it in ACQUISITION_DOWNSTREAM_BUDGETS },
            "a probe allowance that is also a wave setting would let a wave through the probe exemption",
        )
        // And the exemption is exactly that wide: a number belonging to neither set is still refused.
        val unlisted = assertThrows<IllegalStateException> { requireAcquisitionAdmission(case, 45) }
        assertTrue("calibrated at an allowance of 15" in unlisted.message.orEmpty(), unlisted.message.orEmpty())
        assertTrue(
            ACQUISITION_FLOOR_PROBE_BUDGETS.sorted().toString() in unlisted.message.orEmpty(),
            "the refusal must name the probe allowances, or an operator cannot tell why 45 differs " +
                "from 40: ${unlisted.message}",
        )
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
            4,
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
