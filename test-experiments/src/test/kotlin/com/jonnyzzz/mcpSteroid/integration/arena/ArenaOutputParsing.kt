/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val PROJECT_NOT_FOUND_PREFIX = "Project not found: \""

/** One normalized steroid_execute_code result, independent of the agent CLI that emitted it. */
data class ExecuteCodeResult(
    val callId: String,
    val isError: Boolean,
    val text: String,
)

/** Structural MCP facts extracted from an agent's raw NDJSON transcript. */
data class AgentTranscript(
    val executeCodeCallIds: List<String>,
    val executeCodeResults: List<ExecuteCodeResult>,
) {
    val usedMcpSteroid: Boolean
        get() = executeCodeCallIds.isNotEmpty()

    val successfulMcpExecution: Boolean
        get() = executeCodeResults.any { !it.isError }
}

enum class ProjectResolutionStatus {
    CLEAN,
    RECOVERED,
    INITIAL_FAILURE,
    UNRECOVERED_FAILURE,
    ;

    val shouldFailRun: Boolean
        get() = this == INITIAL_FAILURE || this == UNRECOVERED_FAILURE
}

private data class ResultCandidate(
    val sequence: Int,
    val callId: String,
    val selfToolName: String?,
    val isError: Boolean,
    val text: String,
)

/**
 * Normalize Claude Code, Codex, and Gemini raw NDJSON into execute-code calls/results.
 * Tool-result attribution is by call id, so an article body returned by steroid_fetch_resource cannot
 * masquerade as an execute-code failure merely because it quotes "Project not found".
 */
fun decodeAgentTranscript(rawNdjson: String): AgentTranscript {
    val idToToolName = LinkedHashMap<String, String>()
    val executeCodeCallIds = mutableListOf<String>()
    val resultCandidates = mutableListOf<ResultCandidate>()
    var sequence = 0

    fun recordCall(callId: String?, toolName: String?) {
        if (callId == null || toolName == null) return
        idToToolName[callId] = toolName
        if (toolName.endsWith("steroid_execute_code") && callId !in executeCodeCallIds) {
            executeCodeCallIds += callId
        }
    }

    fun recordResult(callId: String?, selfToolName: String?, isError: Boolean?, text: String) {
        if (callId == null || isError == null) return
        resultCandidates += ResultCandidate(sequence++, callId, selfToolName, isError, text)
    }

    rawNdjson.lineSequence().forEach lines@{ raw ->
        if ('{' !in raw) return@lines
        val obj = (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)
            ?: return@lines

        // Claude Code: message.content[*] tool_use / tool_result objects.
        ((obj["message"] as? JsonObject)?.get("content") as? JsonArray)
            ?.forEach entries@{ entry ->
                val item = entry as? JsonObject ?: return@entries
                when (item["type"].stringOrNull()) {
                    "tool_use" -> recordCall(
                        callId = item["id"].stringOrNull(),
                        toolName = item["name"].stringOrNull(),
                    )

                    "tool_result" -> recordResult(
                        callId = item["tool_use_id"].stringOrNull(),
                        selfToolName = null,
                        // Claude omits is_error on successful tool results; only failures carry true.
                        isError = item["is_error"].boolOrNull() ?: false,
                        text = textOf(item["content"]),
                    )
                }
            }

        // Codex: item.started / item.completed containing an mcp_tool_call.
        (obj["item"] as? JsonObject)?.let { item ->
            if (item["type"].stringOrNull() == "mcp_tool_call") {
                val callId = item["id"].stringOrNull()
                val toolName = item["tool"].stringOrNull() ?: item["name"].stringOrNull()
                recordCall(callId, toolName)

                if (obj["type"].stringOrNull() == "item.completed") {
                    val status = item["status"].stringOrNull()?.lowercase()
                    val hasErrorObject = item["error"] != null && item["error"] !is JsonNull
                    val isError = when {
                        hasErrorObject || status == "failed" || status == "error" -> true
                        status == "completed" || status == "success" -> false
                        else -> null
                    }
                    val text = textOf((item["result"] as? JsonObject)?.get("content"))
                        .ifBlank { textOf(item["error"]) }
                    recordResult(callId, toolName, isError, text)
                }
            }
        }

        // Gemini: root objects using the real stream-json fields tool_id/status/output.
        when (obj["type"].stringOrNull()) {
            "tool_use" -> recordCall(
                callId = obj["tool_id"].stringOrNull(),
                toolName = obj["tool_name"].stringOrNull(),
            )

            "tool_result" -> {
                val status = obj["status"].stringOrNull()?.lowercase()
                val isError = when (status) {
                    "error", "failed" -> true
                    "success", "completed" -> false
                    else -> null
                }
                recordResult(
                    callId = obj["tool_id"].stringOrNull(),
                    selfToolName = obj["tool_name"].stringOrNull(),
                    isError = isError,
                    text = textOf(obj["output"]),
                )
            }
        }
    }

    val results = resultCandidates
        .sortedBy { it.sequence }
        .mapNotNull { candidate ->
            val toolName = candidate.selfToolName ?: idToToolName[candidate.callId]
            if (toolName?.endsWith("steroid_execute_code") != true) return@mapNotNull null
            ExecuteCodeResult(candidate.callId, candidate.isError, candidate.text)
        }

    return AgentTranscript(
        executeCodeCallIds = executeCodeCallIds,
        executeCodeResults = results,
    )
}

/**
 * The first mandatory execute_code must resolve immediately (#251). Later project reloads may invalidate
 * the key; that is accepted only when the agent re-lists projects and produces a later successful result.
 */
fun AgentTranscript.projectResolutionStatus(): ProjectResolutionStatus {
    val firstCallId = executeCodeCallIds.firstOrNull()
    val firstResult = firstCallId?.let { id -> executeCodeResults.firstOrNull { it.callId == id } }
    if (firstResult?.isProjectResolutionFailure() == true) {
        return ProjectResolutionStatus.INITIAL_FAILURE
    }

    val lastFailureIndex = executeCodeResults.indexOfLast { it.isProjectResolutionFailure() }
    if (lastFailureIndex < 0) return ProjectResolutionStatus.CLEAN

    return if (executeCodeResults.drop(lastFailureIndex + 1).any { !it.isError }) {
        ProjectResolutionStatus.RECOVERED
    } else {
        ProjectResolutionStatus.UNRECOVERED_FAILURE
    }
}

/**
 * The prompt's mandatory first execute-code recipe prints `Project: ..., base: ...`. Require that first
 * successful result to name the arena deployment path so a different but valid open project cannot make
 * the MCP arm look healthy.
 */
fun AgentTranscript.firstExecutionTargetsProject(expectedProjectDir: String): Boolean {
    val firstCallId = executeCodeCallIds.firstOrNull() ?: return false
    val firstResult = executeCodeResults.firstOrNull { it.callId == firstCallId } ?: return false
    if (firstResult.isError) return false

    val expectedPath = expectedProjectDir.trimEnd('/')
    return firstResult.text.lineSequence().any { line ->
        line.substringAfter("base:", missingDelimiterValue = "").trim().trimEnd('/') == expectedPath
    }
}

private fun ExecuteCodeResult.isProjectResolutionFailure(): Boolean =
    isError && stripErrorPrefixes(text).startsWith(PROJECT_NOT_FOUND_PREFIX)

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun textOf(element: JsonElement?): String = when (element) {
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonArray -> element.joinToString("\n") { textOf(it) }
    is JsonObject -> sequenceOf("text", "output", "message", "content")
        .map { key -> textOf(element[key]) }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    else -> ""
}

private fun stripErrorPrefixes(text: String): String {
    var value = text.trimStart()
    while (value.length >= 6 && value.substring(0, 6).equals("error:", ignoreCase = true)) {
        value = value.substring(6).trimStart()
    }
    return value
}
