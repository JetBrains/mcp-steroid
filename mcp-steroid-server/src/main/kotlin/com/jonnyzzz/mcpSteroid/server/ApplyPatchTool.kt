package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ApplyPatchToolDescriptionPromptArticle
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class ApplyPatchRequest(val hunks: List<ApplyPatchHunk>)

@Serializable
data class ApplyPatchHunk(val filePath: String, val oldString: String, val newString: String)

/**
 * First-class MCP tool for atomic multi-site literal-text patching.
 *
 * Surfaces the same underlying [com.jonnyzzz.mcpSteroid.execution.executeApplyPatch] engine that
 * [com.jonnyzzz.mcpSteroid.execution.McpScriptContextImpl.applyPatch] uses,
 * but without the kotlinc compile step — so Claude Code's hard-coded ~60s
 * MCP tool timeout (issue #16837) no longer truncates 5+ hunk patches.
 *
 * Input shape mirrors the DSL:
 * ```json
 * {
 *   "project_name": "project-home",
 *   "task_id": "feature-x",
 *   "reason": "rename logger name across services",
 *   "hunks": [
 *     {"file_path": "/abs/A.java", "old_string": "foo", "new_string": "bar"},
 *     {"file_path": "/abs/A.java", "old_string": "baz", "new_string": "qux"},
 *     {"file_path": "/abs/B.java", "old_string": "log1", "new_string": "log2"}
 *   ]
 * }
 * ```
 *
 * Semantics: pre-flight validates every `old_string` is present exactly once;
 * all edits land as a single undoable [WriteCommandAction], PSI committed in
 * the same action, VFS async-refreshed on completion.
 */
class ApplyPatchToolSpec(val handler: () -> ApplyPatchToolHandler) : McpTool {
    override val name = "steroid_apply_patch"
    override val description get() = ApplyPatchToolDescriptionPromptArticle().readPayload(PromptsContext.Generic)
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_name") {
                put("type", "string")
                put("description", "Project name (from steroid_list_projects)")
            }
            putJsonObject("task_id") {
                put("type", "string")
                put("description", "Your task identifier; reuse across related calls.")
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "One-line summary of what this patch changes.")
            }
            putJsonObject("hunks") {
                put("type", "array")
                put("description", "Literal-text hunks. Each hunk's old_string must occur exactly once in its file.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("file_path") {
                            put("type", "string")
                            put("description", "Absolute filesystem path. Matches Claude Code `Edit`'s `file_path` field.")
                        }
                        putJsonObject("old_string") {
                            put("type", "string")
                            put("description", "Literal text to replace (must occur exactly once).")
                        }
                        putJsonObject("new_string") {
                            put("type", "string")
                            put("description", "Replacement text.")
                        }
                    }
                    putJsonArray("required") {
                        add("file_path")
                        add("old_string")
                        add("new_string")
                    }
                }
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("task_id")
            add("hunks")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: project_name")
        args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: task_id")

        val rawHunks = args["hunks"]
            ?: return ToolCallResult.errorResult("Missing required parameter: hunks (array)")
        // Some MCP clients (Claude Code's tool-call envelope under certain
        // configurations) pass complex parameters as serialised JSON strings
        // instead of native arrays. `?.jsonArray` would have thrown
        // IllegalArgumentException on a JsonPrimitive, leaking a stacktrace
        // through the MCP layer. Detect-and-decode keeps the contract clean
        // (regression: ApplyPatchToolIntegrationTest#testStringEncodedHunksReturnsCleanError).
        val hunksJson = when (rawHunks) {
            is JsonArray -> rawHunks
            is JsonPrimitive -> {
                if (!rawHunks.isString) {
                    return ToolCallResult.errorResult("hunks must be a JSON array, got primitive: ${rawHunks.content}")
                }
                val decoded = try {
                    McpJson.parseToJsonElement(rawHunks.content)
                } catch (e: SerializationException) {
                    return ToolCallResult.errorResult("hunks must be a JSON array — failed to parse string-encoded value: ${e.message}")
                }
                (decoded as? JsonArray)
                    ?: return ToolCallResult.errorResult("hunks must be a JSON array — string-encoded value parsed to ${decoded::class.simpleName}")
            }
            else -> return ToolCallResult.errorResult("hunks must be a JSON array, got ${rawHunks::class.simpleName}")
        }
        if (hunksJson.isEmpty()) return ToolCallResult.errorResult("hunks array is empty")

        val hunks = hunksJson.mapIndexed { i, el ->
            val o = (el as? JsonObject)
                ?: return ToolCallResult.errorResult("hunks[$i] is not an object")
            val filePath = o["file_path"]?.jsonPrimitive?.contentOrNull
                ?: return ToolCallResult.errorResult("hunks[$i].file_path is required")
            val oldString = o["old_string"]?.jsonPrimitive?.contentOrNull
                ?: return ToolCallResult.errorResult("hunks[$i].old_string is required")
            val newString = o["new_string"]?.jsonPrimitive?.contentOrNull
                ?: return ToolCallResult.errorResult("hunks[$i].new_string is required")
            ApplyPatchHunk(filePath = filePath, oldString = oldString, newString = newString)
        }

        return handler().applyPatch(projectName, ApplyPatchRequest(hunks))
    }
}

interface ApplyPatchToolHandler {
    suspend fun applyPatch(projectName: String, applyPatchRequest: ApplyPatchRequest): ToolCallResult
}
