/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.features

import kotlinx.serialization.json.*

/**
 * Metadata for a tool definition: name, description, and input schema.
 */
data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/**
 * Declares all MCP tool definitions with their names, descriptions, and input schemas.
 * The descriptions and schemas match the existing tool handler implementations.
 */
object McpToolDefinitions {
    val LIST_PROJECTS = ToolDef(
        name = "steroid_list_projects",
        description = "List all open projects in the IDE. Returns project names that can be used with steroid_execute_code and steroid_open_project.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            putJsonArray("required") {}
        }
    )

    val LIST_WINDOWS = ToolDef(
        name = "steroid_list_windows",
        description = "List open IDE windows and their associated projects. Use this to choose project_name for screenshot/input tools in multi-window setups.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            putJsonArray("required") {}
        }
    )

    // Note: ExecuteCode has a dynamic description from generated prompts.
    // The actual description is set via overrideExecuteCodeDescription().
    val EXECUTE_CODE = ToolDef(
        name = "steroid_execute_code",
        description = "Execute a Kotlin script in the IDE's scripting environment.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project_name") {
                    put("type", "string")
                    put("description", "Target project name (from steroid_list_projects)")
                }
                putJsonObject("code") {
                    put("type", "string")
                    put("description", "Kotlin script code to execute")
                }
                putJsonObject("task_id") {
                    put("type", "string")
                    put("description", "Your task identifier to group related executions")
                }
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "Reason for execution. Required for audit logs.")
                }
                putJsonObject("timeout") {
                    put("type", "integer")
                    put("description", "Timeout in seconds (optional)")
                }
                putJsonObject("dialog_killer") {
                    put("type", "boolean")
                    put("description", "Enable dialog killer during execution (optional)")
                }
            }
            putJsonArray("required") {
                add("project_name")
                add("code")
                add("task_id")
                add("reason")
            }
        }
    )

    val ACTION_DISCOVERY = ToolDef(
        name = "steroid_action_discovery",
        description = "Discover available editor actions, quick-fixes, and gutter actions for a file and caret offset.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project_name") {
                    put("type", "string")
                    put("description", "Target project name")
                }
                putJsonObject("file_path") {
                    put("type", "string")
                    put("description", "Relative file path in the project")
                }
                putJsonObject("caret_offset") {
                    put("type", "integer")
                    put("description", "Caret offset in the file (default: 0)")
                }
                putJsonObject("action_groups") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Action groups to discover")
                }
                putJsonObject("task_id") {
                    put("type", "string")
                    put("description", "Task identifier")
                }
            }
            putJsonArray("required") {
                add("project_name")
                add("file_path")
            }
        }
    )

    val VISION_SCREENSHOT = ToolDef(
        name = "steroid_take_screenshot",
        description = """
            Capture a screenshot of the IDE and return an image payload.

            HEAVY ENDPOINT: This is intended for debugging and tricky configuration only.
            Prefer steroid_execute_code for regular automation.

            Use steroid_list_windows when multiple IDE windows are open and pass window_id to target a specific window.
        """.trimIndent(),
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project_name") {
                    put("type", "string")
                    put("description", "Target project name")
                }
                putJsonObject("task_id") {
                    put("type", "string")
                    put("description", "Your task identifier")
                }
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "Reason for taking screenshot")
                }
                putJsonObject("window_id") {
                    put("type", "string")
                    put("description", "Target window ID (from steroid_list_windows)")
                }
            }
            putJsonArray("required") {
                add("project_name")
                add("task_id")
                add("reason")
            }
        }
    )

    val VISION_INPUT = ToolDef(
        name = "steroid_input",
        description = """
            Send input events (keyboard + mouse) to the IDE using a sequence string.

            HEAVY ENDPOINT: Intended for debugging only. Prefer steroid_execute_code for regular automation.
        """.trimIndent(),
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("project_name") {
                    put("type", "string")
                    put("description", "Target project name")
                }
                putJsonObject("task_id") {
                    put("type", "string")
                    put("description", "Task identifier")
                }
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "Reason for input")
                }
                putJsonObject("screenshot_execution_id") {
                    put("type", "string")
                    put("description", "Execution ID from steroid_take_screenshot")
                }
                putJsonObject("sequence") {
                    put("type", "string")
                    put("description", "Input sequence (comma-separated steps)")
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
    )

    val OPEN_PROJECT = ToolDef(
        name = "steroid_open_project",
        description = """
            Open a project in the IDE. This tool initiates the project opening process and returns quickly.

            IMPORTANT: Project opening is ASYNCHRONOUS. This tool returns immediately; you MUST poll to verify the project is fully ready before using it.
        """.trimIndent(),
        inputSchema = buildJsonObject {
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
    )

    val EXECUTE_FEEDBACK = ToolDef(
        name = "steroid_execute_feedback",
        description = """
            Provide feedback on the result of a steroid_execute_code call.

            Use this tool to rate execution results and track what worked or didn't work.
            Feedback helps track execution history and identify patterns for improvement.
        """.trimIndent(),
        inputSchema = buildJsonObject {
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
                    put("description", "The execution_id returned from steroid_execute_code")
                }
                putJsonObject("success_rating") {
                    put("type", "number")
                    put("minimum", 0.0)
                    put("maximum", 1.0)
                    put("description", "Rate the success from 0.00 (failure) to 1.00 (success)")
                }
putJsonObject("explanation") {
                    put("type", "string")
                    put("description", "Explain why you gave this rating")
                }
                putJsonObject("code") {
                    put("type", "string")
                    put("description", "Optional: The code snippet that was executed")
                }
            }
            putJsonArray("required") {
                add("project_name")
                add("task_id")
                add("success_rating")
                add("explanation")
            }
        }
    )

    /** All tool definitions in registration order */
    val ALL: List<ToolDef> = listOf(
        LIST_PROJECTS,
        LIST_WINDOWS,
        EXECUTE_CODE,
        ACTION_DISCOVERY,
        VISION_SCREENSHOT,
        VISION_INPUT,
        OPEN_PROJECT,
        EXECUTE_FEEDBACK,
    )
}
