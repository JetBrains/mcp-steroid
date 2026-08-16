package com.jonnyzzz.mcpSteroid.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses one `dpaia-arena-run-<instance>-<agent>-<mode>.json` summary file (written by
 * `DpaiaScenarioBaseTest.writeRunSummary`) into an [AgentRun]. This is the cleanest, fully
 * self-identifying source: a local test run writes these directly, and the CI collector can
 * download them once the arena builds publish them as artifacts.
 *
 * Every field is read defensively — older/newer summaries may omit keys — so a partial file still
 * yields a usable [AgentRun] rather than throwing.
 */
object RunSummaryJsonParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): AgentRun {
        val o = json.parseToJsonElement(text).jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        fun long(k: String) = o[k]?.jsonPrimitive?.longOrNull
        fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull
        fun dbl(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        fun bool(k: String) = o[k]?.jsonPrimitive?.booleanOrNull

        // The semantic-ripple track nests its own grade under `ripple` (see `buildRippleRunSummaryJson`),
        // because the shared `objective_success` is only a NECESSARY part of a ripple SUCCESS and reading
        // it as the family's verdict would publish a run that failed a structural predicate as a success.
        val ripple = o["ripple"] as? JsonObject
        fun rBool(k: String) = ripple?.get(k)?.jsonPrimitive?.booleanOrNull
        fun rDbl(k: String) = ripple?.get(k)?.jsonPrimitive?.doubleOrNull
        val comparability = ripple?.get("comparability") as? JsonObject
        fun cStr(k: String) = comparability?.get(k)?.jsonPrimitive?.contentOrNull
        fun cInt(k: String) = comparability?.get(k)?.jsonPrimitive?.intOrNull
        val extraPredicates = (ripple?.get("extra_predicates") as? JsonObject)
            ?.mapNotNull { (id, v) -> v.jsonPrimitive.booleanOrNull?.let { id to it } }
            ?.toMap()
            .orEmpty()

        // Per-tool call counts → the toolCalls map used for the with/without diff. Only the *_calls keys
        // that are present contribute, so the map stays accurate for partial summaries.
        val toolCalls = buildMap {
            mapOf(
                "Read" to "read_calls", "Write" to "write_calls", "Edit" to "edit_calls",
                "Bash" to "bash_calls", "Glob" to "glob_calls", "Grep" to "grep_calls",
                "steroid_execute_code" to "exec_code_calls",
            ).forEach { (tool, key) -> int(key)?.let { put(tool, it) } }
        }

        return AgentRun(
            scenario = str("instance_id") ?: "(unknown)",
            agent = str("agent") ?: "(unknown)",
            mode = if (str("mode") == "none") McpMode.WITHOUT else McpMode.WITH,
            exitCode = int("exit_code"),
            claimedFix = bool("agent_claimed_fix"),
            usedMcp = bool("used_mcp_steroid"),
            agentDurationMs = long("agent_duration_ms"),
            inputTokens = long("input_tokens"),
            outputTokens = long("output_tokens"),
            cacheReadTokens = long("cache_read_tokens"),
            cacheCreationTokens = long("cache_creation_tokens"),
            costUsd = dbl("cost_usd"),
            numTurns = int("num_turns"),
            model = str("model"),
            agentVersion = str("agent_version"),
            contextWindow = long("context_window"),
            maxOutputTokens = long("max_output_tokens"),
            testsRun = int("tests_run"),
            testsFail = int("tests_fail"),
            buildSuccess = bool("build_success"),
            toolCalls = toolCalls,
            execCodeCalls = int("exec_code_calls"),
            summary = str("agent_summary")?.ifBlank { null },
            objectiveSuccess = bool("objective_success"),
            failToPassTampered = bool("fail_to_pass_tampered"),
            rippleSuccess = rBool("ripple_success"),
            rippleAllPredicatesPassed = rBool("all_predicates_passed"),
            rippleCompileGatePassed = rBool("compile_gate_passed"),
            rippleF1 = rDbl("f1"),
            rippleRecall = rDbl("recall"),
            ripplePrecision = rDbl("precision"),
            rippleExtraPredicates = extraPredicates,
            comparabilityVerdict = cStr("verdict"),
            comparabilityReason = cStr("reason"),
            steroidCalls = cInt("steroid_calls"),
            bashCalls = cInt("bash_calls"),
            totalToolCalls = cInt("total_tool_calls"),
            toolErrorCount = cInt("tool_error_count"),
            ideCallShare = comparability?.get("ide_call_share")?.jsonPrimitive?.doubleOrNull,
        )
    }
}
