/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pins [extractApiTransportError] against the ONE shape that is not the agent's fault.
 *
 * The measured case is TeamCity build 1035679682 (`arm=none checkpoint=5 step=33 replicate=1`), which
 * published `Y=0 usd=0.0672 agentSeconds=26 tokens=0` after 9 Reads and 0 Edits: Anthropic closed the
 * connection, the Claude CLI injected a `"model":"<synthetic>"` assistant message carrying
 * `API Error: …`, emitted a top-level `"error":"server_error"` and exited 1. Nothing about that run
 * says the recorded state was too far from a solution — it says the probe never got to try — so it
 * must not be folded into `V` as a zero.
 *
 * The negatives are the point of this class as much as the positive. A tool that returned
 * `is_error: true`, an agent that ran out of its own budget, and an ordinary assistant turn are all
 * the agent's own problem and stay graded; widening the predicate to any of them would start
 * discarding real failures and inflate every readiness value.
 */
class AgentOutputTransportErrorTest {

    /**
     * The exact event Claude Code injects when the HTTP stream dies: a synthetic assistant turn whose
     * `model` is the literal `<synthetic>` — no real model id can collide with it — carrying the CLI's
     * own `API Error:` text as its content. Both halves are required, because the CLI also synthesizes
     * assistant turns for other, harmless reasons.
     */
    @Test
    fun `a synthetic assistant turn carrying an API Error is a transport abort`() {
        val ndjson = listOf(
            """{"type":"assistant","message":{"id":"msg_1","role":"assistant","model":"claude-haiku-4-5",""" +
                """"content":[{"type":"text","text":"Let me read the failing test."}],""" +
                """"usage":{"input_tokens":12,"output_tokens":30}}}""",
            """{"type":"assistant","message":{"id":"msg_2","type":"message","role":"assistant",""" +
                """"model":"<synthetic>","stop_reason":"stop_sequence","stop_sequence":"","content":""" +
                """[{"type":"text","text":"API Error: Connection closed mid-response. The response """ +
                """above may be incomplete."}],"usage":{"input_tokens":0,"output_tokens":0}}}""",
        ).joinToString("\n")

        assertEquals(
            "API Error: Connection closed mid-response. The response above may be incomplete.",
            extractApiTransportError(ndjson),
        )
    }

    /**
     * The second half of the same failure, and its own signal: the CLI closes the stream with a
     * top-level `error` string. It is matched independently because the two do not always arrive
     * together — a stream can be cut before the synthetic turn is written at all.
     */
    @Test
    fun `a top-level server_error event is a transport abort on its own`() {
        val ndjson = """{"type":"result","subtype":"error_during_execution","error":"server_error"}"""

        assertEquals("server_error", extractApiTransportError(ndjson))
    }

    /**
     * The descriptive message wins over the terse one when a run carries both, which is what the
     * measured build did. `server_error` alone tells an operator nothing about what to re-queue.
     */
    @Test
    fun `the CLI's own message is preferred over the bare error code`() {
        val ndjson = listOf(
            """{"type":"assistant","message":{"model":"<synthetic>","stop_reason":"stop_sequence",""" +
                """"content":[{"type":"text","text":"API Error: Connection closed mid-response."}]}}""",
            """{"type":"result","subtype":"error_during_execution","error":"server_error"}""",
        ).joinToString("\n")

        assertEquals("API Error: Connection closed mid-response.", extractApiTransportError(ndjson))
    }

    @Test
    fun `an ordinary successful run carries no transport error`() {
        val ndjson = listOf(
            """{"type":"system","subtype":"init","model":"claude-haiku-4-5"}""",
            """{"type":"assistant","message":{"model":"claude-haiku-4-5","content":[{"type":"tool_use",""" +
                """"id":"tu_1","name":"Edit","input":{"file_path":"/p/A.java"}}]}}""",
            """{"type":"result","subtype":"success","total_cost_usd":0.42,"num_turns":5,""" +
                """"usage":{"input_tokens":15000,"output_tokens":3000}}""",
        ).joinToString("\n")

        assertNull(extractApiTransportError(ndjson))
    }

    /**
     * A tool that failed is the agent's own problem: it saw the error, and finishing anyway was the
     * task. `is_error` on a `tool_result` is counted by [extractToolCallStats] and must never reach
     * this predicate — a state whose continuation kept breaking its own commands is exactly the kind
     * of `Y=0` the pilot is trying to measure.
     */
    @Test
    fun `a tool_result marked is_error is the agent's own failure and stays graded`() {
        val ndjson = listOf(
            """{"type":"assistant","message":{"model":"claude-haiku-4-5","content":[{"type":"tool_use",""" +
                """"id":"tu_1","name":"Bash","input":{"command":"mvn -q test"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result",""" +
                """"tool_use_id":"tu_1","is_error":true,"content":"BUILD FAILURE"}]}}""",
            """{"type":"result","subtype":"success","total_cost_usd":0.11,"usage":{"input_tokens":10,"output_tokens":2}}""",
        ).joinToString("\n")

        assertNull(extractApiTransportError(ndjson))
        assertEquals(1, extractToolCallStats(ndjson)!!.toolErrorCount)
    }

    /**
     * A run killed by its OWN timeout is a real `Y=0`: it was handed the state, it edited files, and it
     * did not finish in the budget every other cell also had. Its transcript simply stops mid-turn —
     * there is no synthetic message and no `error` field — and the harness's terminal
     * `error_during_execution` (without an `error` string) must not be mistaken for one either.
     */
    @Test
    fun `a run that hit its own timeout is not a transport abort`() {
        val ndjson = listOf(
            """{"type":"assistant","message":{"model":"claude-haiku-4-5","content":[{"type":"tool_use",""" +
                """"id":"tu_1","name":"Edit","input":{"file_path":"/p/A.java"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tu_1","content":"ok"}]}}""",
            """{"type":"assistant","message":{"model":"claude-haiku-4-5","content":[{"type":"tool_use",""" +
                """"id":"tu_2","name":"Bash","input":{"command":"mvn -q test"}}]}}""",
            """{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":41}""",
        ).joinToString("\n")

        assertNull(extractApiTransportError(ndjson))
    }

    /**
     * `API Error:` prose an agent happens to WRITE is not a transport abort. Only the CLI's own
     * synthetic turn is, which is what the `<synthetic>` model check is for — an agent reading a log
     * and quoting it back must stay graded.
     */
    @Test
    fun `an agent quoting the words API Error in its own turn stays graded`() {
        val ndjson = """{"type":"assistant","message":{"model":"claude-haiku-4-5","content":""" +
            """[{"type":"text","text":"API Error: is what the log says; I will retry the request."}]}}"""

        assertNull(extractApiTransportError(ndjson))
    }

    /** Non-JSON console noise around the transcript must not hide the signal, nor invent one. */
    @Test
    fun `console noise around the stream is ignored`() {
        val ndjson = listOf(
            "Starting agent…",
            """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text",""" +
                """"text":"API Error: Connection error."}]}}""",
            "not json at all",
        ).joinToString("\n")

        assertEquals("API Error: Connection error.", extractApiTransportError(ndjson))
        assertNull(extractApiTransportError("Starting agent…\nnot json at all\n"))
    }

    /**
     * The signal has to survive the trip from the transcript to the reader, because that is where the
     * measured build lost it: [collectRunMetrics] is the only thing that reads the raw NDJSON, and a
     * value it does not carry cannot be acted on by any seam. Asserted over the PERSISTED transcript
     * rather than a string, because the console-filtered stdout drops these events — the same plumbing
     * bug that once hid Codex's usage entirely.
     */
    @Test
    fun `the collected run metrics carry the transport error under its own name`() {
        val runDir = tempDir.resolve("run").also { it.mkdirs() }
        runDir.resolve("agent-claude-code-1-raw.ndjson").writeText(
            """{"type":"assistant","message":{"model":"<synthetic>","stop_reason":"stop_sequence",""" +
                """"content":[{"type":"text","text":"API Error: Connection closed mid-response."}]}}""" +
                "\n" + """{"type":"result","subtype":"error_during_execution","error":"server_error"}""",
        )

        val metrics = collectRunMetrics(runDir, agentName = "claude", fallbackStdout = "")
        assertEquals("API Error: Connection closed mid-response.", metrics.apiTransportError)
    }

    @Test
    fun `a healthy run reports no transport error to its reader`() {
        val runDir = tempDir.resolve("healthy").also { it.mkdirs() }
        runDir.resolve("agent-claude-code-1-raw.ndjson").writeText(
            """{"type":"result","subtype":"success","total_cost_usd":0.42,"usage":{"input_tokens":10,"output_tokens":2}}""",
        )

        assertNull(collectRunMetrics(runDir, agentName = "claude", fallbackStdout = "").apiTransportError)
    }

    @TempDir
    lateinit var tempDir: File
}
