package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.thisLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject


/**
 * Handler for the steroid_open_project MCP tool.
 *
 * This tool initiates opening a project in IntelliJ. It does NOT wait for the project
 * to fully open - instead it returns quickly so the client can interact with any dialogs
 * that may appear (such as the trust project dialog) using screenshot/input tools.
 *
 * The tool can optionally trust the project path before opening, which allows skipping
 * the trust dialog.
 */
class OpenProjectToolSpec(val handler: () -> OpenProjectToolHandler) : McpTool {
    private val logger = thisLogger()

    override val name = "steroid_open_project"
    override val description = """
        Open a project in the IDE. This tool initiates the project opening process and returns quickly.

        IMPORTANT: Project opening is ASYNCHRONOUS. This tool returns immediately; you MUST poll to verify the project is fully ready before using it.

        Verification Workflow:
        1. Call steroid_open_project with the project path
        2. Poll steroid_list_windows repeatedly (every 2-3 seconds) until:
           - The project appears in the windows list
           - modalDialogShowing is false (no dialogs blocking)
           - indexingInProgress is false (indexing complete)
           - projectInitialized is true
        3. If modalDialogShowing is true, use steroid_take_screenshot + steroid_input to handle dialogs
        4. Use steroid_take_screenshot to visually confirm the project is fully loaded
        5. Verify with steroid_list_projects that the project appears

        Dialog Handling:
        - If trust_project=true (default), the trust dialog is skipped automatically
        - Other dialogs (project type, SDK selection, etc.) may still appear
        - Always check modalDialogShowing in steroid_list_windows response
    """.trimIndent()

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to the project directory to open.")
            }
            putJsonObject("task_id") {
                put("type", "string")
                put("description", "Your task identifier to group related executions.")
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "Reason for opening the project. Required for audit logs.")
            }
            putJsonObject("trust_project") {
                put("type", "boolean")
                put("description", "If true, trust the project path before opening (skips trust dialog). Default: true")
            }
        }
        putJsonArray("required") {
            add("project_path")
            add("task_id")
            add("reason")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments
        val projectPathStr = args["project_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: project_path")
        args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: task_id")
        args["reason"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: reason")
        val trustProject = args["trust_project"]?.jsonPrimitive?.boolean ?: true

        val requestedProjectPath = try {
            Path.of(projectPathStr).toAbsolutePath().normalize()
        } catch (e: Exception) {
            logger.warn("Invalid project path: $projectPathStr", e)
            return ToolCallResult.errorResult("Invalid project path: $projectPathStr - ${e.message}")
        }

        // Validate that the path exists
        if (!Files.isDirectory(requestedProjectPath)) {
            return ToolCallResult.errorResult("Project path is not a directory: $requestedProjectPath")
        }

        val projectPath = try {
            withContext(Dispatchers.IO) {
                requestedProjectPath.toRealPath()
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve project path: $requestedProjectPath", e)
            return ToolCallResult.errorResult("Failed to resolve project path: $requestedProjectPath - ${e.message}")
        }

        return handler().handleOpenProject(
            OpenProjectParams(
                projectPath = projectPath.toString(),
                trustProject = trustProject,
            )
        )
    }
}

@Serializable
data class OpenProjectParams(
    val projectPath: String,
    val trustProject: Boolean,
)

interface OpenProjectToolHandler {
    suspend fun handleOpenProject(openProjectParams: OpenProjectParams): ToolCallResult
}
