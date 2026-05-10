package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.vision.InputSequenceParser
import com.jonnyzzz.mcpSteroid.vision.InputStep
import com.jonnyzzz.mcpSteroid.vision.InputTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Handler for the steroid_input MCP tool.
 */
class VisionInputToolSpec(val handler: () -> VisionInputToolHandler) : McpTool {
    override val name = "steroid_input"

    override val description = """
        Send input events (keyboard + mouse) to the IDE using a sequence string.

        HEAVY ENDPOINT: Intended for debugging only. Prefer steroid_execute_code for regular automation.

        Sequence format (comma-separated or newline-separated steps; commas optional with newlines):
        - stick:ALT           (hold a key until the end)
        - delay:400           (milliseconds)
        - press:CTRL+P        (press key with modifiers)
        - type:hello          (type text; commas are allowed unless they look like a new step)
        - click:CTRL+Left@120,200        (click with modifiers at screenshot coords)
        - click:Right@screen:400,300     (click at screen coords)

        Comma separators are detected by ", <step>:" patterns, so avoid typing ", delay:" etc.
        Trailing commas before a newline are ignored.
        Use "#" for comments until the end of the line.

        All keys are released at the end of the sequence.

        The input is delivered to the window captured by steroid_take_screenshot (window_id from metadata) and the focus is forced to that window.
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
                put("description", "Reason for sending input. Required for audit logs.")
            }
            putJsonObject("screenshot_execution_id") {
                put("type", "string")
                put("description", "Execution ID from steroid_take_screenshot (or takeIdeScreenshot() inside a script)")
            }
            putJsonObject("sequence") {
                put("type", "string")
                put("description", "Comma-separated input sequence (stick/press/type/click/delay)")
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("task_id")
            add("reason")
            add("screenshot_execution_id")
            add("sequence")
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
        //TODO: just use window_id and make sure it's still around.
        val screenshotExecutionId = args["screenshot_execution_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: screenshot_execution_id")
        val sequence = args["sequence"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: sequence")

        val parsed = InputSequenceParser().parse(sequence)
        if (parsed.filterIsInstance<InputStep.Click>().any { it.target is InputTarget.Unsupported }) {
            throw IllegalArgumentException("Unsupported target in sequence. Only screenshot/screen targets are supported.")
        }

        return handler().handleInputSequence(projectName, InputParams(
            taskId = taskId,
            reason = reason,
            screenshotExecutionId = screenshotExecutionId,
            sequence = parsed,
        ))
    }
}

@Serializable
data class InputParams(
    val taskId: String,
    val reason: String,
    val screenshotExecutionId: String,
    val sequence: List<InputStep>
)

interface VisionInputToolHandler {
    suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult
}
