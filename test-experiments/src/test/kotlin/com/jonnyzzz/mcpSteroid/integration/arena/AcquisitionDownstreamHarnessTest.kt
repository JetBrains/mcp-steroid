/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The unit tests of the downstream-validation half of the acquisition round.
 *
 * They exist for the reason the curve's own tests do: every rule here, when wrong, produces a
 * plausible table rather than an error. A checkpoint condition that silently resolved to the other
 * family's note directory, or a residual count that read "0 of 0" for a cell whose module never
 * compiled, would both publish numbers that look like measurements of understanding and are not.
 */
class AcquisitionDownstreamHarnessTest {

    private val case = AcquisitionCases.ccRefreshToken

    @Test
    fun `a checkpoint condition names a knowledge state, a note id still names a run`() {
        val condition = understandingConditionOf("checkpoint:mcp-b40-l2000-r2@10")
        val note = assertInstanceOf(AcquisitionCheckpointNote::class.java, condition)
        assertEquals("mcp-b40-l2000-r2", note.trajectoryId)
        assertEquals(10, note.checkpoint)
        assertEquals("mcp-b40-l2000-r2@10", note.label)
        assertEquals("mcp", note.arm)
        assertEquals("shell", AcquisitionCheckpointNote("none-b40-l2000-r3", 20).arm)

        // The three older shapes must keep meaning exactly what they meant. A prefix that started
        // swallowing note ids would silently redirect every note-bottleneck cell to an empty directory.
        assertTrue(understandingConditionOf("mcp-b10-l2000-r1") is UnderstandingCondition.Research)
        assertTrue(understandingConditionOf("baseline") is UnderstandingCondition.Baseline)
        assertTrue(understandingConditionOf("oracle:gold") is UnderstandingCondition.Oracle)
    }

    @Test
    fun `a note distilled at an unregistered prefix has no U to be plotted against`() {
        // 7 is not a checkpoint of this experiment, so no `U` was ever computed for it. A cell run
        // against such a note would land on the scatter plot with a fabricated x coordinate.
        assertThrows(IllegalStateException::class.java) {
            understandingConditionOf("checkpoint:mcp-b40-l2000-r2@7")
        }
        assertThrows(IllegalStateException::class.java) {
            understandingConditionOf("checkpoint:mcp-b40-l2000-r2")
        }
        assertThrows(IllegalStateException::class.java) {
            understandingConditionOf("checkpoint:@10")
        }
    }

    @Test
    fun `the two families read their notes from different directories`() {
        val checkpoint = AcquisitionCheckpointNote("mcp-b40-l2000-r2", 20)
        assertEquals(
            "src/test/resources/acquisition-notes/${case.instanceId}/mcp-b40-l2000-r2-at20.md",
            acquisitionNoteFile(case, checkpoint).path.replace(File.separatorChar, '/'),
        )
        assertTrue(
            understandingNoteDir(case).path != acquisitionNoteDir(case).path,
            "a read-out of a prefix and a note the research agent wrote answer different questions",
        )
    }

    @Test
    fun `residual work is counted against the oracle, never against what happened to run`() {
        fun verification(
            testsRun: Int,
            failures: Int,
            errors: Int,
            tampered: Boolean = false,
        ) = ArenaVerificationResult(
            perClass = listOf(SurefireClassResult(case.failToPass.single(), testsRun, failures, errors, 0)),
            failToPassTampered = tampered,
            collateralTestFilesEdited = emptyList(),
            regressions = emptyList(),
            baselineAvailable = false,
            verificationDurationMs = 1,
        )

        assertEquals(8, oracleAssertionsPassed(verification(8, 0, 0), case))
        assertEquals(5, oracleAssertionsPassed(verification(8, 2, 1), case))
        // The case this reading exists for: the module did not compile, surefire ran nothing, and the
        // binary verdict cannot tell it apart from seven assertions of eight.
        assertEquals(0, oracleAssertionsPassed(verification(0, 0, 0), case))
        assertEquals(0, oracleAssertionsPassed(null, case), "a cell that was never graded proved nothing")
        assertEquals(
            0,
            oracleAssertionsPassed(verification(8, 0, 0, tampered = true), case),
            "an agent that rewrote the oracle it is judged by scored nothing on it",
        )
    }

    @Test
    fun `the verdict line carries the pair the scatter plot is indexed by`() {
        val line = acquisitionDownstreamLine(
            caseId = case.instanceId,
            condition = AcquisitionCheckpointNote("none-b40-l2000-r3", 5),
            replicate = 1,
            outcome = UnderstandingDownstreamOutcome(
                success = false,
                verdict = "Y=0",
                oracleTestsPassed = 3,
                oracleTestsTotal = 8,
                cost = UnderstandingCellCost(usd = 0.25, agentSeconds = 400, outputTokens = 9_000),
            ),
        )
        assertTrue(line.contains("trajectory=none-b40-l2000-r3"), line)
        assertTrue(line.contains("checkpoint=5"), line)
        assertTrue(line.contains("arm=shell"), line)
        assertTrue(line.contains("oraclePassed=3/8"), line)
        assertTrue(line.contains("residual=5"), line)
        // `U` deliberately absent: it is recomputed from the transcript, and a copy carried through a
        // build parameter is a copy that can disagree with the curve it is plotted on.
        assertTrue(!line.contains("u="), line)
    }

    @Test
    fun `a calibration anchor reports its residual work without pretending to be a note`() {
        val line = acquisitionDownstreamLine(
            caseId = case.instanceId,
            condition = UnderstandingCondition.Baseline,
            replicate = 2,
            outcome = UnderstandingDownstreamOutcome(
                success = false,
                verdict = "Y=0",
                oracleTestsPassed = 0,
                oracleTestsTotal = 8,
                cost = UnderstandingCellCost(null, null, null),
            ),
        )
        assertTrue(line.contains("condition=baseline"), line)
        assertTrue(line.contains("trajectory=- checkpoint=- arm=-"), line)
        assertTrue(line.contains("residual=8"), line)
    }

    @Test
    fun `every cell of the pre-registered matrix has a note, and none of them names its arm`() {
        assertEquals(12, ACQUISITION_DOWNSTREAM_MATRIX.size)
        assertEquals(
            listOf("mcp", "shell"),
            ACQUISITION_DOWNSTREAM_MATRIX.map { it.arm }.distinct().sorted(),
            "a matrix with one arm cannot support the matched-pair reading the design rests on",
        )

        // A note that mentioned a search, a tool or the IDE would tell the blind judge which arm it
        // came from, and the arm reading is the only thing keeping "the note was better" apart from
        // "the tool was different". `\bide\b` rather than `ide`, because `provider` contains it.
        val giveaways = Regex(
            """steroid|find[ -]?usages|\bgrep\b|ripgrep|\bIDE\b|\bMCP\b|tool call|semantic search""",
            RegexOption.IGNORE_CASE,
        )
        for (note in ACQUISITION_DOWNSTREAM_MATRIX) {
            val text = note.noteText(case)
            assertTrue(text.length in 500..5_000, "${note.label} is ${text.length} characters")
            assertTrue(
                giveaways.find(text) == null,
                "${note.label} names how it was found: '${giveaways.find(text)?.value}'",
            )
            // The oracle is applied after the agent finishes. A note naming its class would let the
            // agent create a file at that path, and the cell would be LOST rather than graded.
            assertTrue(
                !text.contains(case.failToPass.single().substringAfterLast('.')),
                "${note.label} names the hidden oracle class",
            )
        }
    }

    @Test
    fun `a trajectory that never used its tools cannot become a note either`() {
        fun trajectory(arm: String, vararg tools: String) = AcquisitionTrajectory(
            trajectoryId = "t", caseId = case.instanceId, arm = arm, model = "opus",
            calls = tools.mapIndexed { index, tool ->
                AcquisitionToolCall(index + 1, tool, "{}", "", 0, null)
            },
            exemptCalls = 0, refusedCalls = 0, totalOutputTokens = 1,
            tokenAccounting = AcquisitionTokenAccounting.PER_MESSAGE, delegatedOutputTokens = 0,
            finalMessage = "",
        )

        assertTrue(armDegenerate(trajectory("mcp", "Bash", "Read")))
        assertTrue(!armDegenerate(trajectory("mcp", "Bash", "mcp__mcp-steroid__steroid_execute_code")))
        // The control arm has no semantic tools by construction, so the predicate must never fire on
        // it — a re-reader that skipped every shell trajectory would silently halve the round.
        assertTrue(!armDegenerate(trajectory("shell", "Bash", "Read")))
    }

    @Test
    fun `the downstream gate charges for reading the repository and not for writing the answer`() {
        val script = understandingBudgetHookScript(
            budget = ACQUISITION_DOWNSTREAM_BUDGET,
            counterFile = "/r/used",
            deniedFile = "/r/denied",
            recordDir = "/r",
            exemptTools = UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS,
            exhaustedMessage = UNDERSTANDING_DOWNSTREAM_BUDGET_EXHAUSTED_MESSAGE,
        )
        // The edit tools are free, so a note's value is priced in discovery rather than in keystrokes.
        listOf("Write", "Edit", "MultiEdit").forEach {
            assertTrue(script.contains("$it|") || script.contains("|$it"), "$it must be exempt: $script")
        }
        // Bash is NOT, even though it also runs the build: the same tool greps the tree, and a rule
        // keyed on the command text is a rule the agent can phrase its way around.
        assertTrue(!UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS.contains("Bash"), script)
        assertTrue(!UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS.contains("Read"), script)
        // Read stays charged, and the one hole in that rule is the repair turn's own list of files.
        // Without it `Edit` is free and unusable at once — the CLI will not edit an unread file — so
        // the repair turn would spend its rounds being refused. See UNDERSTANDING_REPAIR_READABLE_FILE.
        assertTrue(script.contains(UNDERSTANDING_REPAIR_READABLE_FILE), script)
        assertTrue(script.contains("grep -Fxq"), "a prefix match would exempt more than javac named")
        // No downstream cell has an IDE in any condition, so an exemption for one would describe a
        // tool that cannot be called — and would invite giving one condition its tools back.
        assertTrue(UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS.none { it.contains("steroid") })
        assertTrue(UNDERSTANDING_BUDGET_EXEMPT_TOOLS.any { it.contains("steroid") })

        // The wall must tell a downstream agent to FINISH, where it tells a research agent to stop.
        assertTrue(script.contains("still create and edit files"), script)
        assertTrue(!script.contains(UNDERSTANDING_NOTE_OPEN_MARKER), script)
    }

    @Test
    fun `the brief states the price of everything before the agent spends anything`() {
        val budgeted = buildUnderstandingDownstreamPrompt(case, "/p", note = null, budget = 20)
        assertTrue(budgeted.contains("**20 times**"), budgeted)
        assertTrue(budgeted.contains("Creating and editing files is FREE"), budgeted)
        assertTrue(budgeted.contains("Build at most once"), budgeted)

        // The unbudgeted shape must stay byte-identical to what the note-bottleneck rounds ran, or
        // their published tables would be describing a brief that no longer exists.
        val unlimited = buildUnderstandingDownstreamPrompt(case, "/p", note = null)
        assertTrue(!unlimited.contains("## Your budget"), unlimited)
        assertTrue(!unlimited.contains("costs ONE"), unlimited)
        assertTrue(unlimited.contains("Verify your work by building and testing the module"), unlimited)

        // One inserted block and nothing else is the whole design of the note conditions; the budget
        // paragraph must not have become a second difference between them.
        val withNote = buildUnderstandingDownstreamPrompt(case, "/p", note = "a note", budget = 20)
        assertEquals(
            budgeted.lines().filter { it.contains("interact with this repository") },
            withNote.lines().filter { it.contains("interact with this repository") },
        )
    }

    @Test
    fun `a repair turn is shown its own errors and told to fix nothing else`() {
        val prompt = acquisitionRepairPrompt(
            compilerOutput = "[ERROR] A.java:[3,4] cannot find symbol\n  symbol: variable NOPE",
            files = mapOf("services/src/main/java/A.java" to "class A { int x = NOPE; }"),
        )
        // The diagnostics and the file contents are the whole input: the agent cannot search, so
        // anything the harness leaves out is a fact it has to invent.
        assertTrue(prompt.contains("cannot find symbol"), prompt)
        assertTrue(prompt.contains("services/src/main/java/A.java"), prompt)
        assertTrue(prompt.contains("class A { int x = NOPE; }"), prompt)
        // Narrow on purpose. A turn that invited more work would let a cell keep developing after its
        // allowance ran out, which is the one thing the allowance exists to prevent.
        assertTrue(prompt.contains("Fix ONLY these compilation errors"), prompt)
        assertTrue(prompt.contains("do not write tests"), prompt)
        assertTrue(prompt.contains("rather than inventing another API"), prompt)
        // Reading the named files is allowed, and saying so is not a courtesy: the CLI refuses to edit
        // a file it has not read, so a turn told it cannot read is a turn that cannot repair anything.
        // Round 5 ran three rounds of exactly that. Searching and building stay gone.
        assertTrue(prompt.contains("You may read and edit the files named below"), prompt)
        assertTrue(prompt.contains("You cannot search or build"), prompt)

        // Bounded, and the count is published: a cell that compiled unaided must stay distinguishable
        // from one that needed every attempt.
        assertEquals(3, ACQUISITION_REPAIR_ROUNDS)
        val line = acquisitionDownstreamLine(
            caseId = case.instanceId,
            condition = UnderstandingCondition.Baseline,
            replicate = 1,
            outcome = UnderstandingDownstreamOutcome(
                success = true, verdict = "Y=1", oracleTestsPassed = 9, oracleTestsTotal = 9,
                compiled = true, cost = UnderstandingCellCost(null, null, null), repairRounds = 2,
            ),
        )
        assertTrue(line.contains("repairRounds=2"), line)
        val older = acquisitionDownstreamLine(
            caseId = case.instanceId,
            condition = UnderstandingCondition.Baseline,
            replicate = 1,
            outcome = UnderstandingDownstreamOutcome(
                success = true, verdict = "Y=1", oracleTestsPassed = 9, oracleTestsTotal = 9,
                compiled = true, cost = UnderstandingCellCost(null, null, null),
            ),
        )
        assertTrue(
            !older.contains("repairRounds="),
            "a cell that ran without the loop must not report zero repairs: $older",
        )
    }

    @Test
    fun `only a pre-registered allowance can be queued`() {
        assertEquals(ACQUISITION_DOWNSTREAM_BUDGET, acquisitionDownstreamBudgetOf(null))
        assertEquals(ACQUISITION_DOWNSTREAM_BUDGET, acquisitionDownstreamBudgetOf("  "))
        assertEquals(15, acquisitionDownstreamBudgetOf("15"))
        // The failure mode this guards is a cell queued generously landing in the same table as the
        // cells queued at twenty, indistinguishable once the build log has scrolled. A GENEROUS NUMBER
        // is still refused — only the one derived from the research budget is not, and a cell run at it
        // prints `budget=60/60`, so it names itself.
        assertThrows(IllegalStateException::class.java) { acquisitionDownstreamBudgetOf("45") }
        assertThrows(IllegalStateException::class.java) { acquisitionDownstreamBudgetOf("100") }
        assertThrows(IllegalStateException::class.java) { acquisitionDownstreamBudgetOf("twenty") }
        // The unbudgeted floor probe: a deliberate absence of a wall, spelled out rather than encoded
        // as a large number that would land in the wave's table looking like a setting.
        assertNull(acquisitionDownstreamBudgetOf(ACQUISITION_DOWNSTREAM_BUDGET_NONE))
        assertNull(acquisitionDownstreamBudgetOf("NONE"))
        // The floor probes have their own allowances, accepted but kept out of the wave's closed set:
        // 60 is the research the shell arm actually spent plus the solver's own twenty.
        assertEquals(60, acquisitionDownstreamBudgetOf("60"))
        assertTrue(
            ACQUISITION_FLOOR_PROBE_BUDGETS.none { it in ACQUISITION_DOWNSTREAM_BUDGETS },
            "a probe allowance must never become a candidate setting for the wave",
        )
        // Both rungs are derived, and the test says so rather than listing them: the matched floor is
        // the research budget plus the solver's allowance, the lower rung is the research bill alone.
        // A rung that stopped being derivable from those two numbers would be a chosen one.
        assertEquals(40, acquisitionDownstreamBudgetOf("40"))
        assertEquals(
            setOf(ACQUISITION_RESEARCH_BUDGET, ACQUISITION_RESEARCH_BUDGET + ACQUISITION_DOWNSTREAM_BUDGET),
            ACQUISITION_FLOOR_PROBE_BUDGETS,
            "every probe allowance must be derived from the research and solver budgets, not chosen",
        )
    }

    @Test
    fun `a probe solver is one rung above the weak model, and never the top of the ladder`() {
        // The guard that reads this set runs inside a container, so what a unit test can hold is the
        // set's own shape — and that is the part a later edit would get wrong. A haiku here would make
        // the exemption a silent duplicate of the model every wave cell already runs on; an opus would
        // buy a reading from a solver that no longer needs a note, which is the one answer the round
        // cannot carry back to the wave.
        assertTrue(ACQUISITION_FLOOR_PROBE_SOLVERS.isNotEmpty(), "the probe has a solver lever or it has none")
        assertTrue(
            ACQUISITION_FLOOR_PROBE_SOLVERS.none { it.contains("haiku", ignoreCase = true) },
            "a probe solver that is itself a haiku exempts nothing: $ACQUISITION_FLOOR_PROBE_SOLVERS",
        )
        assertTrue(
            ACQUISITION_FLOOR_PROBE_SOLVERS.none { it.contains("opus", ignoreCase = true) },
            "an opus solver answers a question the notes were not written for: $ACQUISITION_FLOOR_PROBE_SOLVERS",
        )
    }

    @Test
    fun `success after the wall is not success within the budget`() {
        fun outcome(success: Boolean?, denied: Int?) = UnderstandingDownstreamOutcome(
            success = success,
            verdict = "Y=${if (success == true) 1 else 0}",
            oracleTestsPassed = 9,
            oracleTestsTotal = 9,
            cost = UnderstandingCellCost(null, null, null),
            budget = 20,
            budgetUsed = 20,
            budgetDenied = denied,
        )

        assertEquals(true, outcome(true, 0).successWithinBudget)
        // Solved, but only after pushing against the wall eleven times: that is a cell whose note did
        // not carry it there, and averaging it in as a success would credit the note for the ceiling.
        assertEquals(false, outcome(true, 11).successWithinBudget)
        assertEquals(false, outcome(false, 0).successWithinBudget)
        assertNull(outcome(null, 0).successWithinBudget, "an ungraded cell answers nothing")
        assertNull(outcome(true, null).successWithinBudget, "an unbudgeted cell answers nothing here")
    }

    @Test
    fun `the verdict line carries the accounting the calibration is read from`() {
        val line = acquisitionDownstreamLine(
            caseId = case.instanceId,
            condition = UnderstandingCondition.Baseline,
            replicate = 1,
            outcome = UnderstandingDownstreamOutcome(
                success = false,
                verdict = "Y=0",
                oracleTestsPassed = 2,
                oracleTestsTotal = 9,
                cost = UnderstandingCellCost(usd = 0.4, agentSeconds = 300, outputTokens = 8_000),
                toolCalls = 31,
                budget = 20,
                budgetUsed = 20,
                budgetDenied = 7,
                agentTestsDiscarded = 2,
                agentNonSourceFiles = 28,
            ),
        )
        assertTrue(line.contains("budget=20/20"), line)
        // Amendment 3 removes files from the tree before grading, and a removal nobody can see in the
        // table is a removal nobody can audit.
        assertTrue(line.contains("agentTestsDiscarded=2"), line)
        // The escape hatch free edits create: a blocked agent writes prose about the change instead of
        // the change. One cell produced twenty-eight such files and scored zero with nothing in its row
        // saying why. Recorded, never forbidden.
        assertTrue(line.contains("agentNonSourceFiles=28"), line)
        assertTrue(line.contains("denied=7"), line)
        assertTrue(line.contains("withinBudget=0"), line)
        // An unbudgeted cell must not print zeros for columns it never measured.
        val unbudgeted = acquisitionDownstreamLine(
            caseId = case.instanceId,
            condition = UnderstandingCondition.Baseline,
            replicate = 1,
            outcome = UnderstandingDownstreamOutcome(
                success = false, verdict = "Y=0", oracleTestsPassed = 2, oracleTestsTotal = 9,
                cost = UnderstandingCellCost(null, null, null),
            ),
        )
        assertTrue(!unbudgeted.contains("budget="), unbudgeted)
        assertTrue(!unbudgeted.contains("denied="), unbudgeted)
        assertTrue(
            !unbudgeted.contains("agentTestsDiscarded="),
            "a cell that ran before amendment 3 must not claim it discarded none: $unbudgeted",
        )
        assertTrue(
            !unbudgeted.contains("agentNonSourceFiles="),
            "a cell that predates the column must not claim it wrote none: $unbudgeted",
        )
    }

    @Test
    fun `a committed transcript reads the same gzipped as it does plain`(@TempDir dir: File) {
        val folder = dir.resolve("mcp-b40-l2000-r1").apply { mkdirs() }
        val text = """{"type":"assistant","message":{"id":"m1"}}"""
        GZIPOutputStream(folder.resolve("transcript.ndjson.gz").outputStream()).use {
            it.write(text.toByteArray())
        }

        val found = acquisitionTranscriptIn(folder)
        assertEquals("transcript.ndjson.gz", found?.name)
        assertEquals(text, readAcquisitionTranscript(found!!))

        // A folder with neither form is not a trajectory, and must not silently score as an empty one.
        assertNull(acquisitionTranscriptIn(dir.resolve("nothing-here").apply { mkdirs() }))
    }
}
