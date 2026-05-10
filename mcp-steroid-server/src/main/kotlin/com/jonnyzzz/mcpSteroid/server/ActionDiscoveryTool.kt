package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Handler for the steroid_action_discovery MCP tool.
 */
class ActionDiscoveryToolSpec(val handler: () -> ActionDiscoveryToolHandler) : McpTool {
    override val name = "steroid_action_discovery"

    override val description =
        "Discover what IDE actions are available at a file location before invoking them via steroid_execute_code. " +
                "Use BEFORE applying quick-fixes, refactorings, or running gutter actions (Run/Debug) when you don't know the exact action ID. " +
                "Returns action IDs (pass to ActionManager.getAction(id) in exec_code), intention names, error fixes, and gutter icon actions. " +
                "Workflow: (1) call this with file + caret offset, (2) pick action from results, (3) invoke via steroid_execute_code."

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_name") {
                put("type", "string")
                put("description", "Name of the project containing the file (from steroid_list_projects).")
            }
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Absolute path or project-relative path to the file.")
            }
            putJsonObject("caret_offset") {
                put("type", "integer")
                put("description", "Caret offset within the file (default: 0).")
            }
            putJsonObject("action_groups") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Optional list of action group IDs to expand (default: editor popup + gutter).")
            }
            putJsonObject("max_actions_per_group") {
                put("type", "integer")
                put("description", "Limit the number of actions returned per action group (default: 200).")
            }
            putJsonObject("task_id") {
                put("type", "string")
                put("description", "Optional task ID for log grouping.")
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("file_path")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments
        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: project_name")
        val filePath = args["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: file_path")
        val caretOffset = args["caret_offset"]?.jsonPrimitive?.intOrNull ?: 0
        val actionGroups = parseActionGroups(args["action_groups"]?.jsonArray)
        val maxActions = args["max_actions_per_group"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 200
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull

        return handler().discoverActions(
            projectName,
            ActionDiscoveryParams(
                filePath = filePath,
                caretOffset = caretOffset,
                actionGroups = actionGroups,
                maxActions = maxActions,
                taskId = taskId
            )
        )
    }

    private fun parseActionGroups(array: JsonArray?): List<String>? {
        array ?: return null
        val groups = array
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return groups
    }
}

@Serializable
data class ActionDiscoveryParams(
    val filePath: String,
    val caretOffset: Int,
    val actionGroups: List<String>?,
    val maxActions: Int,
    val taskId: String?
)


interface ActionDiscoveryToolHandler {
    suspend fun discoverActions(projectName: String, actionDiscoveryParams: ActionDiscoveryParams): ToolCallResult
}
