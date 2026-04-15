/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Shared data classes and extraction functions for parsing agent output metrics.
 *
 * Used by arena tests to extract token usage, test results, and tool call statistics
 * from Claude NDJSON stream-json output.
 */

// ── Data classes ─────────────────────────────────────────────────────────────

data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0L,
    val costUsd: Double? = null,
    val numTurns: Int? = null,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class TestMetrics(
    val testsRun: Int,
    val testsPass: Int,
    val testsFail: Int,
    val testsError: Int,
    val buildSuccess: Boolean?,
)

data class ToolCallStats(
    /** Number of steroid_execute_code tool invocations. */
    val steroidCallCount: Int,
    /** Total tool_use calls across all assistant turns. */
    val totalToolCalls: Int,
    /** Number of tool results that returned is_error=true. */
    val toolErrorCount: Int,
)

// ── Extraction functions ─────────────────────────────────────────────────────

private val TEST_RESULT_REGEX = Regex("""Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)""")
private val BUILD_STATUS_REGEX = Regex("""BUILD (SUCCESS|FAILURE)""")

/**
 * Extract Maven/Gradle test metrics from agent output.
 *
 * Looks for the standard Surefire summary line:
 * ```
 * Tests run: 47, Failures: 0, Errors: 0, Skipped: 0
 * BUILD SUCCESS
 * ```
 * Takes the LAST match, which corresponds to the final test run summary.
 */
fun extractTestMetrics(rawOutput: String): TestMetrics? {
    val matches = TEST_RESULT_REGEX.findAll(rawOutput).toList()
    if (matches.isEmpty()) return null

    val last = matches.last()
    val testsRun = last.groupValues[1].toInt()
    val testsFail = last.groupValues[2].toInt()
    val testsError = last.groupValues[3].toInt()
    val testsPass = testsRun - testsFail - testsError

    val buildSuccess = BUILD_STATUS_REGEX.findAll(rawOutput).toList()
        .lastOrNull()?.groupValues?.get(1)?.let { it == "SUCCESS" }

    return TestMetrics(testsRun, testsPass, testsFail, testsError, buildSuccess)
}

/**
 * Extract Claude token usage from stream-json NDJSON output.
 *
 * Claude CLI `--output-format stream-json` writes NDJSON where the last "result" event is:
 * ```json
 * {"type":"result","subtype":"success","total_cost_usd":0.01,"num_turns":5,
 *  "usage":{"input_tokens":15000,"output_tokens":3000,"cache_read_input_tokens":12000}}
 * ```
 */
fun extractTokenUsage(rawOutput: String): TokenUsage? {
    for (line in rawOutput.lines().asReversed()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val json = try {
            Json.parseToJsonElement(trimmed).jsonObject
        } catch (_: Exception) {
            continue
        }
        if (json["type"]?.jsonPrimitive?.content != "result") continue
        val usage = json["usage"]?.jsonObject ?: return null
        return TokenUsage(
            inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            costUsd = (json["total_cost_usd"] ?: json["cost_usd"])?.jsonPrimitive?.doubleOrNull,
            numTurns = json["num_turns"]?.jsonPrimitive?.intOrNull,
        )
    }
    return null
}

// ── Decoded log metrics ──────────────────────────────────────────────────────

data class DecodedLogMetrics(
    /** Number of steroid_execute_code invocations (lines containing "steroid_execute_code"). */
    val execCodeCalls: Int,
    /** Number of Read tool invocations (lines starting with ">> Read"). */
    val readCalls: Int,
    /** Number of Write tool invocations (lines starting with ">> Write"). */
    val writeCalls: Int,
    /** Number of Bash tool invocations (lines starting with ">> Bash"). */
    val bashCalls: Int,
)

/**
 * Parse decoded agent log text and count tool invocation lines.
 *
 * The decoded log format (written by [ConsoleAwareAgentSession]) uses `>> ToolName (detail)` lines.
 * Actual examples from Claude:
 * - `>> mcp__mcp-steroid__steroid_execute_code (reason text)`
 * - `>> Read (/path/to/file)`
 * - `>> Write (/path/to/file)`
 * - `>> Bash (command)`
 *
 * Returns null when the text contains no `>>` tool lines at all (e.g. agent never produced decoded output).
 */
fun extractDecodedLogMetrics(decodedLogText: String): DecodedLogMetrics? {
    var execCodeCalls = 0
    var readCalls = 0
    var writeCalls = 0
    var bashCalls = 0
    var foundAny = false

    for (line in decodedLogText.lines()) {
        if (!line.startsWith(">> ")) continue
        foundAny = true
        when {
            line.contains("steroid_execute_code") -> execCodeCalls++
            line.startsWith(">> Read ") || line == ">> Read" -> readCalls++
            line.startsWith(">> Write ") || line == ">> Write" -> writeCalls++
            line.startsWith(">> Bash ") || line == ">> Bash" -> bashCalls++
        }
    }

    return if (foundAny) DecodedLogMetrics(execCodeCalls, readCalls, writeCalls, bashCalls) else null
}

/**
 * Find the most-recently-modified decoded log file in [runDir] whose name matches
 * `agent-<agentName>-*-decoded.txt`.
 *
 * Returns null if no matching file exists.
 */
fun findDecodedLogFile(runDir: java.io.File, agentName: String = "claude-code"): java.io.File? {
    val safeName = agentName.replace(' ', '-').lowercase()
    return runDir.listFiles { f ->
        f.name.startsWith("agent-$safeName-") && f.name.endsWith("-decoded.txt")
    }?.maxByOrNull { it.lastModified() }
}

/**
 * Extract tool call statistics from Claude NDJSON output.
 *
 * Counts:
 * - `steroid_execute_code` calls (full or bare name)
 * - total `tool_use` blocks
 * - tool results with `is_error: true`
 */
fun extractToolCallStats(rawOutput: String): ToolCallStats? {
    var steroidCount = 0
    var totalCount = 0
    var errorCount = 0
    var foundAny = false

    for (line in rawOutput.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val obj = try {
            Json.parseToJsonElement(trimmed).jsonObject
        } catch (_: Exception) {
            continue
        }

        when (obj["type"]?.jsonPrimitive?.content) {
            "assistant" -> {
                val content = obj["message"]?.jsonObject
                    ?.get("content")?.let { el ->
                        try { el.jsonArray } catch (_: Exception) { null }
                    } ?: continue
                for (item in content) {
                    val itemObj = try { item.jsonObject } catch (_: Exception) { continue }
                    if (itemObj["type"]?.jsonPrimitive?.content == "tool_use") {
                        totalCount++
                        foundAny = true
                        val name = itemObj["name"]?.jsonPrimitive?.content ?: ""
                        if (name.endsWith("steroid_execute_code")) {
                            steroidCount++
                        }
                    }
                }
            }
            "user" -> {
                val content = obj["message"]?.jsonObject
                    ?.get("content")?.let { el ->
                        try { el.jsonArray } catch (_: Exception) { null }
                    } ?: continue
                for (item in content) {
                    val itemObj = try { item.jsonObject } catch (_: Exception) { continue }
                    if (itemObj["type"]?.jsonPrimitive?.content == "tool_result") {
                        if (itemObj["is_error"]?.jsonPrimitive?.content == "true") {
                            errorCount++
                        }
                    }
                }
            }
        }
    }

    return if (foundAny) ToolCallStats(steroidCount, totalCount, errorCount) else null
}
