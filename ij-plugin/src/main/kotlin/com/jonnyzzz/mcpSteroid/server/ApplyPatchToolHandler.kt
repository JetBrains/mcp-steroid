/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.observation.Observation
import com.jonnyzzz.mcpSteroid.execution.ApplyPatchException
import com.jonnyzzz.mcpSteroid.execution.ApplyPatchHunk
import com.jonnyzzz.mcpSteroid.execution.dialogKiller
import com.jonnyzzz.mcpSteroid.execution.executeApplyPatch
import com.jonnyzzz.mcpSteroid.execution.vfsRefreshService
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.coroutines.withTimeoutOrNull
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ApplyPatchToolDescriptionPromptArticle
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.serialization.json.*

/**
 * First-class MCP tool for atomic multi-site literal-text patching.
 *
 * Surfaces the same underlying [executeApplyPatch] engine that
 * [com.jonnyzzz.mcpSteroid.execution.McpScriptContextImpl.applyPatch] uses,
 * but without the kotlinc compile step — so Claude Code's hard-coded ~60s
 * MCP tool timeout (issue #16837) no longer truncates 5+ hunk patches.
 *
 * Input shape mirrors the DSL:
 * ```json
 * {
 *   "project_name": "project-home",
 *   "task_id": "feature-x",
 *   "reason": "rename logger name across services",
 *   "hunks": [
 *     {"file_path": "/abs/A.java", "old_string": "foo", "new_string": "bar"},
 *     {"file_path": "/abs/A.java", "old_string": "baz", "new_string": "qux"},
 *     {"file_path": "/abs/B.java", "old_string": "log1", "new_string": "log2"}
 *   ]
 * }
 * ```
 *
 * Semantics: pre-flight validates every `old_string` is present exactly once;
 * all edits land as a single undoable [WriteCommandAction], PSI committed in
 * the same action, VFS async-refreshed on completion.
 */
class ApplyPatchToolHandler : McpRegistrar {
    private val log = Logger.getInstance(ApplyPatchToolHandler::class.java)

    private val toolDescription get() = ApplyPatchToolDescriptionPromptArticle().readPayload(ResourceRegistrar.buildPromptsContext())

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_apply_patch",
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
                        put("description", "Your task identifier; reuse across related calls.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "One-line summary of what this patch changes.")
                    }
                    putJsonObject("hunks") {
                        put("type", "array")
                        put("description", "Literal-text hunks. Each hunk's old_string must occur exactly once in its file.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("file_path") {
                                    put("type", "string")
                                    put("description", "Absolute filesystem path. Matches Claude Code `Edit`'s `file_path` field.")
                                }
                                putJsonObject("old_string") {
                                    put("type", "string")
                                    put("description", "Literal text to replace (must occur exactly once).")
                                }
                                putJsonObject("new_string") {
                                    put("type", "string")
                                    put("description", "Replacement text.")
                                }
                            }
                            putJsonArray("required") {
                                add("file_path")
                                add("old_string")
                                add("new_string")
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    add("project_name")
                    add("task_id")
                    add("hunks")
                }
            },
            ::handle,
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments ?: return errorResult("Missing arguments")

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")

        val hunksJson = args["hunks"]?.jsonArray
            ?: return errorResult("Missing required parameter: hunks (array)")
        if (hunksJson.isEmpty()) return errorResult("hunks array is empty")

        val hunks = hunksJson.mapIndexed { i, el ->
            val o = (el as? JsonObject) ?: return errorResult("hunks[$i] is not an object")
            val filePath = o["file_path"]?.jsonPrimitive?.contentOrNull
                ?: return errorResult("hunks[$i].file_path is required")
            val oldString = o["old_string"]?.jsonPrimitive?.contentOrNull
                ?: return errorResult("hunks[$i].old_string is required")
            val newString = o["new_string"]?.jsonPrimitive?.contentOrNull
                ?: return errorResult("hunks[$i].new_string is required")
            ApplyPatchHunk(filePath = filePath, oldString = oldString, newString = newString)
        }

        val (project, availableNames) = readAction {
            val openProjects = getInstance().openProjects
            openProjects.find { it.name == projectName } to openProjects.map { it.name }
        }
        if (project == null) {
            return errorResult("Project not found: \"$projectName\". Available projects: $availableNames")
        }

        // Kill any modal dialog that may be blocking EDT dispatch. Without this,
        // the withContext(EDT + ModalityState.nonModal()) inside executeApplyPatch
        // will wait until the modal dismisses — which can exceed Claude Code's
        // hardcoded ~60s MCP tool timeout even though the patch itself is sub-ms.
        // This mirrors what ExecutionManager does before each execute_code call.
        val executionId = ExecutionId("apply-patch-${System.currentTimeMillis()}")
        dialogKiller().killProjectDialogs(
            project = project,
            executionId = executionId,
            logMessage = { log.info(it) },
            forceEnabled = null,
        )

        // Wait for IDE background configuration (project save, indexing, etc.) to
        // settle before the write action. Without this, in a freshly-opened IDE
        // the project-save activity that follows DialogKiller can hold the
        // write-intent read lock for 10+ s, causing `WriteCommandAction` to time
        // out before Claude's 60 s MCP tool cap. 5 s is a pragmatic upper bound:
        // if configuration isn't done by then, proceed anyway — the write action
        // retries on its own.
        withTimeoutOrNull(5_000L) {
            Observation.awaitConfiguration(project)
        }

        val result = try {
            executeApplyPatch(project, hunks) { path ->
                LocalFileSystem.getInstance().findFileByPath(path)
            }
        } catch (e: ApplyPatchException) {
            runCatching {
                analyticsBeacon.capture(
                    event = "apply_patch",
                    project = project,
                    properties = mapOf("result" to "error"),
                )
            }
            return errorResult(e.message ?: "apply-patch failed with no message")
        }

        project.vfsRefreshService.scheduleAsyncRefresh()

        runCatching {
            analyticsBeacon.capture(
                event = "apply_patch",
                project = project,
                properties = mapOf(
                    "result" to "success",
                    "hunks" to result.hunkCount.toString(),
                    "files" to result.fileCount.toString(),
                ),
            )
        }

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = result.toString())),
            isError = false,
        )
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true,
    )

}
