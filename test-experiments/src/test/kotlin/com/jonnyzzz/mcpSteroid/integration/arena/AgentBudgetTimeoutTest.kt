/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.process.PROCESS_TIMEOUT_EXIT_CODE
import com.jonnyzzz.mcpSteroid.testHelper.process.PROCESS_TIMEOUT_STDERR_MARKER
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [agentRunTimedOut] against what the harness really reports when an agent runs out of budget.
 *
 * The distinction it draws is a grading rule, not a cosmetic one: exhausting the 30-minute budget every
 * replicate shares is what a probe FAILING at the task looks like, so such a cell is an unsuccess
 * (`Y=0`) folded into `V`. Only the INSTRUMENT failing withholds a verdict. The measured cell is
 * TeamCity build 1035674856 (`arm=mcp checkpoint=2 replicate=3`), which printed
 * `LOST reason=not-graded` after 1800 s and exit -1 and thereby left the sample silently.
 *
 * Both halves of the signal are produced by ONE line of `ProcessRunner.awaitForProcessFinish`, which is
 * why they are asserted together: the timeout branch destroys the process, returns
 * [PROCESS_TIMEOUT_EXIT_CODE] and prefixes stderr with [PROCESS_TIMEOUT_STDERR_MARKER]. The literals
 * are spelled out here on purpose — the production code reads them from the constants next to their
 * producer, so a test that used the same constants would pin nothing.
 */
class AgentBudgetTimeoutTest {
    /**
     * The exact value the runner returns, literals and all, for a process it killed on the timeout.
     * `stderr` is the empty-stderr case: the agent was killed before writing anything of its own.
     */
    @Test
    fun `the harness killing the agent on its own timeout is a budget exhaustion`() {
        val killed = ProcessResultValue(
            -1,
            "…9 Reads, 0 Edits…",
            "Terminated by timeout\n\n\n ERROR: Terminated by timeout",
        )

        assertTrue(agentRunTimedOut(killed)) {
            "exit=${killed.exitCode} with '${killed.stderr.lineSequence().first()}' on stderr is the " +
                "runner's own timeout report and nothing else"
        }
    }

    /** The same shape with the agent's own stderr folded in, as a real run produces it. */
    @Test
    fun `the agent's own stderr does not hide the timeout marker`() {
        val killed = ProcessResultValue(
            PROCESS_TIMEOUT_EXIT_CODE,
            "",
            "Terminated by timeout\nnode:internal/process/promises rejection\n\n ERROR: Terminated by timeout",
        )

        assertTrue(agentRunTimedOut(killed))
    }

    /**
     * The transport-abort cell: the CLI exited 1 by itself after 26 seconds. It had budget left, so it
     * is not a budget exhaustion — it is [extractApiTransportError]'s case and stays a LOST.
     */
    @Test
    fun `an agent that exited by itself is not out of budget`() {
        assertFalse(agentRunTimedOut(result(1, "API Error: Connection closed mid-response.")))
    }

    @Test
    fun `a clean run is not out of budget`() {
        assertFalse(agentRunTimedOut(result(0, "")))
    }

    /**
     * The container dying takes the exit code down with it but never writes the runner's marker, and it
     * must keep withholding a verdict: nothing about it says the agent had a full budget to work in.
     */
    @Test
    fun `a dead container is not a budget exhaustion`() {
        assertFalse(agentRunTimedOut(result(-1, "Error response from daemon: No such container: ide-1")))
        assertFalse(agentRunTimedOut(result(137, "")))
    }

    /**
     * A transcript that merely CONTAINS the marker proves nothing — the agent may have read a log of an
     * earlier run. The exit code is what says the harness, and not the agent, ended the process.
     */
    @Test
    fun `quoting the timeout marker is not a budget exhaustion`() {
        assertFalse(agentRunTimedOut(result(0, "the log says: Terminated by timeout")))
        assertFalse(agentRunTimedOut(result(1, " ERROR: Terminated by timeout")))
    }

    /**
     * An absent exit code means nobody observed how the process ended, which is an instrument failure
     * and not a measured budget exhaustion.
     */
    @Test
    fun `an unobserved exit is not a budget exhaustion`() {
        assertFalse(agentRunTimedOut(result(null, PROCESS_TIMEOUT_STDERR_MARKER)))
    }

    /**
     * One process result with only the two fields the predicate reads.
     *
     * Not [ProcessResultValue]: that one's `exitCode` is non-null, and an unobserved exit is one of the
     * cases this class has to be able to express.
     */
    private fun result(exitCode: Int?, stderr: String): ProcessResult =
        ObservedProcessResult(exitCode = exitCode, stdout = "", stderr = stderr)
}

/** A finished process as only its three observed fields, so a test can express an absent exit code. */
private data class ObservedProcessResult(
    override val exitCode: Int?,
    override val stdout: String,
    override val stderr: String,
) : ProcessResult
