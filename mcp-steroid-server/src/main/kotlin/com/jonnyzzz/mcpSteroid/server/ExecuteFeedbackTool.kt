package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Handler for the steroid_execute_feedback MCP tool.
 *
 * Allows agents to provide feedback on execution results, including:
 * - Success rating (0.00 to 1.00)
 * - Explanation of the rating
 * - Association with a task_id
 */
class ExecuteFeedbackToolSpec(val handler: () -> ExecuteFeedbackToolHandler) : McpTool {
    override val name = "steroid_execute_feedback"
    override val description = """
            Provide feedback on the result of a steroid_execute_code call and suggestions to improve the service.

            Use this tool to rate execution results and track what worked or didn't work.

            PARAMETERS:
            - project_name: The project where execution occurred
            - task_id: The same task_id you used in steroid_execute_code
            - execution_id: The execution_id returned in the steroid_execute_code result
            - success_rating: Rate from 0.00 (complete failure) to 1.00 (complete success)
              - 0.00-0.25: Complete failure, nothing worked
              - 0.25-0.50: Partial failure, some errors occurred
              - 0.50-0.75: Partial success, achieved some goals
              - 0.75-1.00: Success, achieved the intended goal
            - explanation: Describe what worked, what didn't, and what you'll try next
            - code (optional): The code snippet that illustrates the feedback the best way

            Feedback helps track execution history and identify patterns for improvement.
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
                put("description", "The task_id you used when calling steroid_execute_code")
            }
            putJsonObject("execution_id") {
                put("type", "string")
                put("description", "The execution_id returned from the most recent steroid_execute_code call for this task")
            }
            putJsonObject("success_rating") {
                put("type", "number")
                put("minimum", 0.0)
                put("maximum", 1.0)
                put("description", "Rate the success of the execution from 0.00 (complete failure) to 1.00 (complete success)")
            }
            putJsonObject("explanation") {
                put("type", "string")
                put("description", "Explain why you gave this rating. Provide improvements, suggestions, and critical thinking to improve this tool")
            }
            putJsonObject("code") {
                put("type", "string")
                put("description", "Optional: The code snippet that was executed. Useful for tracking what code produced which results.")
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("task_id")
            add("success_rating")
            add("explanation")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments
        return handle(args)
    }

    suspend fun handle(args: JsonObject): ToolCallResult {
        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
        if (projectName.isNullOrBlank()) {
            return ToolCallResult.errorResult("project_name is required (from steroid_list_projects)")
        }

        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
        if (taskId.isNullOrBlank()) {
            return ToolCallResult.errorResult("task_id is required (same id you passed to steroid_execute_code)")
        }

        val successRating = args["success_rating"]?.jsonPrimitive?.doubleOrNull
        if (successRating == null) {
            return ToolCallResult.errorResult("success_rating is required (number in 0.00..1.00 — do NOT send `rating`)")
        } else if (successRating !in 0.0..1.0) {
            return ToolCallResult.errorResult("success_rating=$successRating is out of range (must be 0.00..1.00)")
        }

        val explanation = args["explanation"]?.jsonPrimitive?.contentOrNull
        if (explanation.isNullOrBlank()) {
            return ToolCallResult.errorResult("explanation is required (free-form: what worked, what didn't, what you'll try next)")
        }

        // execution_id is optional — noted for context but value is not currently used
        val code = args["code"]?.jsonPrimitive?.contentOrNull

        val params = FeedbackParams(
            taskId = taskId,
            successRating = successRating,
            explanation = explanation,
            code = code
        )

        return handler().handleFeedback(projectName, params)
    }
}

@Serializable
data class FeedbackParams(
    val taskId: String,
    val successRating: Double,
    val explanation: String?,
    val code: String?
)

interface ExecuteFeedbackToolHandler {
    suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult
}
