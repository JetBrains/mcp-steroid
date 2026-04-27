/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.execution.ExecutionManager
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

data class ExecCodeParams(
    val taskId: String,
    val code: String,
    val reason: String,
    val timeout: Int,
    //TODO: move that away from here, allow changes only via the McpScriptContext::doNotCancelOnModalityStateChange
    /** If true, cancel execution when a modal dialog appears and return a screenshot. Default true. */
    val cancelOnModal: Boolean = true,

    /** Controls pre-execution dialog killer: null = use registry default, true = force enable, false = force disable. */
    val dialogKiller: Boolean? = null,

    val rawParams: JsonObject,
)

/**
 * Handler for the steroid_execute_code MCP tool.
 */
class ExecuteCodeToolHandler : McpRegistrar {
    private val toolDescription get() = ExecuteCodeToolDescriptionPromptArticle().readPayload(ResourceRegistrar.buildPromptsContext())

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_execute_code",
            description = toolDescription,
            inputSchema = buildJsonObject {
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
                        put("description", "Your task identifier to group related executions. Use the same task_id for all execute_code calls that are part of the same task, and when providing feedback via steroid_execute_feedback.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "IMPORTANT: On your FIRST call, provide the FULL TASK DESCRIPTION from the user - what they originally asked you to do. On subsequent calls, describe what this specific execution aims to achieve. This helps track progress and understand context.")
                    }
                    putJsonObject("timeout") {
                        put("type", "integer")
                        put("description", "Execution timeout in seconds (default: 600, configurable via mcp.steroid.execution.timeout registry key)")
                    }
                    putJsonObject("dialog_killer") {
                        put("type", "boolean")
                        put("description", "Override pre-execution dialog killer: true = force enable, false = force disable. Default: use registry setting (mcp.steroid.dialog.killer.enabled).")
                    }
                    putJsonObject("required_plugins") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                        put(
                            "description",
                            "Optional list of required plugin IDs (example: com.intellij.database). " +
                                "Check installed plugins via steroid_execute_code with PluginManagerCore.getPluginSet().enabledPlugins."
                        )
                    }
                }
                putJsonArray("required") {
                    add("project_name")
                    add("code")
                    add("reason")
                    add("task_id")
                }
            },
            ::handle
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val params = context.params
        val args = params.arguments ?: return errorResult("Missing arguments")

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: code")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull ?: Registry.intValue("mcp.steroid.execution.timeout", 600)
        val dialogKiller = args["dialog_killer"]?.jsonPrimitive?.booleanOrNull
        val requiredPlugins = args["required_plugins"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val missingPlugins = findMissingPlugins(requiredPlugins)
        if (missingPlugins.isNotEmpty()) {
            return errorResult(
                "Missing required plugins: ${missingPlugins.joinToString(", ")}. " +
                    "Check installed plugins via steroid_execute_code with PluginManagerCore.getPluginSet().enabledPlugins."
            )
        }

        val (project, availableNames) = readAction {
            val openProjects = getInstance().openProjects
            openProjects.find { it.name == projectName } to openProjects.map { it.name }
        }
        if (project == null) {
            return errorResult("Project not found: \"$projectName\". Available projects: $availableNames")
        }

        val execCodeParams = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason ?: "No reason provided",
            timeout = timeout,
            dialogKiller = dialogKiller,
            rawParams = params.arguments
        )

        val result = project
            .service<ExecutionManager>()
            .executeWithProgress(execCodeParams, context.mcpProgressReporter)

        runCatching {
            analyticsBeacon.capture(
                event = "exec_code",
                project = project,
                properties = mapOf(
                    "result" to if (result.isError) "error" else "success"
                )
            )
        }

        return ExecuteCodeBuildAbortGuidance.appendTo(
            result = result,
            projectBasePath = project.basePath?.let(Path::of),
        )
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true
    )

    private fun findMissingPlugins(requiredPlugins: List<String>): List<String> {
        if (requiredPlugins.isEmpty()) return emptyList()
        return requiredPlugins.filter { pluginId ->
            val resolvedId = PluginId.getId(pluginId)
            val resolved = PluginManagerCore.getPlugin(resolvedId)
            resolved == null || !PluginManagerCore.isLoaded(resolvedId)
        }
    }

}

internal object ExecuteCodeBuildAbortGuidance {
    private const val CLAUDE_FETCH_RESOURCE_TOOL = "mcp__mcp-steroid__steroid_fetch_resource"

    private val abortedWithoutErrorsPattern = Regex(
        pattern = """(?im)\b(?:Build|Compile)\s+errors:\s*false,\s*aborted:\s*true\b"""
    )

    fun appendTo(result: ToolCallResult, projectBasePath: Path?): ToolCallResult {
        val outputText = result.content
            .filterIsInstance<ContentItem.Text>()
            .joinToString("\n") { it.text }
        val guidance = guidanceFor(outputText, projectBasePath) ?: return result
        return result.copy(content = result.content + ContentItem.Text("\n$guidance"))
    }

    fun guidanceFor(outputText: String, projectBasePath: Path?): String? {
        if (!abortedWithoutErrorsPattern.containsMatchIn(outputText)) return null

        val resourceTarget = resourceTargetText(projectBasePath)
        return "REQUIRED ACTION: IDE build was aborted without compiler errors. " +
            "NEXT TOOL CALL must be $CLAUDE_FETCH_RESOURCE_TOOL with URI $resourceTarget before using Bash. " +
            "Then run the fetched sync/configuration pattern and retry the IDE build/test. " +
            "Use Bash only if sync fails or times out."
    }

    private fun resourceTargetText(projectBasePath: Path?): String {
        val resourceUris = detectBuildResourceUris(projectBasePath)
        return if (resourceUris.size == 1) {
            resourceUris.single()
        } else {
            "the matching resource (${resourceUris.joinToString(" or ")})"
        }
    }

    private fun detectBuildResourceUris(projectBasePath: Path?): List<String> {
        val gradleUri = ExecuteCodeGradlePromptArticle().uri
        val mavenUri = ExecuteCodeMavenPromptArticle().uri
        if (projectBasePath == null) return listOf(gradleUri, mavenUri)

        val hasGradle = listOf(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts",
            "gradlew",
        ).any { Files.isRegularFile(projectBasePath.resolve(it)) }
        val hasMaven = Files.isRegularFile(projectBasePath.resolve("pom.xml"))

        return buildList {
            if (hasGradle) add(gradleUri)
            if (hasMaven) add(mavenUri)
        }.ifEmpty {
            listOf(gradleUri, mavenUri)
        }
    }
}
