/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.*
import java.io.BufferedWriter

/**
 * Filter for Claude's stream-json NDJSON output.
 *
 * Handles two formats produced by `--output-format stream-json --verbose`:
 *
 * **New format (Claude Code 2.1.x+)** — events: `system`, `assistant`, `user`, `result`.
 *   - `assistant.message.content[]` contains `text`, `tool_use`, `thinking` blocks.
 *   - `user.message.content[]` contains `tool_result` blocks.
 *   - `result.result` is typically empty; stats are in `total_cost_usd`, `duration_ms`.
 *
 * **Old streaming format** — events: `message_start`, `content_block_start`,
 *   `content_block_delta`, `tool_use`, `tool_result`, `message_delta`, `result`.
 *
 * Both formats are handled; unrecognised events pass through as raw JSON.
 */
class ClaudeOutputFilter : AbstractOutputFilter() {

    override fun processEvent(rawLine: String, event: JsonObject, writer: BufferedWriter) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull
        if (type == null) {
            // No type field — render raw JSON so no data is lost
            writer.writeLine(rawLine)
            return
        }

        when (type) {
            // ── New format (Claude Code 2.1.x+) ───────────────────────────
            "assistant" -> handleAssistantEvent(event, writer)
            "user"      -> handleUserEvent(event, writer)

            // ── Old streaming format ───────────────────────────────────────
            "content_block_delta" -> handleContentBlockDelta(event, writer)
            "content_block_start" -> handleContentBlockStart(event, writer)
            "tool_use"            -> handleToolUse(event, writer)
            "tool_result"         -> handleToolResult(event, writer)
            "message_start"       -> handleMessageStart(event, writer)
            "message_delta"       -> handleMessageDelta(event, writer)

            // ── Both formats ───────────────────────────────────────────────
            "result" -> handleResult(event, writer)
            "error"  -> {
                val msg = formatErrorMessage(event) ?: return
                writer.writeLine(msg)
            }
            "system" -> handleSystem(event, writer)

            // Explicitly known no-op events — intentionally silenced
            "ping", "content_block_stop", "message_stop" -> { /* known, no output */ }

            // Unknown event type — pass through raw JSON so no data is lost
            else -> writer.writeLine(rawLine)
        }
    }

    // ── New-format handlers ────────────────────────────────────────────────

    private fun handleAssistantEvent(event: JsonObject, writer: BufferedWriter) {
        val message = event["message"]?.jsonObject ?: return
        val content = message["content"]?.jsonArray ?: return

        for (item in content) {
            val itemObj = item as? JsonObject ?: continue
            when (itemObj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = itemObj["text"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (text.isNotEmpty()) {
                        writer.write(text)
                        if (!text.endsWith("\n")) writer.write("\n")
                        writer.flush()
                    }
                }
                "tool_use" -> {
                    val name = itemObj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                    val input = itemObj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    val detail = toolDetail(name, input)
                    writer.writeLine("\n>> $name$detail")
                }
                "thinking" -> {
                    val text = itemObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                    if (firstLine.isNotEmpty()) {
                        writer.writeLine("[thinking] $firstLine")
                    }
                }
            }
        }
    }

    private fun handleUserEvent(event: JsonObject, writer: BufferedWriter) {
        val message = event["message"]?.jsonObject ?: return
        val content = message["content"]?.jsonArray ?: return

        for (item in content) {
            val itemObj = item as? JsonObject ?: continue
            if (itemObj["type"]?.jsonPrimitive?.contentOrNull != "tool_result") continue

            val isError = itemObj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
            val fullContent = extractFullContent(itemObj["content"])
            val prefix = if (isError) "<< ERROR" else "<<"
            writeToolResult(writer, prefix, fullContent)
        }
    }

    /**
     * Write a tool result to [writer] with the [prefix] (`<<` or `<< ERROR`) on the same line
     * as the first content line. Any subsequent lines are written as-is so that multi-line
     * debugger output (e.g. "=== Breakpoint hit at: File.kt:7 ===") is visible in the
     * filtered output and available to test assertions via [combined].
     */
    private fun writeToolResult(writer: BufferedWriter, prefix: String, fullContent: String) {
        if (fullContent.isEmpty()) {
            writer.writeLine(prefix)
            writer.flush()
            return
        }
        val firstNewline = fullContent.indexOf('\n')
        if (firstNewline < 0) {
            // Single-line content: keep on the same line as the prefix (backward-compatible)
            writer.writeLine("$prefix $fullContent")
        } else {
            val firstLine = fullContent.substring(0, firstNewline)
            val rest = fullContent.substring(firstNewline + 1)
            writer.writeLine("$prefix $firstLine")
            if (rest.isNotEmpty()) {
                writer.write(rest)
                if (!rest.endsWith("\n")) writer.write("\n")
            }
        }
        writer.flush()
    }

    /**
     * Extract all text content from a tool result content element.
     * Content may be a plain string or an array of text blocks.
     * Returns the full multi-line content so that debugger suspension evidence
     * (e.g. "=== Breakpoint hit at: File.kt:7 ===") is visible in filtered output.
     */
    private fun extractFullContent(content: JsonElement?): String {
        return when (content) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray -> buildString {
                for (block in content) {
                    if (block is JsonObject && block["type"]?.jsonPrimitive?.contentOrNull == "text") {
                        block["text"]?.jsonPrimitive?.contentOrNull?.let { append(it) }
                    }
                }
            }
            else -> ""
        }
    }

    private fun handleContentBlockDelta(event: JsonObject, writer: BufferedWriter) {
        val delta = event["delta"]?.jsonObject ?: return
        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull ?: return

        if (deltaType == "text_delta") {
            val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: return
            if (text.isNotEmpty()) {
                writer.write(text)
                writer.flush()
            }
        }
        // input_json_delta carries partial tool input - skip (noisy JSON fragments)
    }

    private fun handleContentBlockStart(event: JsonObject, writer: BufferedWriter) {
        val contentBlock = event["content_block"]?.jsonObject ?: return
        val blockType = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: return

        if (blockType == "tool_use") {
            val name = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "?"
            val input = contentBlock["input"]?.jsonObject ?: JsonObject(emptyMap())
            val detail = toolDetail(name, input)
            writer.writeLine("\n>> $name$detail")
        }
        // text block start - content comes via deltas, no action needed
    }

    private fun handleToolUse(event: JsonObject, writer: BufferedWriter) {
        // Fallback for older stream-json format with standalone tool_use events
        val name = event["name"]?.jsonPrimitive?.contentOrNull ?: "?"
        val input = event["input"]?.jsonObject ?: JsonObject(emptyMap())
        val detail = toolDetail(name, input)
        writer.writeLine(">> $name$detail")
    }

    private fun handleToolResult(event: JsonObject, writer: BufferedWriter) {
        val isError = event["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
        val fullContent = extractFullContent(event["content"])
        val prefix = if (isError) "<< ERROR" else "<<"
        writeToolResult(writer, prefix, fullContent)
    }

    private fun handleMessageStart(event: JsonObject, writer: BufferedWriter) {
        val message = event["message"]?.jsonObject ?: return
        val model = message["model"]?.jsonPrimitive?.contentOrNull ?: return
        if (model.isNotEmpty()) {
            writer.writeLine("[model] $model")
        }
    }

    private fun handleMessageDelta(event: JsonObject, writer: BufferedWriter) {
        val delta = event["delta"]?.jsonObject ?: return
        val stopReason = delta["stop_reason"]?.jsonPrimitive?.contentOrNull ?: return

        // Only show non-standard stop reasons
        if (stopReason.isNotEmpty() && stopReason != "end_turn") {
            writer.writeLine("[stop] $stopReason")
        }
    }

    private fun handleResult(event: JsonObject, writer: BufferedWriter) {
        // The result event carries the final answer text in the "result" field
        val resultText = event["result"]?.jsonPrimitive?.contentOrNull
        if (!resultText.isNullOrBlank()) {
            writer.write(resultText)
            if (!resultText.endsWith("\n")) {
                writer.write("\n")
            }
        }

        val cost = event["cost_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val duration = event["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
        val totalCost = event["total_cost_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val turns = event["num_turns"]?.jsonPrimitive?.intOrNull ?: 0

        val durSeconds = duration / 1000.0
        val parts = mutableListOf<String>()

        if (cost > 0) {
            parts.add("cost=${'$'}%.4f".format(cost))
        }
        if (totalCost > 0 && totalCost != cost) {
            parts.add("total=${'$'}%.4f".format(totalCost))
        }
        if (durSeconds > 0) {
            parts.add("time=%.1fs".format(durSeconds))
        }
        if (turns > 0) {
            parts.add("turns=$turns")
        }

        if (parts.isNotEmpty()) {
            writer.writeLine("[done] ${parts.joinToString(" ")}")
        } else {
            writer.writeLine("[done]")
        }
    }

    private fun handleSystem(event: JsonObject, writer: BufferedWriter) {
        val message = event["message"]?.jsonPrimitive?.contentOrNull ?: return
        if (message.isNotEmpty()) {
            writer.writeLine("[system] $message")
        }
    }

}
