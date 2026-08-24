/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The case's admission criteria, measured rather than argued, against a recorded shell trajectory.
 *
 * The ten files under `calibration/shell-simulation/` are the verbatim output of ten shell commands
 * run against the pinned checkout. They are not a simulation of a weak agent — they are the opposite,
 * a deliberately CHEATING one: the commands were written by someone who already knew the answer and
 * they walk straight to every gold file without a single wasted step. That is the point. If a
 * trajectory that cannot go wrong still fails to cover the checklist in three interactions, then no
 * real shell agent will either, and the case has the research depth criterion A asks for; and if this
 * trajectory DOES cover it, the case is a file-name lookup and no amount of arm comparison would mean
 * anything.
 *
 * Recorded and committed rather than executed here on purpose: a test that shells out to a checkout
 * that may or may not exist on the machine is an infrastructure dependency, and this repository does
 * not skip tests at runtime. The recordings are reproducible from the commands printed in their first
 * line.
 */
class AcquisitionCalibrationTest {

    /**
     * The cases that carry a recorded cheating trajectory, checked as a family.
     *
     * Every ARCHITECTURE case of a round has to pass all three shape criteria before it is queued, and
     * the criteria are the reason the round can be read at all: a case whose checklist is bought by
     * three commands measures nothing, and one whose checklist the shell cannot reach at forty measures
     * the ceiling. Keeping them in one data-driven check is how a case added later cannot skip them —
     * the previous shape of this file hard-coded the first case and would have said nothing about the
     * two that came after it.
     *
     * The controls are deliberately absent: the navigational one names its own target (three commands
     * SHOULD buy much of it), and the shallow one is here to be easy.
     */
    private val calibrated: List<AcquisitionChecklist> = listOf(
        CC_REFRESH_TOKEN_CHECKLIST,
        CLIENT_AUTH_METHOD_CHECKLIST,
        OAUTH_GRANT_TYPE_CHECKLIST,
    )

    private fun recordedShellResults(caseId: String): List<String> = (1..10).map { index ->
        val name = "acquisition-cases/$caseId/calibration/" +
            "shell-simulation/${index.toString().padStart(2, '0')}.txt"
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "the recorded shell calibration output $name is missing from the test resources"
        }.use { it.readBytes().decodeToString() }
    }

    @Test
    fun `three obvious commands do not buy the checklist`() = calibrated.forEach { checklist ->
        val results = recordedShellResults(checklist.caseId).take(3)
        val afterThree = checklist.observedScore(results)
        println(
            "[ACQUISITION-CALIBRATION] ${checklist.caseId}: research depth: U_obs after 3 optimal " +
                "shell commands = ${"%.2f".format(afterThree)} (${checklist.observedIds(results)})"
        )
        assertTrue(
            afterThree <= 0.35,
            "${checklist.caseId}: an agent that types the three most obvious commands already observes " +
                "${"%.2f".format(afterThree)} of the checklist, so the case is a file-name lookup and " +
                "cannot separate two ways of looking at a repository",
        )
    }

    @Test
    fun `the checklist is reachable from the shell, so the control arm is not handicapped`() =
        calibrated.forEach { checklist ->
            val results = recordedShellResults(checklist.caseId)
            val afterTen = checklist.observedScore(results)
            println(
                "[ACQUISITION-CALIBRATION] ${checklist.caseId}: reachability: U_obs after 10 optimal " +
                    "shell commands = ${"%.2f".format(afterTen)} (missing: " +
                    "${checklist.facts.map { it.id } - checklist.observedIds(results).toSet()})"
            )
            assertTrue(
                afterTen >= 0.60,
                "${checklist.caseId}: ten commands aimed straight at the answer observe only " +
                    "${"%.2f".format(afterTen)} of the checklist. Either the detectors are broken or the " +
                    "checklist asks for something the shell arm cannot reach at all, and a curve drawn " +
                    "against an unreachable ceiling would measure the ceiling",
            )
        }

    @Test
    fun `the residual-work denominator is the number of assertions the oracle really makes`() {
        val case = AcquisitionCases.ccRefreshToken
        val declared = Regex("""^\+\s*@Test\b""", RegexOption.MULTILINE)
            .findAll(case.oracleTestPatch()).count()
        assertTrue(
            declared == case.oracleTestCount,
            "the oracle patch declares $declared assertions and the case says ${case.oracleTestCount}. " +
                "Every residual-work reading of this round is a fraction of that number, so the two " +
                "drifting apart would rescale the whole scatter plot without failing anything",
        )
    }

    @Test
    fun `the oracle finds the implementation without naming it and without either registration`() {
        val patch = AcquisitionCases.ccRefreshToken.oracleTestPatch()
        // Naming the gold class would make the residual count a measurement of whether the agent
        // guessed the same identifier, and would let a note leak the answer through the file path.
        assertTrue(
            !patch.contains("RejectClientCredentials"),
            "the oracle names the gold implementation, so it grades identifiers rather than behaviour",
        )
        // The de-cascade itself: discovery by scanning the module's compiled classes, so that the
        // behavioural axes can pass on a tree that registered nothing. Without this the scale collapses
        // back to `{0} u {5..9}`, which is what the first downstream wave was graded on.
        assertTrue(patch.contains("getProtectionDomain"), "discovery must not go through the profile JSON")
        assertTrue(patch.contains("ClientPolicyExecutorProvider"), patch.take(200))

        // The superseded oracle stays on disk as the provenance of the first wave's numbers. A round
        // whose grader has been silently replaced cannot explain its own history.
        assertTrue(
            AcquisitionCases.ccRefreshToken.oracleTestPatchResource?.endsWith("oracle-v2.patch") == true,
            "the case must grade against the de-cascaded oracle, and it must still HAVE one: the " +
                "resource is nullable now that research-only cases exist, and null here would mean " +
                "this case had quietly lost the downstream half of its history",
        )
        assertTrue(
            javaClass.classLoader
                .getResource("acquisition-cases/acquisition__keycloak__cc-refresh-token/oracle.patch") != null,
            "the first wave's oracle must remain readable beside the one that replaced it",
        )
    }

    @Test
    fun `the growth between three and ten commands is where the case lives`() =
        calibrated.forEach { checklist ->
            val results = recordedShellResults(checklist.caseId)
            val afterThree = checklist.observedScore(results.take(3))
            val afterTen = checklist.observedScore(results)
            assertTrue(
                afterTen - afterThree >= 0.30,
                "${checklist.caseId}: the checklist barely moves between the third and the tenth " +
                    "interaction (${"%.2f".format(afterThree)} -> ${"%.2f".format(afterTen)}), so there " +
                    "is no region in which an acquisition curve could have a shape",
            )
        }
}
