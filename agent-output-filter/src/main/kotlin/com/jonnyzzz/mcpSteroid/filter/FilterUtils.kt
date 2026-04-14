/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedWriter

/**
 * Shared JSON parser for all NDJSON filters.
 */
internal val filterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Write text followed by a newline and flush. Uses `\n` explicitly to avoid platform-dependent `\r\n`. */
internal fun BufferedWriter.writeLine(text: String) {
    write(text)
    write("\n")
    flush()
}

/**
 * Format an error message from a JSON event.
 * Handles both object and primitive error fields, extracts type/code for labeling.
 * Returns null if no meaningful message can be extracted.
 */
internal fun formatErrorMessage(event: JsonObject): String? {
    val errorElement = event["error"] ?: event["message"]

    val (message, errorType) = when (errorElement) {
        is JsonObject -> {
            val msg = errorElement["message"]?.jsonPrimitive?.contentOrNull
                ?: event["message"]?.jsonPrimitive?.contentOrNull
                ?: errorElement.toString()
            val etype = errorElement["type"]?.jsonPrimitive?.contentOrNull
                ?: errorElement["code"]?.jsonPrimitive?.contentOrNull
                ?: event["code"]?.jsonPrimitive?.contentOrNull
                ?: ""
            msg to etype
        }
        is JsonPrimitive -> (errorElement.contentOrNull ?: "") to ""
        else -> (event["message"]?.jsonPrimitive?.contentOrNull ?: errorElement?.toString() ?: "") to ""
    }

    val trimmed = message.trim()
    if (trimmed.isEmpty()) return null

    return if (errorType.isNotEmpty() && errorType != "error") {
        "[ERROR $errorType] $trimmed"
    } else {
        "[ERROR] $trimmed"
    }
}

/**
 * Extracts a human-readable detail string from tool input parameters.
 *
 * Used by all NDJSON filters to annotate tool calls with their key argument
 * (e.g. file path, command, pattern, reason).
 */
internal fun toolDetail(toolName: String, input: JsonObject?): String {
    if (input == null) return ""
    // Strip MCP server prefix (e.g. "mcp__mcp-steroid__steroid_execute_code" → "steroid_execute_code")
    val simpleName = toolName.substringAfterLast("__")
    return when {
        simpleName == "steroid_execute_code" || toolName == "steroid_execute_code" -> {
            val reason = input["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            if (reason.isNotEmpty()) " ($reason)" else ""
        }

        simpleName == "read_mcp_resource" || toolName == "read_mcp_resource"
                || toolName == "ReadMcpResourceTool" -> {
            val uri = input["uri"]?.jsonPrimitive?.contentOrNull ?: ""
            if (uri.isNotEmpty()) " ($uri)" else ""
        }

        simpleName == "steroid_list_projects" || simpleName == "steroid_list_windows"
                || simpleName == "steroid_take_screenshot" || simpleName == "steroid_action_discovery" -> ""

        toolName in setOf("Bash", "bash", "run_shell_command") -> {
            val cmd = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
            if (cmd.isNotEmpty()) " ($cmd)" else ""
        }

        toolName in setOf("read_file", "write_file", "edit_file", "replace",
            "Read", "read", "Edit", "edit", "Write", "write") -> {
            val path = input["file_path"]?.jsonPrimitive?.contentOrNull ?: ""
            if (path.isNotEmpty()) " ($path)" else ""
        }

        toolName in setOf("Grep", "grep", "Glob", "glob") -> {
            val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
            if (pattern.isNotEmpty()) " ($pattern)" else ""
        }

        simpleName == "steroid_execute_feedback" || toolName == "steroid_execute_feedback" -> {
            val rating = input["success_rating"]?.jsonPrimitive?.contentOrNull ?: ""
            val explanation = input["explanation"]?.jsonPrimitive?.contentOrNull
                ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: ""
            val parts = buildList {
                if (rating.isNotEmpty()) add("rating=$rating")
                if (explanation.isNotEmpty()) add(explanation.take(60))
            }
            if (parts.isNotEmpty()) " (${parts.joinToString(", ")})" else ""
        }

        simpleName == "steroid_open_project" || toolName == "steroid_open_project" -> {
            val path = input["project_path"]?.jsonPrimitive?.contentOrNull
                ?: input["project_name"]?.jsonPrimitive?.contentOrNull ?: ""
            if (path.isNotEmpty()) " ($path)" else ""
        }

        else -> {
            // Generic fallback: show the first short, non-multiline primitive value
            // so tool calls for unrecognised tools still carry useful context.
            val firstVal = input.entries
                .mapNotNull { (_, v) ->
                    if (v is JsonPrimitive) {
                        val s = v.contentOrNull ?: return@mapNotNull null
                        if (s.isBlank() || s.contains('\n') || s.length > 80) null else s
                    } else null
                }
                .firstOrNull()
            if (firstVal != null) " ($firstVal)" else ""
        }
    }
}
