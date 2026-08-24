/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The unit tests of the acquisition-curve instrument.
 *
 * Every one of them pins a rule that, if it were wrong, would produce a plausible curve rather than an
 * error — which is the only failure mode that matters here. The previous round shipped four separate
 * silent defects (an empty container command that "verified" a pristine tree, a parser reading the
 * console-filtered stream, a truncation treated as an edit, a build timeout sized for a warm agent),
 * and each of them looked exactly like data until somebody checked. These run in milliseconds and need
 * no container, so there is no excuse for the next one.
 */
class AcquisitionHarnessTest {

    private fun assistant(id: String, outputTokens: Int, vararg blocks: String): String =
        """{"type":"assistant","message":{"id":"$id","role":"assistant","model":"claude-opus-5",""" +
            """"content":[${blocks.joinToString(",")}],"usage":{"input_tokens":10,"output_tokens":$outputTokens}}}"""

    private fun textBlock(text: String): String = """{"type":"text","text":"$text"}"""

    private fun toolUse(id: String, name: String, input: String): String =
        """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""

    private fun toolResult(id: String, text: String, timestamp: String = "2026-08-22T10:00:00.000Z"): String =
        """{"type":"user","timestamp":"$timestamp","message":{"role":"user","content":""" +
            """[{"type":"tool_result","tool_use_id":"$id","content":"$text"}]}}"""

    /** The MCP tools answer with a list of typed blocks; the shell tools answer with a string. */
    private fun structuredToolResult(id: String, text: String): String =
        """{"type":"user","timestamp":"2026-08-22T10:00:05.000Z","message":{"role":"user","content":""" +
            """[{"type":"tool_result","tool_use_id":"$id","content":[{"type":"text","text":"$text"}]}]}}"""

    private fun resultEvent(outputTokens: Int, text: String = "done"): String =
        """{"type":"result","subtype":"success","result":"$text","usage":{"input_tokens":1,"output_tokens":$outputTokens}}"""

    @Test
    fun `budgeted ordinals reproduce the in-container hook`() {
        val ndjson = listOf(
            assistant("m1", 100, textBlock("planning"), toolUse("t1", "ToolSearch", """{"q":"java"}""")),
            toolResult("t1", "a list of tools"),
            assistant("m2", 200, toolUse("t2", "Bash", """{"command":"ls"}""")),
            toolResult("t2", "services core server-spi"),
            assistant("m3", 300, toolUse("t3", "Bash", """{"command":"cat pom.xml"}""")),
            toolResult("t3", UNDERSTANDING_BUDGET_EXHAUSTED_MESSAGE),
            resultEvent(600),
        ).joinToString("\n")

        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", "case", "shell")

        assertEquals(1, trajectory.budgetedCalls, "only the one call that reached the environment is charged")
        assertEquals(1, trajectory.exemptCalls, "ToolSearch is exempt in the hook and must be exempt here")
        assertEquals(1, trajectory.refusedCalls, "a refused call never reached the repository")
        assertEquals("Bash", trajectory.calls.single().toolName)
        assertEquals(1, trajectory.calls.single().ordinal)
    }

    @Test
    fun `connecting to the semantic tools is free, asking them about the code is not`() {
        val ndjson = listOf(
            assistant("m1", 10, toolUse("t1", "mcp__mcp-steroid__steroid_list_projects", "{}")),
            structuredToolResult("t1", "keycloak@/home/agent/project-home"),
            assistant("m2", 20, toolUse("t2", "mcp__mcp-steroid__steroid_fetch_resource", """{"uri":"x"}""")),
            structuredToolResult("t2", "how to run code in the IDE"),
            assistant("m3", 30, toolUse("t3", "mcp__mcp-steroid__steroid_execute_code", """{"code":"x"}""")),
            structuredToolResult("t3", "ConsentRequiredExecutor"),
            resultEvent(60),
        ).joinToString("\n")

        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", "case", "mcp")

        // The bootstrap pair is the semantic arm's cost of ADDRESSING the IDE, and the shell arm has no
        // equivalent. Charging for it is what made three trajectories of this experiment refuse to touch
        // the tools at all, so the reader must agree with the hook that those two are free.
        assertEquals(2, trajectory.exemptCalls)
        assertEquals(1, trajectory.budgetedCalls, "only the question about the code is an interaction")
        assertEquals("mcp__mcp-steroid__steroid_execute_code", trajectory.calls.single().toolName)
    }

    @Test
    fun `cumulative output tokens follow the turn that issued the call`() {
        val ndjson = listOf(
            assistant("m1", 100, toolUse("t1", "Bash", """{"command":"ls"}""")),
            toolResult("t1", "one"),
            assistant("m2", 250, toolUse("t2", "Bash", """{"command":"ls -la"}""")),
            toolResult("t2", "two"),
            resultEvent(350),
        ).joinToString("\n")

        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", "case", "shell")

        assertEquals(AcquisitionTokenAccounting.PER_MESSAGE, trajectory.tokenAccounting)
        assertEquals(listOf(100L, 350L), trajectory.calls.map { it.cumulativeOutputTokens })
        assertEquals(350L, trajectory.totalOutputTokens)
    }

    @Test
    fun `partial streaming usage falls back to proportional attribution instead of under-reporting`() {
        // Real Claude Code transcripts emit assistant events whose usage is a streaming partial: the
        // per-message numbers add up to a fraction of the run total. Believing them would put the mcp
        // and shell arms on token axes that are wrong by different factors.
        val ndjson = listOf(
            assistant("m1", 1, textBlock("${"x".repeat(300)}"), toolUse("t1", "Bash", """{"command":"ls"}""")),
            toolResult("t1", "one"),
            assistant("m2", 1, textBlock("${"y".repeat(700)}"), toolUse("t2", "Bash", """{"command":"cat"}""")),
            toolResult("t2", "two"),
            resultEvent(10_000),
        ).joinToString("\n")

        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", "case", "shell")

        assertEquals(AcquisitionTokenAccounting.PROPORTIONAL, trajectory.tokenAccounting)
        assertEquals(10_000L, trajectory.totalOutputTokens, "the run total is the terminal event's, always")
        val cumulative = trajectory.calls.map { it.cumulativeOutputTokens }
        assertTrue(cumulative[0] in 2_500L..3_500L, "the first turn emitted about 30% of the characters: $cumulative")
        assertEquals(10_000L, cumulative[1], "the attribution is exact at the end of the run by construction")
    }

    @Test
    fun `a sub-agent's interleaved turns cannot make the token axis go backwards`() {
        // The shape that produced the defect: the main agent delegates, the sub-agent's assistant turns
        // arrive in the same stream, and a LATER result belongs to an EARLIER turn id. Keyed on the
        // issuing turn, call three then reports fewer cumulative tokens than call two — a cumulative
        // quantity that decreases, which is not a denominator but a bug. Two shell trajectories of the
        // first pilot published exactly that (1652 tokens at B=20, 807 at B=40).
        val ndjson = listOf(
            assistant("main-1", 1, textBlock("x".repeat(200)), toolUse("t1", "Bash", """{"command":"ls"}""")),
            toolResult("t1", "one"),
            assistant("sub-1", 1, textBlock("y".repeat(600)), toolUse("t2", "Bash", """{"command":"grep"}""")),
            toolResult("t2", "two"),
            // The main agent's own turn resumes and issues a call whose id was registered first.
            assistant("main-1", 1, toolUse("t3", "Bash", """{"command":"cat"}""")),
            toolResult("t3", "three"),
            resultEvent(9_000),
        ).joinToString("\n")

        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", "case", "shell")

        val cumulative = trajectory.calls.map { it.cumulativeOutputTokens }
        assertEquals(3, cumulative.size)
        assertTrue(
            cumulative.zipWithNext().all { (a, b) -> b >= a },
            "cumulative output tokens must never decrease along a trajectory: $cumulative",
        )
        assertEquals(9_000L, cumulative.last(), "the attribution is exact at the end of the run")
    }

    @Test
    fun `structured mcp tool results are read, not dropped`() {
        val ndjson = listOf(
            assistant("m1", 10, toolUse("t1", "mcp__mcp-steroid__steroid_execute_code", """{"code":"x"}""")),
            structuredToolResult("t1", "class ConsentRequiredExecutor with beforeUpdate"),
            resultEvent(10),
        ).joinToString("\n")

        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", "case", "mcp")

        assertTrue(
            trajectory.calls.single().resultText.contains("ConsentRequiredExecutor"),
            "an mcp result arrives as a list of typed blocks; reading only the string shape would score " +
                "the mcp arm as having observed nothing",
        )
    }

    @Test
    fun `a prefix beyond the end of the run is reported as incomplete rather than padded`() {
        val ndjson = listOf(
            assistant("m1", 10, toolUse("t1", "Bash", """{"command":"ls"}""")),
            toolResult("t1", "one"),
            resultEvent(10),
        ).joinToString("\n")
        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", "case", "shell")

        assertEquals(1, trajectory.prefix(1).actualCalls)
        assertTrue(trajectory.prefix(1).complete)
        assertEquals(1, trajectory.prefix(40).actualCalls)
        assertFalse(trajectory.prefix(40).complete, "a run that stopped at one call has not been observed at forty")
    }

    @Test
    fun `a file name in a listing does not score the fact that lives inside the file`() {
        val checklist = CC_REFRESH_TOKEN_CHECKLIST
        val directoryListing = listOf(
            "keycloak-default-client-profiles.json\nkeycloak-strict-client-policies.json\n" +
                "strict-security-profile.json\nlax-security-profile.json",
        )

        assertFalse(
            checklist.facts.single { it.id == "G1" }.observedIn(directoryListing),
            "seeing the three file names in one listing is not knowing which profile the strict policy binds",
        )
        assertTrue(
            checklist.facts.single { it.id == "G1" }.observedIn(
                listOf("""{"name":"Openid-connect OAuth 2.1 confidential client","enabled":true,""" +
                    """"profiles":["oauth-2-1-for-confidential-client"]}"""),
            ),
            "quoting the enabled policy that binds the profile is the fact",
        )
    }

    @Test
    fun `an empty prefix scores nothing and the whole checklist is reachable`() {
        val checklist = CC_REFRESH_TOKEN_CHECKLIST
        assertEquals(0.0, checklist.observedScore(emptyList()), 1e-9)

        // Every fact must be reachable by SOME text, or it is an unscoreable item that would cap both
        // arms below 1.0 and hide a real difference in the ceiling.
        val everything = checklist.facts.map { fact -> fact.evidenceBundles.first().joinToString(" ") }
        assertEquals(1.0, checklist.observedScore(everything), 1e-9)
    }

    @Test
    fun `the curve carries both denominators at every checkpoint`() {
        val calls = (1..25).map { index ->
            listOf(
                assistant("m$index", index * 10, toolUse("t$index", "Bash", """{"command":"c$index"}""")),
                toolResult("t$index", if (index == 3) "client_credentials.use_refresh_token" else "nothing"),
            )
        }.flatten()
        val ndjson = (calls + resultEvent(25 * 10 * 25)).joinToString("\n")
        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", CC_REFRESH_TOKEN_CHECKLIST.caseId, "shell")

        val curve = observedCurve(trajectory, CC_REFRESH_TOKEN_CHECKLIST)

        assertEquals(ACQUISITION_CHECKPOINTS, curve.map { it.checkpoint })
        assertEquals(listOf(5, 10, 20, 25), curve.map { it.actualCalls })
        assertTrue(curve.all { it.cumulativeOutputTokens > 0 }, "a token axis of zeroes is not a denominator")
        assertTrue(curve.first().observedScore > 0.0, "the attribute was quoted at call three")
        assertFalse(curve.last().complete, "the run has 25 calls and the last checkpoint is 40")
    }

    @Test
    fun `the pre-registered case does not hand the agent its own answer`() {
        val case = AcquisitionCases.ccRefreshToken
        val statement = case.problemStatement.lowercase()

        val forbidden = case.goldRolePaths.values.flatten().map { it.substringAfterLast('/') } +
            case.precedentPaths.map { it.substringAfterLast('/').removeSuffix(".java") } +
            listOf("clientpolicy", "executor", "provider", "META-INF", "oauth-2-1-for-confidential-client",
                "reject-client-credentials-refresh-token", "client_credentials.use_refresh_token")
        for (token in forbidden) {
            assertFalse(
                statement.contains(token.lowercase()),
                "the statement names '$token', which is the answer rather than the behaviour",
            )
        }
        assertTrue(
            case.statementLeakageTokens.values.all { it >= 20 },
            "a phrase of the statement that matches fewer than twenty files is a pointer, not vocabulary: " +
                case.statementLeakageTokens,
        )
    }

    @Test
    fun `the checklist is registered for the case and is not a navigation benchmark`() {
        val checklist = AcquisitionCases.checklistFor(AcquisitionCases.ccRefreshToken.instanceId)

        assertEquals(15, checklist.facts.size)
        assertEquals(15, checklist.totalWeight, "every fact is worth one point in the pre-registered design")
        val nonNavigational = checklist.facts.count {
            it.category in setOf(
                AcquisitionFactCategory.FLOW,
                AcquisitionFactCategory.SECONDARY_INTEGRATION,
                AcquisitionFactCategory.INVARIANT,
                AcquisitionFactCategory.ENTRY_POINT,
            )
        }
        assertTrue(
            nonNavigational >= 5,
            "only $nonNavigational of the facts are the kind no find-usages answers; below five this " +
                "measures navigation and not architecture",
        )
        assertTrue(checklist.facts.all { it.judgeQuestion.endsWith("?") }, "a rubric item is a question")
    }

    /**
     * A case declares an oracle completely or not at all.
     *
     * The generalization round buys research trajectories on cases that have no hidden oracle, which
     * is a deliberate saving: `U(B)` needs a statement and a checklist, and a hidden test suite per
     * case is what would have kept this family at one case. The danger the type has to remove is the
     * MIDDLE state — a patch with no FAIL_TO_PASS entry grades nothing while looking graded, and a
     * denominator with no patch publishes a fraction of an oracle that does not exist. Both come out
     * as a percentage rather than as an error.
     */
    @Test
    fun `a case is either research-only or gradable and never half of one`() {
        fun case(resource: String?, failToPass: List<String>, count: Int) = UnderstandingCase(
            instanceId = "acquisition__keycloak__probe",
            problemStatement = "behaviour only",
            oracleTestPatchResource = resource,
            failToPass = failToPass,
            oracleTestCount = count,
            gradingScopeSelector = ":keycloak-services",
            statementLeakageTokens = mapOf("strict" to 592),
            precedentPaths = listOf("services/src/main/java/Whatever.java"),
            goldRolePaths = emptyMap(),
        )

        assertFalse(case(null, emptyList(), 0).gradable, "no oracle declared at all")
        assertTrue(case("p.patch", listOf("a.B"), 3).gradable)

        // Every half-declaration, each of which used to be constructible.
        assertThrows(IllegalStateException::class.java) { case("p.patch", emptyList(), 0) }
        assertThrows(IllegalStateException::class.java) { case(null, listOf("a.B"), 0) }
        assertThrows(IllegalStateException::class.java) { case(null, emptyList(), 3) }
        assertThrows(IllegalStateException::class.java) { case("p.patch", listOf("a.B"), 0) }
    }

    /**
     * The token axis counts every model of the run, not just the one that answered last.
     *
     * The final event carries two accountings of the same run: `usage`, which is ONE model's output,
     * and `modelUsage`, the per-model breakdown. Round two read only the first, and three control-arm
     * trajectories had delegated most of their work to a cheap sub-agent — so the published axis showed
     * 30-40 % of what they emitted, and "the shell arm buys facts three times cheaper per token"
     * followed from the omission. The correction is not cosmetic: it moves the per-fact token ratio
     * between the arms from 2.8x to about 1.2x.
     */
    @Test
    fun `a delegating run is charged for its sub-agent too`() {
        val ndjson = listOf(
            assistant("m1", 40, toolUse("t1", "Bash", """{"command":"grep"}""")),
            toolResult("t1", "something"),
            """{"type":"result","subtype":"success","result":"done",""" +
                """"usage":{"input_tokens":1,"output_tokens":3635},""" +
                """"modelUsage":{"claude-opus-5":{"outputTokens":3635,"inputTokens":10},""" +
                """"claude-haiku-4-5-20251001":{"outputTokens":6589,"inputTokens":10}}}""",
        ).joinToString("\n")

        val trajectory = parseAcquisitionTrajectory(ndjson, "t-1", "case", "shell")

        assertEquals(10_224L, trajectory.totalOutputTokens, "the sub-agent's output is part of the cost")
        assertEquals(6_589L, trajectory.delegatedOutputTokens, "and it is visible as delegated")
        assertEquals("claude-opus-5", trajectory.model)

        // A run that delegated nothing must be unchanged, or every published number would move.
        val solo = listOf(
            assistant("m1", 40, toolUse("t1", "Bash", """{"command":"grep"}""")),
            toolResult("t1", "something"),
            """{"type":"result","subtype":"success","result":"done",""" +
                """"usage":{"input_tokens":1,"output_tokens":21873},""" +
                """"modelUsage":{"claude-opus-5":{"outputTokens":21873,"inputTokens":10}}}""",
        ).joinToString("\n")
        val single = parseAcquisitionTrajectory(solo, "t-2", "case", "mcp")
        assertEquals(21_873L, single.totalOutputTokens)
        assertEquals(0L, single.delegatedOutputTokens)
    }

    /**
     * The registry of the generalization round, asserted as a SET rather than case by case.
     *
     * The round's claim is not "the effect replicates" but "the effect is ordered": largest on the
     * navigational control, smallest on the shallow one, and somewhere between on the two architecture
     * cases. That claim is only readable if the round really contains those different shapes, so the
     * shapes are asserted here — one declared control and no more, a checklist for every case, and the
     * no-leakage rule enforced on every case that is NOT the control.
     */
    @Test
    fun `the round is a set of different shapes and not the same case four times`() {
        val cases = AcquisitionCases.all
        assertTrue(cases.size >= 3, "a generalization round with fewer than three cases generalizes nothing")
        assertEquals(cases.size, cases.map { it.instanceId }.distinct().size, "duplicate case id")

        val checklists = cases.map { AcquisitionCases.checklistFor(it.instanceId) }
        assertEquals(
            cases.map { it.instanceId },
            checklists.map { it.caseId },
            "a checklist registered under another case's id would score the wrong facts silently",
        )
        assertEquals(
            1,
            checklists.count { it.positiveControl },
            "exactly one case anchors the top of the scale; two would be two anchors and none would " +
                "leave a modest effect unreadable",
        )

        for ((case, checklist) in cases.zip(checklists)) {
            assertTrue(checklist.facts.size in 8..15, "${case.instanceId}: ${checklist.facts.size} facts")
            assertTrue(
                checklist.facts.all { it.judgeQuestion.endsWith("?") },
                "${case.instanceId}: a rubric item is a question",
            )
            // The control is the ONLY case allowed to name its target: a rename brief without the
            // symbol is a guessing game. Every other statement must still be pure vocabulary.
            if (checklist.positiveControl) continue
            assertTrue(
                case.statementLeakageTokens.isNotEmpty(),
                "${case.instanceId}: an architecture case with no leakage audit was never admitted",
            )
        }
    }

    @Test
    fun `a research-only case refuses to be read as something gradable`() {
        val researchOnly = UnderstandingCase(
            instanceId = "acquisition__keycloak__probe",
            problemStatement = "behaviour only",
            gradingScopeSelector = ":keycloak-services",
            statementLeakageTokens = mapOf("strict" to 592),
            precedentPaths = listOf("services/src/main/java/Whatever.java"),
            goldRolePaths = emptyMap(),
        )

        // Not "returns an empty patch": an empty patch applies cleanly and grades a pristine tree,
        // which is a green cell reporting that nothing was asked of the agent.
        val patch = assertThrows(IllegalStateException::class.java) { researchOnly.oracleTestPatch() }
        assertTrue(patch.message!!.contains("research-only"), patch.message)
        val shape = assertThrows(IllegalStateException::class.java) { researchOnly.dpaiaCase() }
        assertTrue(shape.message!!.contains("research-only"), shape.message)
    }
}
