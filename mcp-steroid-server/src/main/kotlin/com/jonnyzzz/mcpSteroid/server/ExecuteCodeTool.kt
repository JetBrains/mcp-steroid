package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.boolean
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.int
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import kotlinx.serialization.Serializable

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
class ExecuteCodeToolSpec(val handler: () -> ExecuteCodeToolHandler) : McpToolBase() {
    override val name = "steroid_execute_code"
    override val description get() = ExecuteCodeToolDescriptionPromptArticle().readPayload(PromptsContext.Generic)

    val projectName = CommonToolParams.projectName().registerToSchema()

    val code = InputSchemaElement.param("code")
        .description("Kotlin suspend method body")
        .string()
        .required()
        .registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = InputSchemaElement.param("reason")
        .description("IMPORTANT: On your FIRST call, provide the FULL TASK DESCRIPTION from the user - what they originally asked you to do. On subsequent calls, describe what this specific execution aims to achieve. This helps track progress and understand context.")
        .string()
        .required()
        .registerToSchema()

    //TODO: Drop timeout
    val timeout = InputSchemaElement.param("timeout")
        .description("Execution timeout in seconds (default: 600, configurable via mcp.steroid.execution.timeout registry key)")
        .int()
        .registerToSchema()

    //TODO: Drop dialog killer
    val dialogKiller = InputSchemaElement.param("dialog_killer")
        .description("Override pre-execution dialog killer: true = force enable, false = force disable. Default: use registry setting (mcp.steroid.dialog.killer.enabled).")
        .boolean()
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectName = context[projectName]
        val code = context[code]
        val taskId = context[taskId]
        val reason = context[reason]
        val timeout = context[timeout]
        val dialogKiller = context[dialogKiller]

        val execCodeParams = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason,
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
