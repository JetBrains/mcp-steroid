/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
