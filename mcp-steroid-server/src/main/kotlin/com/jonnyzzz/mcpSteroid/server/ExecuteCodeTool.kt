package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class ExecCodeParams(
    val taskId: String,
    val code: String,
    val reason: String,
    val timeout: Int?,
    //TODO: move that away from here, allow changes only via the McpScriptContext::doNotCancelOnModalityStateChange
    /** If true, cancel execution when a modal dialog appears and return a screenshot. Default true. */
    val cancelOnModal: Boolean = true,

    /** Controls pre-execution dialog killer: null = use registry default, true = force enable, false = force disable. */
    val dialogKiller: Boolean? = null,
)

/**
 * Handler for the steroid_execute_code MCP tool.
 */
class ExecuteCodeToolSpec(val handler: () -> ExecuteCodeToolHandler) : McpTool {
    override val name = "steroid_execute_code"
    override val description get() = ExecuteCodeToolDescriptionPromptArticle().readPayload(PromptsContext.Generic)
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_name") {
                put("type", "string")
                put("description", "Project name (from steroid_list_projects)")
            }
            putJsonObject("code") {
                put("type", "string")
                put("description", "Kotlin suspend method body")
            }
            putJsonObject("task_id") {
                put("type", "string")
                put(
                    "description",
                    "Your task identifier to group related executions. Use the same task_id for all execute_code calls that are part of the same task, and when providing feedback via steroid_execute_feedback."
                )
            }
            putJsonObject("reason") {
                put("type", "string")
                put(
                    "description",
                    "IMPORTANT: On your FIRST call, provide the FULL TASK DESCRIPTION from the user - what they originally asked you to do. On subsequent calls, describe what this specific execution aims to achieve. This helps track progress and understand context."
                )
            }
            putJsonObject("timeout") {
                put("type", "integer")
                put(
                    "description",
                    "Execution timeout in seconds (default: 600, configurable via mcp.steroid.execution.timeout registry key)"
                )
            }
            putJsonObject("dialog_killer") {
                put("type", "boolean")
                put(
                    "description",
                    "Override pre-execution dialog killer: true = force enable, false = force disable. Default: use registry setting (mcp.steroid.dialog.killer.enabled)."
                )
            }
        }
        putJsonArray("required") {
            add("project_name")
            add("code")
            add("reason")
            add("task_id")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val params = context.params
        val args = params.arguments

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: project_name")
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: code")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull
        val dialogKiller = args["dialog_killer"]?.jsonPrimitive?.booleanOrNull

        val execCodeParams = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason ?: "No reason provided",
            timeout = timeout,
            dialogKiller = dialogKiller,
        )

        return handler().executeCode(projectName, execCodeParams, context.mcpProgressReporter)
    }
}

interface ExecuteCodeToolHandler {
    suspend fun executeCode(
        projectName: String,
        execCodeParams: ExecCodeParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult
}
