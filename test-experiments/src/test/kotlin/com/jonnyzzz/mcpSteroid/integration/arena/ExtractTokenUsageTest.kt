/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pins [extractTokenUsage] against BOTH agent CLIs' real usage shapes.
 *
 * Codex was invisible here: the parser only accepted Claude's terminal `{"type":"result", …}` event,
 * so every Codex arena run recorded blank tokens and blank cost — the whitepaper's cost columns were
 * empty for the entire gpt-5.6 pass and had to be reconstructed by hand from the raw NDJSON bundles.
 * Codex reports usage on `turn.completed` instead.
 *
 * The two CLIs also disagree on what `input_tokens` MEANS, which is the subtle half of this:
 *  - Claude reports `input_tokens` EXCLUDING cache traffic, alongside `cache_read_input_tokens` and
 *    `cache_creation_input_tokens`;
 *  - Codex reports `input_tokens` as the whole prompt, with `cached_input_tokens` and
 *    `cache_write_input_tokens` as subsets of it.
 * [TokenUsage.inputTokens] therefore always means "fresh, non-cached prompt tokens", and the Codex
 * branch subtracts. Without that normalization a Codex run looks ~10x more expensive than a Claude
 * run doing identical work, purely from double-counting cache reads.
 */
class ExtractTokenUsageTest {

    @Test
    fun `claude terminal result event is read as-is`() {
        // One JSON object per physical line — NDJSON, exactly as the CLI writes it.
        val ndjson = listOf(
            """{"type":"assistant","message":{"role":"assistant","content":[]}}""",
            """{"type":"result","subtype":"success","total_cost_usd":0.42,"num_turns":5,""" +
                """"duration_api_ms":1234,"usage":{"input_tokens":15000,"output_tokens":3000,""" +
                """"cache_read_input_tokens":12000,"cache_creation_input_tokens":800}}""",
        ).joinToString("\n")

        val usage = extractTokenUsage(ndjson)!!
        assertEquals(15000L, usage.inputTokens)
        assertEquals(3000L, usage.outputTokens)
        assertEquals(12000L, usage.cacheReadTokens)
        assertEquals(800L, usage.cacheCreationTokens)
        assertEquals(0.42, usage.costUsd!!, 1e-9)
        assertEquals(5, usage.numTurns)
        assertEquals(1234L, usage.durationApiMs)
    }

    @Test
    fun `codex turn-completed usage is normalized to fresh input tokens`() {
        // Real shape from a gpt-5.6-sol arena run (TC build 1022752583, codex+none).
        val ndjson = listOf(
            """{"type":"thread.started","thread_id":"t1"}""",
            """{"type":"turn.started"}""",
            """{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"done"}}""",
            """{"type":"turn.completed","usage":{"input_tokens":696646,"cached_input_tokens":643057,""" +
                """"cache_write_input_tokens":53523,"output_tokens":3174,"reasoning_output_tokens":447}}""",
        ).joinToString("\n")

        val usage = extractTokenUsage(ndjson)!!
        // 696646 total prompt - 643057 cache reads - 53523 cache writes = 66 genuinely fresh
        assertEquals(66L, usage.inputTokens)
        assertEquals(643057L, usage.cacheReadTokens)
        assertEquals(53523L, usage.cacheCreationTokens)
        assertEquals(3174L, usage.outputTokens)
        assertEquals(1, usage.numTurns)
        // Codex does not self-report a dollar figure; inventing one from a guessed rate would be worse
        // than leaving the column honest.
        assertNull(usage.costUsd)
    }

    @Test
    fun `codex usage is summed across every turn`() {
        val ndjson = listOf(
            """{"type":"turn.completed","usage":{"input_tokens":1000,"cached_input_tokens":600,""" +
                """"cache_write_input_tokens":300,"output_tokens":50,"reasoning_output_tokens":10}}""",
            """{"type":"turn.completed","usage":{"input_tokens":2000,"cached_input_tokens":1500,""" +
                """"cache_write_input_tokens":400,"output_tokens":70,"reasoning_output_tokens":20}}""",
        ).joinToString("\n")

        val usage = extractTokenUsage(ndjson)!!
        assertEquals(200L, usage.inputTokens) // (1000-600-300) + (2000-1500-400)
        assertEquals(2100L, usage.cacheReadTokens)
        assertEquals(700L, usage.cacheCreationTokens)
        assertEquals(120L, usage.outputTokens)
        assertEquals(2, usage.numTurns)
    }

    @Test
    fun `a codex run that never reported a completed turn yields no usage`() {
        // Observed on 2 of 24 arms in the gpt-5.6 pass: the stream ends at the final agent_message,
        // so there is genuinely nothing to report and a zero-filled row would be a lie.
        val ndjson = listOf(
            """{"type":"thread.started","thread_id":"t1"}""",
            """{"type":"turn.started"}""",
            """{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"ARENA_FIX_APPLIED: yes"}}""",
        ).joinToString("\n")

        assertNull(extractTokenUsage(ndjson))
    }

    @Test
    fun `cached counters larger than the reported prompt never produce negative input`() {
        val ndjson = """{"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":40,"output_tokens":5}}"""
        assertEquals(0L, extractTokenUsage(ndjson)!!.inputTokens)
    }

    @Test
    fun `output without any usage information is not usage`() {
        assertNull(extractTokenUsage("hello\nnot json\n"))
    }

    // ── The plumbing half of the same bug ─────────────────────────────────────────────────────
    //
    // Teaching the parser Codex's shape was not enough: the metrics were being fed
    // `agentResult.stdout`, which is the console-FILTERED stream (`>> steroid_execute_code`, …).
    // The usage event only exists in the unfiltered transcript the session driver persists, so both
    // tokens and test metrics stayed blank for Codex until the source changed.

    @Test
    fun `the raw transcript is found and preferred over the decoded one`() {
        val runDir = tempDir.resolve("run").also { it.mkdirs() }
        runDir.resolve("agent-codex-1-decoded.txt").writeText(">> steroid_execute_code\n")
        runDir.resolve("agent-codex-1-raw.ndjson").writeText(
            """{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":40,"output_tokens":7}}""",
        )

        val raw = findRawNdjsonFile(runDir, agentName = "codex")
        assertEquals("agent-codex-1-raw.ndjson", raw!!.name)
        // …and the whole chain now yields usage where it previously yielded null.
        val usage = extractTokenUsage(raw.readText())!!
        assertEquals(60L, usage.inputTokens)
        assertEquals(7L, usage.outputTokens)
    }

    @Test
    fun `the newest transcript wins when a run issued several prompts`() {
        val runDir = tempDir.resolve("multi").also { it.mkdirs() }
        val first = runDir.resolve("agent-claude-code-1-raw.ndjson")
        first.writeText("{}")
        first.setLastModified(1_000_000L)
        val second = runDir.resolve("agent-claude-code-2-raw.ndjson")
        second.writeText("{}")
        second.setLastModified(2_000_000L)

        assertEquals("agent-claude-code-2-raw.ndjson", findRawNdjsonFile(runDir, "claude-code")!!.name)
    }

    @Test
    fun `a run directory with no transcript yields null so the caller can fall back`() {
        val runDir = tempDir.resolve("empty").also { it.mkdirs() }
        runDir.resolve("agent-codex-1-decoded.txt").writeText("decoded only")
        assertNull(findRawNdjsonFile(runDir, agentName = "codex"))
    }

    @TempDir
    lateinit var tempDir: File
}
