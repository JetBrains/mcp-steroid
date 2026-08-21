/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The contract of the repository-understanding instrument, pinned where it is free to pin.
 *
 * Everything here is a rule that costs a paid Opus or Haiku run to discover the expensive way: a budget
 * that counts the wrong calls, a note that silently arrives longer than its condition, a research run
 * that reads as dirty because the IDE wrote `.idea/`, a downstream brief that differs between arms by
 * more than the note. None of those failures is visible in a build log — each looks like a measurement.
 */
class UnderstandingHarnessTest {

    // ── the note ─────────────────────────────────────────────────────────────

    @Test
    fun `the note is what the markers enclose, not the model's framing around them`() {
        val note = extractUnderstandingNote(
            "Sure! Here is my hand-off note.\n\n<NOTE>\nStart in the policy SPI.\n</NOTE>\n\nGood luck!",
            limitChars = 1_000,
        )
        assertEquals("Start in the policy SPI.", note.text)
        assertFalse(note.markersMissing)
        assertFalse(note.truncated)
    }

    @Test
    fun `a note without markers is still usable and says so`() {
        val note = extractUnderstandingNote("The change belongs in two places.", limitChars = 1_000)
        assertEquals("The change belongs in two places.", note.text)
        assertTrue(note.markersMissing)
    }

    @Test
    fun `a note longer than its condition is cut to exactly the limit and marked`() {
        val note = extractUnderstandingNote("<NOTE>" + "x".repeat(1_500) + "</NOTE>", limitChars = 1_000)
        assertEquals(1_000, note.text.length)
        assertEquals(1_500, note.originalChars)
        assertTrue(note.truncated)
    }

    @Test
    fun `a run that produced no final message is an instrument failure, never an empty note`() {
        assertThrows(IllegalStateException::class.java) { extractUnderstandingNote(null, 1_000) }
        assertThrows(IllegalStateException::class.java) { extractUnderstandingNote("   ", 1_000) }
        assertThrows(IllegalStateException::class.java) {
            extractUnderstandingNote("<NOTE>\n\n</NOTE>", 1_000)
        }
    }

    @Test
    fun `pasted tool output is measured, and prose about files is not`() {
        val dump = """
            diff --git a/A.java b/A.java
            @@ -1,4 +1,9 @@
            src/main/java/A.java:42: public void x()
            public class A {
        """.trimIndent()
        assertEquals(100, verbatimLinePercent(dump))
        val prose = """
            The claim is written by the mapper base class; see HardcodedClaim.java:47 for the pattern.
            Registration happens twice, in two different mechanisms.
        """.trimIndent()
        assertEquals(0, verbatimLinePercent(prose))
    }

    @Test
    fun `a note id round-trips through its coordinates`() {
        val id = understandingNoteId("mcp", 10, 5_000, 3)
        assertEquals("mcp-b10-l5000-r3", id)
        assertEquals(UnderstandingNoteCoordinates("mcp", 10, 5_000, 3), parseUnderstandingNoteId(id))
        assertThrows(IllegalStateException::class.java) { parseUnderstandingNoteId("mcp-10-5000-1") }
        assertThrows(IllegalStateException::class.java) { parseUnderstandingNoteId("oracle-b10-l5000-r1") }
    }

    // ── the budget gate ──────────────────────────────────────────────────────

    @Test
    fun `the gate exempts the CLI's own tool discovery and the agent's scratch list`() {
        val script = understandingBudgetHookScript(5, "/r/used", "/r/denied", "/r")
        assertTrue(script.contains("ToolSearch|TodoWrite)"), script)
        // The mcp arm spends its first calls on tool discovery; charging for it would hand the shell arm
        // a larger effective budget at B=5 and the comparison would measure the CLI's plumbing.
        assertFalse(script.contains("steroid_list_projects"), "list_projects DOES query the environment")
    }

    @Test
    fun `the gate refuses with exit code 2 and never writes to stdout`() {
        val script = understandingBudgetHookScript(7, "/r/used", "/r/denied", "/r")
        assertTrue(script.contains("exit 2"), "a PreToolUse hook blocks only through exit code 2")
        assertTrue(script.contains(">&2"), "the reason must reach the model through stderr")
        assertTrue(
            script.lines().none { it.trimStart().startsWith("echo") && !it.contains(">") },
            "no line may echo to stdout: that channel is the CLI's JSON-RPC transport\n$script",
        )
    }

    @Test
    fun `the gate counts only allowed calls, so the budget really buys that many interactions`() {
        val script = understandingBudgetHookScript(5, "/r/used", "/r/denied", "/r")
        val decision = script.indexOf("-ge 5")
        val increment = script.indexOf("+ 1 )) > /r/used")
        assertTrue(decision in 1..<increment) {
            "the counter must be incremented AFTER the refusal check, or the denied call itself would " +
                "spend one of the budgeted interactions:\n$script"
        }
    }

    @Test
    fun `a broken counter lets the call through instead of blocking the whole run`() {
        val script = understandingBudgetHookScript(5, "/r/used", "/r/denied", "/r")
        assertTrue(
            script.contains("''|*[!0-9]*) exit 0 ;;"),
            "an instrument that cannot count must not turn into one that blocks everything:\n$script",
        )
    }

    @Test
    fun `every hook of a research run lands in ONE settings file`() {
        val json = understandingHookSettingsJson(
            listOf(AgentHook("PreToolUse", "/r/gate.sh"), AgentHook("PostToolUse", "/r/record.sh")),
        )
        val hooks = Json.parseToJsonElement(json).jsonObject["hooks"]!!.jsonObject
        assertEquals(setOf("PreToolUse", "PostToolUse"), hooks.keys)
        hooks.values.forEach { event ->
            val entry = event.jsonArray.single().jsonObject
            assertEquals("*", entry["matcher"]!!.jsonPrimitive.content)
        }
        assertThrows(IllegalArgumentException::class.java) { understandingHookSettingsJson(emptyList()) }
    }

    @Test
    fun `an absent counter file reads as zero rather than as an error`() {
        assertEquals(UnderstandingBudgetUsage(0, 0), parseUnderstandingBudgetUsage(null, null))
        assertEquals(UnderstandingBudgetUsage(4, 11), parseUnderstandingBudgetUsage("4\n", " 11 "))
    }

    // ── the pristine rule ────────────────────────────────────────────────────

    @Test
    fun `an untouched tree is pristine and an edited one names what changed`() {
        assertTrue(understandingPristineVerdict("").pristine)
        val dirty = understandingPristineVerdict(
            """
             M services/src/main/java/org/keycloak/Foo.java
            ?? notes.txt
            """.trimIndent()
        )
        assertFalse(dirty.pristine)
        assertEquals(
            listOf("services/src/main/java/org/keycloak/Foo.java", "notes.txt"),
            dirty.violations,
        )
    }

    @Test
    fun `what the IDE writes is not charged to the agent, and is still reported`() {
        val verdict = understandingPristineVerdict(
            """
            ?? .idea/workspace.xml
            ?? keycloak.iml
            """.trimIndent()
        )
        assertTrue(verdict.pristine)
        assertEquals(2, verdict.ignored.size)
        assertTrue(verdict.describe().contains("PRISTINE"))
    }

    @Test
    fun `a renamed file is reported by the path that exists now`() {
        val verdict = understandingPristineVerdict("R  old/A.java -> new/A.java")
        assertEquals(listOf("new/A.java"), verdict.violations)
    }

    // ── the cell coordinates ─────────────────────────────────────────────────

    @Test
    fun `research coordinates are refused unless every one of them arrived`() {
        val ok = understandingResearchCoordinates("understanding__x", "mcp", "10", "5000", "2")
        assertEquals("mcp-b10-l5000-r2", ok.noteId)
        assertThrows(IllegalStateException::class.java) {
            understandingResearchCoordinates(null, "mcp", "10", "5000", "1")
        }
        assertThrows(IllegalStateException::class.java) {
            understandingResearchCoordinates("understanding__x", "shell", "10", "5000", "1")
        }
        // A budget outside the pre-registered grid cannot be placed on the curve this experiment publishes.
        assertThrows(IllegalStateException::class.java) {
            understandingResearchCoordinates("understanding__x", "mcp", "7", "5000", "1")
        }
        assertThrows(IllegalStateException::class.java) {
            understandingResearchCoordinates("understanding__x", "mcp", "10", "4000", "1")
        }
        assertThrows(IllegalStateException::class.java) {
            understandingResearchCoordinates("understanding__x", "mcp", "10", "5000", "0")
        }
    }

    @Test
    fun `a downstream condition is either the control, an oracle or a real note id`() {
        assertEquals(UnderstandingCondition.Baseline, understandingConditionOf("baseline"))
        assertEquals(UnderstandingCondition.Oracle("gold"), understandingConditionOf("oracle:gold"))
        assertEquals(UnderstandingCondition.Research("none-b5-l1000-r4"), understandingConditionOf("none-b5-l1000-r4"))
        // A typo must never quietly become the control: the cell would be published under a note's label.
        assertThrows(IllegalStateException::class.java) { understandingConditionOf("baselin") }
        assertThrows(IllegalStateException::class.java) { understandingConditionOf(null) }
        assertThrows(IllegalStateException::class.java) { understandingConditionOf("oracle:") }
    }

    @Test
    fun `a note file nobody can queue is reported`() {
        assertEquals(emptyList<String>(), understandingNoteProblems(listOf("mcp-b5-l1000-r1.md", "oracle-gold.md")))
        assertEquals(1, understandingNoteProblems(listOf("mcp-b5.md")).size)
    }

    // ── the two briefs ───────────────────────────────────────────────────────

    @Test
    fun `the research brief states the budget, the limit, the markers and the no-edit rule`() {
        val prompt = buildUnderstandingResearchPrompt(sampleCase, "/home/agent/project", withMcp = false, budget = 5, noteLimitChars = 1_000)
        assertTrue(prompt.contains("at most **5 environment interactions**"), prompt)
        assertTrue(prompt.contains("at most **1000 characters**"), prompt)
        assertTrue(prompt.contains(UNDERSTANDING_NOTE_OPEN_MARKER))
        assertTrue(prompt.contains(UNDERSTANDING_NOTE_CLOSE_MARKER))
        assertTrue(prompt.contains("Do NOT modify"))
        assertTrue(prompt.contains(sampleCase.problemStatement))
        // The research agent must never be asked for the arena's fix marker: it fixes nothing, and a
        // brief that asked would make "did it claim a fix" meaningless for this phase.
        assertFalse(prompt.contains(ARENA_FIX_APPLIED_MARKER))
    }

    @Test
    fun `only the mcp research arm is told the resolved-program tools exist`() {
        val mcp = buildUnderstandingResearchPrompt(sampleCase, "/p", withMcp = true, budget = 10, noteLimitChars = 5_000)
        val shell = buildUnderstandingResearchPrompt(sampleCase, "/p", withMcp = false, budget = 10, noteLimitChars = 5_000)
        assertTrue(mcp.contains("## Available Tools"))
        assertFalse(shell.contains("## Available Tools"))
        // Everything before that section must be identical, or the arms differ by more than their tools.
        assertEquals(shell.substringBefore("## Environment Facts"), mcp.substringBefore("## Environment Facts"))
    }

    @Test
    fun `the three downstream arms differ by exactly one inserted block`() {
        val baseline = buildUnderstandingDownstreamPrompt(sampleCase, "/p", note = null)
        val withNote = buildUnderstandingDownstreamPrompt(sampleCase, "/p", note = "Two places must change.")
        assertFalse(baseline.contains("Notes from another developer"))
        assertTrue(withNote.contains(UNDERSTANDING_NOTE_INTRODUCTION))
        assertTrue(withNote.contains("Two places must change."))
        assertEquals(
            baseline.replace("## Environment Facts", "@@SPLIT@@").substringAfter("@@SPLIT@@"),
            withNote.replace("## Environment Facts", "@@SPLIT@@").substringAfter("@@SPLIT@@"),
        )
        assertTrue(baseline.contains(ARENA_FIX_APPLIED_MARKER), "the runner reads only that marker")
    }

    @Test
    fun `the downstream brief tells the agent nothing about the comparison it is part of`() {
        val prompt = buildUnderstandingDownstreamPrompt(sampleCase, "/p", note = "anything")
        listOf("mcp", "experiment", "comparison", "baseline", "replicate", "research").forEach { leak ->
            assertFalse(prompt.contains(leak, ignoreCase = true)) {
                "the downstream brief must not mention '$leak' — a probe that knows it is being compared " +
                    "can behave differently:\n$prompt"
            }
        }
    }

    // ── where the note is read from ──────────────────────────────────────────

    /**
     * The regression of the first research wave: eight paid Opus runs, every one of them holding a
     * finished note, all reported as "the research run produced no final message".
     *
     * The note travels in the terminal `result` event, and the captured process stdout is
     * console-filtered — the filter drops precisely that event. Nothing about the filtered stream looks
     * broken: it parses, it is not empty, it simply has no result in it, so the failure reads as an
     * agent that said nothing rather than as a harness reading the wrong file.
     */
    @Test
    fun `the run's own transcript wins over the console-filtered stdout`(@TempDir tempDir: File) {
        val runDir = File(tempDir, "run").also { it.mkdirs() }
        File(runDir, "agent-claude-code-1-raw.ndjson").writeText(
            """{"type":"result","subtype":"success","result":"<NOTE>\nthe real note\n</NOTE>"}"""
        )
        val filteredStdout = "[assistant] Let me look at the protocol factory.\n"

        val raw = resolveAgentRawOutput(runDir, agentName = "claude", fallbackStdout = filteredStdout)
        val note = extractUnderstandingNote(decodeAgentFinalResponse(raw), limitChars = 1_000)

        assertEquals("the real note", note.text)
        assertFalse(note.markersMissing, "the markers survive the round trip through the transcript")
    }

    @Test
    fun `a run whose transcript never landed still falls back to whatever stdout held`(@TempDir tempDir: File) {
        val runDir = File(tempDir, "empty").also { it.mkdirs() }

        val raw = resolveAgentRawOutput(runDir, agentName = "claude", fallbackStdout = "only this")

        assertEquals("only this", raw) {
            "a run that died before its transcript was persisted has nothing else; discarding it would " +
                "throw away a paid run over a missing file"
        }
    }

    /**
     * The real shape of the failure that cost this experiment two research waves.
     *
     * The session driver persists no raw transcript for these runs, so the fallback IS the source, and
     * in it every event carries a `[IDE OUT] ` prefix for the human reader. A line-by-line JSON parser
     * skips such a line without a word, so the note was reported missing while sitting in the build log
     * in plain text.
     */
    @Test
    fun `a console-prefixed event line is still read as the agent's final message`(@TempDir tempDir: File) {
        val runDir = File(tempDir, "no-transcript").also { it.mkdirs() }
        val consoleStdout = buildString {
            appendLine("[22:14:41] :\t [IDE] starting the agent")
            appendLine(
                """[IDE OUT] {"type":"result","subtype":"success","result":"<NOTE>\nprefixed note\n</NOTE>"}"""
            )
        }

        val raw = resolveAgentRawOutput(runDir, agentName = "claude", fallbackStdout = consoleStdout)
        val note = extractUnderstandingNote(decodeAgentFinalResponse(raw), limitChars = 1_000)

        assertEquals("prefixed note", note.text)
    }

    @Test
    fun `unprefixing leaves a line that is already plain NDJSON untouched`() {
        val plain = """{"type":"result","result":"x"}"""

        assertEquals(plain, unprefixConsoleNdjson(plain)) {
            "the persisted transcript must survive the same path byte for byte, or the two sources " +
                "would disagree about the same run"
        }
    }

    private val sampleCase = UnderstandingCase(
        instanceId = "understanding__sample__case",
        problemStatement = "Make the widget emit its colour when asked politely.",
        oracleTestPatchResource = "arena-overlays/does-not-need-to-exist.patch",
        failToPass = listOf("org.example.WidgetContractTest"),
        gradingScopeSelector = ":example-widgets",
        statementLeakageTokens = mapOf("colour" to 3),
        precedentPaths = listOf("src/main/java/org/example/Shape.java"),
        goldRolePaths = mapOf("behavior" to listOf("src/main/java/org/example/Widget.java")),
    )
}
