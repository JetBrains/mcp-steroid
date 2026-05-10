package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Handler for the steroid_take_screenshot MCP tool.
 */
class VisionScreenshotToolSpec(val handler: () -> VisionScreenshotToolHandler) : McpTool {
    override val name = "steroid_take_screenshot"

    override val description = """
        Capture a screenshot of the IDE and return an image payload.

        HEAVY ENDPOINT: This is intended for debugging and tricky configuration only.
        Prefer steroid_execute_code for regular automation.

        Use steroid_list_windows when multiple IDE windows are open and pass window_id to target a specific window.

        The screenshot and component tree are saved under the execution folder:
        - screenshot.png
        - screenshot-tree.md
        - screenshot-meta.json

        After execution, call steroid_execute_feedback to log your feedback.
    """.trimIndent()

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_name") {
                put("type", "string")
                put("description", "Project name (from steroid_list_projects)")
            }
            putJsonObject("task_id") {
                put("type", "string")
                put("description", "Your task identifier to group related executions.")
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "Reason for taking the screenshot. Required for audit logs.")
            }
            putJsonObject("window_id") {
                put("type", "string")
                put("description", "Optional window id from steroid_list_windows to target a specific IDE window.")
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("task_id")
            add("reason")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments
        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: project_name")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: reason")
        val windowId = args["window_id"]?.jsonPrimitive?.contentOrNull

        return handler().screenshotWindow(projectName, ScreenshotParams(taskId, reason, windowId), context.mcpProgressReporter)
    }
}

@Serializable
data class ScreenshotParams(
    val taskId: String,
    val reason: String,
    val windowId: String? = null
)

interface VisionScreenshotToolHandler {
    suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter
    ): ToolCallResult
}

