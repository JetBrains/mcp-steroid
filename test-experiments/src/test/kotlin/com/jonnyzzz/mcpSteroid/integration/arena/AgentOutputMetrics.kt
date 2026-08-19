/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val cacheCreationTokens: Long = 0L,
    val costUsd: Double? = null,
    val numTurns: Int? = null,
    val durationApiMs: Long? = null,
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
 * The size of the agent's context at the END of the run, or null when the stream carries no assistant
 * usage at all.
 *
 * `input + cache_read + cache_creation + output` of the LAST assistant message — the definition the
 * RIPPLE v3 trajectory analysis used, and therefore the only quantity comparable with the historical
 * bands in [CaptureReference]. Deliberately not derived from [TokenUsage]: the terminal `result` event
 * reports CUMULATIVE traffic over the whole run (its cache-read counter reached 969851 on a run whose
 * context was ~75k), and `TokenUsage.totalTokens` is `input + output` only, which is a third quantity
 * again. Comparing any of the three against a band built from another rejects every run — see
 * [admitCapture].
 */
fun extractEndContextTokens(rawOutput: String): Long? {
    for (line in rawOutput.lines().asReversed()) {
        val json = parseJsonObjectOrNull(line) ?: continue
        if (json["type"]?.jsonPrimitive?.content != "assistant") continue
        val usage = json["message"]?.jsonObject?.get("usage")?.jsonObject ?: continue
        val fields = listOf(
            "input_tokens",
            "cache_read_input_tokens",
            "cache_creation_input_tokens",
            "output_tokens",
        )
        return fields.sumOf { usage[it]?.jsonPrimitive?.longOrNull ?: 0L }
    }
    return null
}

/**
 * Extract token usage from either agent CLI's NDJSON output.
 *
 * Claude CLI `--output-format stream-json` ends with a terminal result event:
 * ```json
 * {"type":"result","subtype":"success","total_cost_usd":0.01,"num_turns":5,
 *  "usage":{"input_tokens":15000,"output_tokens":3000,"cache_read_input_tokens":12000}}
 * ```
 * Codex `exec --json` has no such event and reports per-turn instead:
 * ```json
 * {"type":"turn.completed","usage":{"input_tokens":696646,"cached_input_tokens":643057,
 *  "cache_write_input_tokens":53523,"output_tokens":3174,"reasoning_output_tokens":447}}
 * ```
 *
 * [TokenUsage.inputTokens] always means FRESH, non-cached prompt tokens. Claude already reports it that
 * way (cache traffic sits in its own fields); Codex's `input_tokens` is the WHOLE prompt with the cache
 * counters as subsets, so the Codex branch subtracts them. Skipping that normalization would count
 * cache reads twice and make a Codex run look an order of magnitude more expensive than a Claude run
 * doing the same work. Codex reports no dollar figure, so [TokenUsage.costUsd] stays null for it
 * rather than being derived from a hardcoded price that would silently go stale.
 */
fun extractTokenUsage(rawOutput: String): TokenUsage? {
    val lines = rawOutput.lines()

    // Claude: single terminal event, so the last one wins.
    for (line in lines.asReversed()) {
        val json = parseJsonObjectOrNull(line) ?: continue
        if (json["type"]?.jsonPrimitive?.content != "result") continue
        val usage = json["usage"]?.jsonObject ?: return null
        return TokenUsage(
            inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            cacheCreationTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            costUsd = (json["total_cost_usd"] ?: json["cost_usd"])?.jsonPrimitive?.doubleOrNull,
            numTurns = json["num_turns"]?.jsonPrimitive?.intOrNull,
            durationApiMs = json["duration_api_ms"]?.jsonPrimitive?.longOrNull,
        )
    }

    // Codex: accumulate every completed turn.
    var totalPrompt = 0L
    var cacheRead = 0L
    var cacheWrite = 0L
    var output = 0L
    var turns = 0
    for (line in lines) {
        val json = parseJsonObjectOrNull(line) ?: continue
        if (json["type"]?.jsonPrimitive?.content != "turn.completed") continue
        val usage = json["usage"]?.jsonObject ?: continue
        turns++
        totalPrompt += usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        cacheRead += usage["cached_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        cacheWrite += usage["cache_write_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        output += usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
    }
    if (turns == 0) return null
    return TokenUsage(
        inputTokens = (totalPrompt - cacheRead - cacheWrite).coerceAtLeast(0L),
        outputTokens = output,
        cacheReadTokens = cacheRead,
        cacheCreationTokens = cacheWrite,
        costUsd = null,
        numTurns = turns,
        durationApiMs = null,
    )
}

/**
 * The literal `model` Claude Code stamps on an assistant turn IT wrote rather than one the API
 * returned. No real model id can collide with it, which is what makes it usable as a marker.
 */
private const val SYNTHETIC_MODEL: String = "<synthetic>"

/** The prefix of the CLI's own transport-failure text, e.g. `API Error: Connection closed mid-response.` */
private const val API_ERROR_TEXT_PREFIX: String = "API Error:"

/**
 * The message of a TRANSPORT-level abort of the agent's own API connection, or null for every other
 * run — including every run that simply failed at its task.
 *
 * This exists because such a run is not a measurement of the agent at all, and the checkpoint pilot
 * publishes one number per run. TeamCity build 1035679682 (`arm=none checkpoint=5 step=33
 * replicate=1`) published `Y=0 usd=0.0672 agentSeconds=26 tokens=0`: after 26 seconds, 9 Reads and 0
 * Edits, Anthropic closed the connection mid-response, the CLI injected a `"model":"<synthetic>"`
 * assistant turn carrying `API Error: Connection closed mid-response. The response above may be
 * incomplete.`, emitted a top-level `"error":"server_error"` and exited 1. That zero was read as "the
 * recorded state was too far from a solution" and pulled the readiness value for that state down.
 *
 * Two shapes, both narrow on purpose:
 *  1. an assistant event whose `model` is exactly [SYNTHETIC_MODEL] **and** whose text starts with
 *     [API_ERROR_TEXT_PREFIX] — the model check is what separates the CLI's own injected turn from an
 *     agent that merely quotes the words "API Error:" out of a log it read;
 *  2. an event carrying a top-level `error` STRING (`"error":"server_error"`), which is how the stream
 *     ends when it is cut before any synthetic turn is written.
 *
 * Deliberately NOT matched, because each of them is the agent's own problem and a real `Y=0`:
 * a `tool_result` with `"is_error":true` (a command the agent ran failed — [extractToolCallStats]
 * counts those), a terminal `"subtype":"error_during_execution"` without an `error` string, and a
 * transcript that simply stops because the run hit its own timeout. Widening to any of those would
 * start discarding runs that failed at the TASK, which is precisely what `V` is a fraction of.
 *
 * The descriptive message wins over the bare code when a stream carries both: `server_error` alone
 * tells an operator nothing about what happened to the cell they now have to re-queue.
 */
fun extractApiTransportError(rawOutput: String): String? {
    var bareErrorCode: String? = null
    for (line in rawOutput.lines()) {
        val json = parseJsonObjectOrNull(line) ?: continue
        syntheticApiErrorText(json)?.let { return it }
        if (bareErrorCode == null) {
            bareErrorCode = (json["error"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.ifBlank { null }
        }
    }
    return bareErrorCode
}

/** The `API Error: …` text of a CLI-synthesized assistant turn, or null when [event] is not one. */
private fun syntheticApiErrorText(event: JsonObject): String? {
    if (event["type"]?.jsonPrimitive?.content != "assistant") return null
    val message = event["message"] as? JsonObject ?: return null
    if (message["model"]?.jsonPrimitive?.content != SYNTHETIC_MODEL) return null
    val content = message["content"] as? JsonArray ?: return null
    return content.mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { block ->
        (block["text"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.trimStart().startsWith(API_ERROR_TEXT_PREFIX) }
    }
}

private fun parseJsonObjectOrNull(line: String): JsonObject? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        Json.parseToJsonElement(trimmed).jsonObject
    } catch (_: Exception) {
        null
    }
}

// ── Decoded log metrics ──────────────────────────────────────────────────────

data class DecodedLogMetrics(
    /** Number of steroid_execute_code invocations (lines containing "steroid_execute_code"). */
    val execCodeCalls: Int,
    /** Number of Read tool invocations (lines starting with ">> Read"). */
    val readCalls: Int,
    /** Number of Write or Edit tool invocations. */
    val writeCalls: Int,
    /** Number of Bash tool invocations (lines starting with ">> Bash"). */
    val bashCalls: Int,
    /** Number of Glob tool invocations (lines starting with ">> Glob"). */
    val globCalls: Int = 0,
    /** Number of Grep tool invocations (lines starting with ">> Grep"). */
    val grepCalls: Int = 0,
    /** Number of Edit tool invocations (lines starting with ">> Edit"). */
    val editCalls: Int = 0,
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
/** Shells Codex spawns directly, as they appear after the `>> ` marker in a decoded transcript. */
private val CODEX_SHELLS = listOf("/bin/bash", "/bin/sh", "/usr/bin/bash", "/usr/bin/sh", "bash", "sh")

private fun String.isCodexShellInvocation(): Boolean {
    val command = removePrefix(">> ").trimStart()
    return CODEX_SHELLS.any { command == it || command.startsWith("$it ") }
}

fun extractDecodedLogMetrics(decodedLogText: String): DecodedLogMetrics? {
    var execCodeCalls = 0
    var readCalls = 0
    var writeCalls = 0
    var bashCalls = 0
    var globCalls = 0
    var grepCalls = 0
    var editCalls = 0
    var foundAny = false

    for (line in decodedLogText.lines()) {
        if (!line.startsWith(">> ")) continue
        foundAny = true
        when {
            line.contains("steroid_execute_code") -> execCodeCalls++
            line.startsWith(">> Read ") || line == ">> Read" -> readCalls++
            line.startsWith(">> Write ") || line == ">> Write" -> writeCalls++
            line.startsWith(">> Edit ") || line == ">> Edit" -> editCalls++
            line.startsWith(">> Bash ") || line == ">> Bash" -> bashCalls++
            line.startsWith(">> Glob ") || line == ">> Glob" -> globCalls++
            line.startsWith(">> Grep ") || line == ">> Grep" -> grepCalls++
            // Codex has no `Bash` tool — it invokes the shell directly, so its decoded line is
            // `>> /bin/bash -lc '…'`. Without this every Codex run reported Bash: 0 while issuing
            // a dozen-plus commands. `>> exit N (…)` echoes the finished command's status and must
            // not be counted as a second call.
            line.isCodexShellInvocation() -> bashCalls++
        }
    }

    return if (foundAny) DecodedLogMetrics(
        execCodeCalls = execCodeCalls,
        readCalls = readCalls,
        writeCalls = writeCalls,
        bashCalls = bashCalls,
        globCalls = globCalls,
        grepCalls = grepCalls,
        editCalls = editCalls,
    ) else null
}

/** Extract Bash command details from decoded `>> Bash (...)` tool lines. */
fun extractDecodedBashCommands(decodedLogText: String): List<String> {
    return decodedLogText.lines().mapNotNull { rawLine ->
        val line = rawLine.trim()
        when {
            line == ">> Bash" -> ""
            line.startsWith(">> Bash (") && line.endsWith(")") ->
                line.removePrefix(">> Bash (").removeSuffix(")")
            else -> null
        }
    }
}

/**
 * Find decoded Gradle Bash commands that do not use [expectedJavaHomePrefix].
 *
 * This guards DPAIA Gradle runs against two measured failure modes:
 * using a lower JDK such as `temurin-21`, and using a literal wildcard assignment
 * such as `JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-*` (Bash does not expand globs in
 * assignment words).
 */
fun findDecodedGradleCommandsWithUnexpectedJavaHome(
    decodedLogText: String,
    expectedJavaHomePrefix: String,
): List<String> {
    val javaHomeRegex = Regex("""(?:^|\s)JAVA_HOME=([^\s]+)""")
    val gradleWrapperRegex = Regex("""(?:^|\s)(?:\S*/)?gradlew(?:\s|$)""")
    return extractDecodedBashCommands(decodedLogText)
        .filter { command -> gradleWrapperRegex.containsMatchIn(command) }
        .filter { command ->
            val javaHome = javaHomeRegex.find(command)?.groupValues?.get(1)
            javaHome == null || javaHome.contains('*') || !javaHome.startsWith(expectedJavaHomePrefix)
        }
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
 * Find the most-recently-modified raw NDJSON transcript in [runDir] matching
 * `agent-<agentName>-*-raw.ndjson` — the UNFILTERED agent stdout the session driver persists.
 *
 * This, not the captured process stdout, is the authoritative source for usage and test metrics: the
 * console-aware session hands back a filtered, human-readable stream (`>> steroid_execute_code`, …),
 * so a Codex `turn.completed` usage event never reached [extractTokenUsage] and every Codex run
 * recorded blank tokens AND blank test metrics even after the parser learned Codex's shape.
 *
 * Returns null if no matching file exists.
 */
fun findRawNdjsonFile(runDir: java.io.File, agentName: String): java.io.File? {
    val safeName = agentName.replace(' ', '-').lowercase()
    return runDir.listFiles { f ->
        f.name.startsWith("agent-$safeName-") && f.name.endsWith("-raw.ndjson")
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

// ── CSV comparison writer ───────────────────────────────────────────────────

private const val CSV_HEADER = "timestamp,instance_id,pass_label,agent_claimed_fix,duration_s," +
        "exec_code_calls,bash_calls,read_calls,write_calls,edit_calls,glob_calls,grep_calls," +
        "num_turns,total_input_tokens,total_output_tokens,total_cache_creation_tokens," +
        "total_cache_read_tokens,duration_api_ms,estimated_cost_usd,tests_pass,tests_run," +
        "verified_ftp_passed,verified_ftp_total,verified_ftp_rate,objective_success," +
        "claim_matches_reality,fail_to_pass_tampered,regression_count,collateral_tests_edited_count"

/**
 * Append a row to the arena comparison CSV file.
 *
 * Creates the file with a header if it doesn't exist yet. Thread-safe via synchronized write.
 *
 * @param csvFile the target CSV file (e.g. `testOutputDir/arena-comparison.csv`)
 * @param instanceId the DPAIA scenario instance ID
 * @param passLabel a label for the current pass (from `-Darena.pass.label` system property)
 * @param claimedFix whether the agent claimed to have fixed the issue
 * @param durationS agent wall-clock duration in seconds
 * @param tokens extracted token usage (nullable)
 * @param testMetrics extracted test metrics (nullable)
 * @param decoded extracted decoded log metrics (nullable)
 * @param verification objective FAIL_TO_PASS grade from [ArenaVerifier.verify] (nullable — null when
 *                      verification itself failed, e.g. infra failure inside the container)
 */
@Synchronized
fun appendComparisonCsv(
    csvFile: java.io.File,
    instanceId: String,
    passLabel: String,
    claimedFix: Boolean,
    durationS: Long,
    tokens: TokenUsage?,
    testMetrics: TestMetrics?,
    decoded: DecodedLogMetrics?,
    verification: ArenaVerificationResult? = null,
) {
    csvFile.parentFile?.mkdirs()
    if (!csvFile.exists()) {
        csvFile.writeText(CSV_HEADER + "\n")
    }
    val row = listOf(
        java.time.Instant.now().toString(),
        instanceId,
        passLabel,
        claimedFix.toString(),
        durationS.toString(),
        (decoded?.execCodeCalls ?: "").toString(),
        (decoded?.bashCalls ?: "").toString(),
        (decoded?.readCalls ?: "").toString(),
        (decoded?.writeCalls ?: "").toString(),
        (decoded?.editCalls ?: "").toString(),
        (decoded?.globCalls ?: "").toString(),
        (decoded?.grepCalls ?: "").toString(),
        (tokens?.numTurns ?: "").toString(),
        (tokens?.inputTokens ?: "").toString(),
        (tokens?.outputTokens ?: "").toString(),
        (tokens?.cacheCreationTokens ?: "").toString(),
        (tokens?.cacheReadTokens ?: "").toString(),
        (tokens?.durationApiMs ?: "").toString(),
        tokens?.costUsd?.let { String.format("%.4f", it) } ?: "",
        (testMetrics?.testsPass ?: "").toString(),
        (testMetrics?.testsRun ?: "").toString(),
        (verification?.classesPassed ?: "").toString(),
        (verification?.classesTotal ?: "").toString(),
        (verification?.failToPassRate ?: "").toString(),
        (verification?.objectiveSuccess ?: "").toString(),
        (claimedFix == (verification?.objectiveSuccess == true)).toString(),
        (verification?.failToPassTampered ?: "").toString(),
        (verification?.takeIf { it.baselineAvailable }?.regressions?.size ?: "").toString(),
        (verification?.collateralTestFilesEdited?.size ?: "").toString(),
    ).joinToString(",")
    csvFile.appendText(row + "\n")
}
