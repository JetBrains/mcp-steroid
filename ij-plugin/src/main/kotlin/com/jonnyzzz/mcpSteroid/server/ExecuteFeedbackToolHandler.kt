/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager.getInstance
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.storage.ExecutionStorage
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.serialization.json.*

/**
 * Handler for the steroid_execute_feedback MCP tool.
 *
 * Allows agents to provide feedback on execution results, including:
 * - Success rating (0.00 to 1.00)
 * - Explanation of the rating
 * - Association with a task_id
 */
class ExecuteFeedbackToolHandler : McpRegistrar {
    private val log = thisLogger()
    private val json = Json {
        prettyPrint = true
    }

    private val toolDescription get() = """
            Provide feedback on the result of a steroid_execute_code call.
            
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
            - code (optional): The code snippet that was executed
            
            Feedback helps track execution history and identify patterns for improvement.
        """.trimIndent()

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_execute_feedback",
            description = toolDescription,
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
                        put("description", "Explain why you gave this rating. What worked? What didn't? What will you try next?")
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
        ) { context ->
            handle(context.params)
        }
    }

    private suspend fun handle(params: ToolCallParams): ToolCallResult {
        val args = params.arguments ?: return errorResult("Missing arguments")

        val aggregated = validate(args)
        if (aggregated != null) return errorResult(aggregated)
        // Past this point, validate() guarantees every required field is non-null.
        val projectName = args["project_name"]!!.jsonPrimitive.content
        val taskId = args["task_id"]!!.jsonPrimitive.content
        val successRating = args["success_rating"]!!.jsonPrimitive.double
        val explanation = args["explanation"]!!.jsonPrimitive.content
        // execution_id is optional — noted for context but value is not currently used
        val code = args["code"]?.jsonPrimitive?.contentOrNull

        log.info("Feedback is submitted: " + json.encodeToString(params.rawArguments))

        val project = readAction {
            getInstance().openProjects.find { it.name == projectName }
        } ?: return errorResult("Project not found: $projectName")

        try {
            val executionStorage = project.service<ExecutionStorage>()
            val executionId = executionStorage.writeExecutionFeedback(taskId = taskId, params)
            if (code != null) {
                executionStorage.writeCodeExecutionData(executionId, "script.kts", code)
            }

            executionStorage.writeCodeExecutionData(executionId, "explanation.txt", explanation)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to store execution feedback for task_id=$taskId", e)
        }

        // Capture feedback event
        analyticsBeacon.capture(
            event = "execute_feedback",
            project = project,
            properties = mapOf(
                "success_rating" to successRating,
                "has_explanation" to explanation.isNotBlank(),
                "has_code" to (code != null)
            )
        )

        // Capture status score (convert 0.0-1.0 to 0-100)
        val score = (successRating * 100).toInt()
        analyticsBeacon.captureScore(
            score = score,
            context = "feedback",
            project = project,
            properties = mapOf(
                "task_id" to taskId
            )
        )

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = "ACK!"))
        )
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = message)),
        isError = true
    )

    internal companion object {
        /**
         * Aggregate every validation problem in [args] into one message, or return
         * null when the input is fully valid. Surfaced as an internal function so
         * `ExecuteFeedbackToolHandlerTest` can assert the contract without standing
         * up the whole MCP transport. See the INFRA-REPORT note at the call site
         * in `handle()` for the rationale (agents were losing 3+ round-trips on
         * sequential rejections).
         */
        internal fun validate(args: JsonObject): String? {
            val problems = mutableListOf<String>()

            val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            if (projectName.isNullOrBlank()) {
                problems += "project_name is required (from steroid_list_projects)"
            }

            val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            if (taskId.isNullOrBlank()) {
                problems += "task_id is required (same id you passed to steroid_execute_code)"
            }

            val successRating = args["success_rating"]?.jsonPrimitive?.doubleOrNull
            if (successRating == null) {
                problems += "success_rating is required (number in 0.00..1.00 — do NOT send `rating`)"
            } else if (successRating !in 0.0..1.0) {
                problems += "success_rating=$successRating is out of range (must be 0.00..1.00)"
            }

            val explanation = args["explanation"]?.jsonPrimitive?.contentOrNull
            if (explanation.isNullOrBlank()) {
                problems += "explanation is required (free-form: what worked, what didn't, what you'll try next)"
            }

            if (problems.isEmpty()) return null
            return buildString {
                appendLine("steroid_execute_feedback: ${problems.size} validation problem${if (problems.size == 1) "" else "s"}:")
                problems.forEach { appendLine("  - $it") }
                append("Required: project_name, task_id, success_rating, explanation. Optional: execution_id, code.")
            }
        }
    }
}
