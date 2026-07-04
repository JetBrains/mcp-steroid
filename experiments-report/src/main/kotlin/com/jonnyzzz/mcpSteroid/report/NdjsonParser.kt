package com.jonnyzzz.mcpSteroid.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Metrics extracted from one agent-output NDJSON stream (one mode of one run). */
data class NdjsonMetrics(
    val model: String? = null,
    val agentVersion: String? = null,
    val mcpServers: List<String> = emptyList(),
    val contextWindow: Long? = null,
    val maxOutputTokens: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheCreationTokens: Long? = null,
    val costUsd: Double? = null,
    val numTurns: Int? = null,
    val durationApiMs: Long? = null,
    val toolCalls: Map<String, Int> = emptyMap(),
)

/**
 * Parses an agent-output NDJSON stream (the `agent-<cli>-<n>-raw.ndjson` file the arena publishes per
 * run) — the authoritative source for model name, agent CLI version, token budget, token/cost usage and
 * per-tool call counts. Lines that are blank or not JSON are skipped, so a partial stream still yields
 * whatever could be read.
 *
 * Shape (Claude Code / Codex stream-json):
 *  - `{type:system, subtype:init, model, claude_code_version, mcp_servers:[{name}]}`
 *  - `{type:assistant, message:{content:[{type:tool_use, name}]}}` — one per tool call
 *  - `{type:result, total_cost_usd, usage:{input_tokens,…}, num_turns, duration_api_ms,
 *       modelUsage:{<model>:{contextWindow, maxOutputTokens}}}`
 */
object NdjsonParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): NdjsonMetrics {
        var model: String? = null
        var agentVersion: String? = null
        var mcpServers: List<String> = emptyList()
        var contextWindow: Long? = null
        var maxOutputTokens: Long? = null
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var cacheReadTokens: Long? = null
        var cacheCreationTokens: Long? = null
        var costUsd: Double? = null
        var numTurns: Int? = null
        var durationApiMs: Long? = null
        val toolCalls = LinkedHashMap<String, Int>()

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "system" -> if (obj["subtype"]?.jsonPrimitive?.contentOrNull == "init") {
                    model = obj["model"]?.jsonPrimitive?.contentOrNull ?: model
                    agentVersion = obj["claude_code_version"]?.jsonPrimitive?.contentOrNull
                        ?: obj["version"]?.jsonPrimitive?.contentOrNull ?: agentVersion
                    mcpServers = obj["mcp_servers"]?.let { arr ->
                        runCatching { arr.jsonArray.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } }.getOrNull()
                    } ?: mcpServers
                }
                "assistant" -> {
                    val content = obj["message"]?.let { runCatching { it.jsonObject["content"]?.jsonArray }.getOrNull() }
                    content?.forEach { block ->
                        val bo = runCatching { block.jsonObject }.getOrNull() ?: return@forEach
                        if (bo["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                            val name = bo["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            toolCalls[name] = (toolCalls[name] ?: 0) + 1
                        }
                    }
                }
                // Codex stream-json: tool calls arrive as completed items rather than assistant tool_use.
                "item.completed" -> {
                    val item = obj["item"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: continue
                    val itemType = (item["item_type"] ?: item["type"])?.jsonPrimitive?.contentOrNull
                    val toolName = when (itemType) {
                        "command_execution" -> "shell"
                        "file_change" -> "file_change"
                        "mcp_tool_call" -> item["tool"]?.jsonPrimitive?.contentOrNull ?: "mcp_tool_call"
                        else -> null // agent_message etc. are not tool calls
                    }
                    if (toolName != null) toolCalls[toolName] = (toolCalls[toolName] ?: 0) + 1
                }
                "result" -> {
                    costUsd = obj["total_cost_usd"]?.jsonPrimitive?.doubleOrNull ?: costUsd
                    numTurns = obj["num_turns"]?.jsonPrimitive?.intOrNull ?: numTurns
                    durationApiMs = obj["duration_api_ms"]?.jsonPrimitive?.longOrNull ?: durationApiMs
                    obj["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }?.let { u ->
                        inputTokens = u["input_tokens"]?.jsonPrimitive?.longOrNull ?: inputTokens
                        outputTokens = u["output_tokens"]?.jsonPrimitive?.longOrNull ?: outputTokens
                        cacheReadTokens = u["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: cacheReadTokens
                        cacheCreationTokens = u["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: cacheCreationTokens
                    }
                    // token budget from the primary model's usage entry
                    obj["modelUsage"]?.let { runCatching { it.jsonObject }.getOrNull() }?.let { mu ->
                        val entry = (model?.let { mu[it] } ?: mu.values.firstOrNull())
                            ?.let { runCatching { it.jsonObject }.getOrNull() }
                        if (entry != null) {
                            contextWindow = entry["contextWindow"]?.jsonPrimitive?.longOrNull ?: contextWindow
                            maxOutputTokens = entry["maxOutputTokens"]?.jsonPrimitive?.longOrNull ?: maxOutputTokens
                        }
                    }
                }
            }
        }

        return NdjsonMetrics(
            model = model,
            agentVersion = agentVersion,
            mcpServers = mcpServers,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheCreationTokens = cacheCreationTokens,
            costUsd = costUsd,
            numTurns = numTurns,
            durationApiMs = durationApiMs,
            toolCalls = toolCalls,
        )
    }
}
