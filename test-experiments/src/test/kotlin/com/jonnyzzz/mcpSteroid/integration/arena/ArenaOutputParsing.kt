/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The plugin's project-resolution error prefix (ProjectScopedToolHandler.resolveProject). */
private const val PROJECT_NOT_FOUND_PREFIX = "Project not found: \""

/** Non-throwing string accessor: null unless this element is a JSON string/primitive. */
private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Non-throwing boolean accessor: null unless this element is a JSON boolean primitive. */
private fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

/**
 * Extract text from a `content`/result field that may be EITHER a bare JSON string OR a JSON array of
 * `{type,text}` content blocks (the valid raw Anthropic content-block shape). Non-throwing: safe casts
 * only, so an unexpected shape yields "" rather than crashing the arena run.
 */
private fun textOf(el: JsonElement?): String =
    (el as? JsonPrimitive)?.contentOrNull
        ?: (el as? JsonArray)?.joinToString("\n") { (it as? JsonObject)?.get("text").stringOrNull() ?: "" }
        ?: ""

/**
 * True when the agent's raw NDJSON contains a `steroid_execute_code` tool RESULT that failed with the
 * plugin's project-resolution error. Structural on purpose:
 *  - reads the raw NDJSON (never the output-filtered prose, which echoes the prompt and fetched articles);
 *  - normalizes the wrapping `ERROR:` / `Error:` prefixes before an EXACT prefix match, so it matches the
 *    real payload `ERROR: Project not found: "…"` (and the doubled `Error: ERROR: …` sibling field);
 *  - attributes the error to `steroid_execute_code` via a tool-call-id -> tool-name map, because the
 *    Claude/Gemini error result carries only `tool_use_id`/`tool_id`, no tool name. That mapping is what
 *    prevents a false positive from a `steroid_fetch_resource` article body quoting the phrase.
 *
 * Robust by contract: every JSON access is a non-throwing safe cast (`as?`), so a malformed line or an
 * unexpected content shape (e.g. `content` sent as an array/object per the raw Anthropic content-block
 * format) simply is not flagged — the detector never throws out into `evaluate`/`runTest`.
 */
fun detectProjectResolutionFailure(rawNdjson: String): Boolean {
    val idToTool = HashMap<String, String>()
    // (toolCallId, errorText, selfToolName) — selfToolName set only when the result object itself
    // carries the tool name (Codex); Claude/Gemini rely on the id map.
    val candidates = mutableListOf<Triple<String?, String, String?>>()

    rawNdjson.lineSequence().forEach { raw ->
        if ('{' !in raw) return@forEach
        val obj = (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject) ?: return@forEach

        // Claude: message.content[*] carries tool_use (calls) and tool_result (results) on separate lines.
        ((obj["message"] as? JsonObject)?.get("content") as? JsonArray)
            ?.forEach { entry ->
                val item = entry as? JsonObject ?: return@forEach
                when (item["type"].stringOrNull()) {
                    "tool_use" -> {
                        val id = item["id"].stringOrNull()
                        val name = item["name"].stringOrNull()
                        if (id != null && name != null) idToTool[id] = name
                    }
                    "tool_result" -> {
                        if (item["is_error"].boolOrNull() == true) {
                            val id = item["tool_use_id"].stringOrNull()
                            val text = textOf(item["content"])
                            candidates += Triple(id, text, null)
                        }
                    }
                }
            }

        // Codex: item.completed with item.type == mcp_tool_call (id + tool + inline result).
        (obj["item"] as? JsonObject)?.let { item ->
            if (item["type"].stringOrNull() == "mcp_tool_call") {
                val id = item["id"].stringOrNull()
                val tool = item["tool"].stringOrNull() ?: item["name"].stringOrNull()
                if (id != null && tool != null) idToTool[id] = tool
                val resultText = ((item["result"] as? JsonObject)?.get("content") as? JsonArray)
                    ?.joinToString("\n") { c -> (c as? JsonObject)?.get("text").stringOrNull() ?: "" }
                if (!resultText.isNullOrBlank()) candidates += Triple(id, resultText, tool)
            }
        }

        // Gemini: root object type=tool_use (call: id + tool_name) / type=tool_result (result: tool_id).
        when (obj["type"].stringOrNull()) {
            "tool_use" -> {
                val id = obj["id"].stringOrNull()
                val name = obj["tool_name"].stringOrNull()
                if (id != null && name != null) idToTool[id] = name
            }
            "tool_result" -> {
                if (obj["is_error"].boolOrNull() == true) {
                    val id = obj["tool_id"].stringOrNull() ?: obj["tool_use_id"].stringOrNull()
                    val text = textOf(obj["content"])
                    candidates += Triple(id, text, null)
                }
            }
        }
    }

    return candidates.any { (id, text, selfTool) ->
        if (!stripErrorPrefixes(text).startsWith(PROJECT_NOT_FOUND_PREFIX)) return@any false
        val toolName = selfTool ?: id?.let { idToTool[it] }
        toolName != null && toolName.endsWith("steroid_execute_code")
    }
}

/** Strip any leading run of case-insensitive `ERROR:` / `Error:` wrappers (the payload can be doubled). */
private fun stripErrorPrefixes(text: String): String {
    var s = text.trimStart()
    while (s.length >= 6 && s.substring(0, 6).equals("error:", ignoreCase = true)) {
        s = s.substring(6).trimStart()
    }
    return s
}
