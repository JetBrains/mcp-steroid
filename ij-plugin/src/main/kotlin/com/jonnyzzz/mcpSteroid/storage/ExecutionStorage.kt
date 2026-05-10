/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

@Serializable
data class ExecutionId(val executionId: String)

@Serializable
data class TextMessage(val text: String)

@Serializable
data class ImageMessage(
    val fileName: String,
    val mimeType: String,
)

@Serializable
data class ToolCallMetadata(
    val toolName: String,
    val timestamp: String,
    val projectName: String,
    val taskId: String? = null,
    val arguments: JsonObject,
)

inline val Project.executionStorage : ExecutionStorage get() = service()

/**
 * File-based storage for execution history.
 * APPEND-ONLY: Files are never deleted, only added.
 *
 * Directory structure:
 * .idea/mcp-steroid/                 - Base folder (see StoragePaths)
 *   {execution-id}/
 *     project.txt                    - Project name (line 1) and path (line 2)
 *     tool.json                      - Tool name + arguments metadata
 *     script.kts                     - Original code submitted by LLM
 *     params.json                    - Execution parameters
 *     output.jsonl                   - Output messages (append-only)
 *     review.kts                     - Code for review (may have user edits)
 *
 * Registry key:
 * - mcp.steroid.storage.path        - Override path (empty = .idea/mcp-steroid)
 */
@Service(Service.Level.PROJECT)
class ExecutionStorage(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val log = thisLogger()

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    val oneLineJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val baseDir: Path
        get() = project.storagePaths.getGetMcpRunDir()


    private val ExecutionId.dir: Path
        get() {
            val dir = baseDir.resolve(executionId)
            Files.createDirectories(dir)
            return dir
        }

    fun resolveExecutionDir(executionId: ExecutionId): Path {
        return executionId.dir
    }

    suspend fun appendExecutionEvent(executionId: ExecutionId, text: String) {
        appendExecutionEvent(executionId, TextMessage(text))
    }

    suspend inline fun <reified T> appendExecutionEvent(executionId: ExecutionId, message: T) {
        appendExecutionEventJson(executionId, oneLineJson.encodeToString(message))
    }

    suspend fun writeCodeErrorEvent(executionId: ExecutionId, text: String) {
        writeCodeExecutionData(executionId, "error.txt", text)
    }

    suspend fun appendExecutionEventJson(executionId: ExecutionId, json: String) {
        withContext(Dispatchers.IO) {
            val file = executionId.dir.resolve("output.jsonl")
            require(json.lines().size == 1)
            require(json.startsWith("{") && json.endsWith("}"))

            Files.writeString(
                file,
                json + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    suspend inline fun <reified T> writeCodeExecutionData(executionId: ExecutionId, name: String, data: T) {
        writeCodeExecutionData(executionId, name, json.encodeToString(data))
    }

    suspend fun writeCodeExecutionData(executionId: ExecutionId, name: String, data: String): Path {
        val path = executionId.dir.resolve(name)
        withContext(Dispatchers.IO) {
            path.writeText(data)
        }
        return path
    }

    suspend fun writeBinaryExecutionData(executionId: ExecutionId, name: String, data: ByteArray): Path {
        val path = executionId.dir.resolve(name)
        withContext(Dispatchers.IO) {
            Files.write(path, data)
        }
        return path
    }

    fun resolveExecutionPath(executionId: ExecutionId, name: String): Path {
        require(!name.contains("..") && !name.contains("/") && !name.contains("\\")) {
            "Invalid execution file name: $name"
        }
        return executionId.dir.resolve(name)
    }

    fun findExecutionId(executionId: String) : ExecutionId? {
        if (executionId.contains("/") || executionId.contains("..")) return null

        // tool.json is the universal sentinel: every writeToolMetadata() call writes it,
        // covering writeNewExecution / writeExecutionFeedback / writeToolCall.
        val path = baseDir.resolve(executionId).resolve("tool.json")
        if (!path.isRegularFile()) return null

        return ExecutionId(executionId)
    }

    suspend fun writeExecutionFeedback(taskId: String, element: FeedbackParams) : ExecutionId {
        val executionId = newExecutionId("feedback-$taskId")
        writeToolMetadata(executionId, "steroid_execute_feedback", json.encodeToJsonElement(element).jsonObject, taskId)
        writeCodeExecutionData(executionId, "feedback.json", element)
        writeCodeExecutionData(executionId, "execution-id.txt", executionId.executionId)
        writeProjectInfo(executionId)
        return executionId
    }

    private fun newExecutionId(taskId: String): ExecutionId {
        val pattern = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val timestamp = LocalDateTime.now().format(pattern)
        val invalidPath = Regex("[^a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE)
        val id = "eid_" + timestamp + "-" + invalidPath.replace(taskId, "_")
        return ExecutionId(id)
    }

    suspend fun writeNewExecution(exec: ExecCodeParams) : ExecutionId {
        val storage = project.executionStorage

        val executionId = storage.newExecutionId(exec.taskId)
        storage.writeToolMetadata(executionId, "steroid_execute_code", json.encodeToJsonElement(exec).jsonObject, exec.taskId)
        storage.writeCodeExecutionData(executionId, "reason.txt", exec.reason)
        storage.writeCodeExecutionData(executionId, "script.kts", exec.code)
        storage.writeCodeExecutionData(executionId, "execution-id.txt", executionId.executionId)
        storage.writeProjectInfo(executionId)

        return executionId
    }

    suspend fun writeToolCall(toolName: String, arguments: JsonObject, taskId: String? = null): ExecutionId {
        val executionId = newExecutionId(taskId ?: "tool-$toolName")
        writeToolMetadata(executionId, toolName, arguments, taskId)
        writeCodeExecutionData(executionId, "params.json", arguments ?: buildJsonObject { })
        writeCodeExecutionData(executionId, "execution-id.txt", executionId.executionId)
        writeProjectInfo(executionId)
        return executionId
    }

    private suspend fun writeToolMetadata(
        executionId: ExecutionId,
        toolName: String,
        arguments: JsonObject?,
        taskId: String? = null,
    ) {
        val metadata = ToolCallMetadata(
            toolName = toolName,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            projectName = project.name,
            taskId = taskId,
            arguments = arguments ?: buildJsonObject { }
        )
        writeCodeExecutionData(executionId, "tool.json", metadata)
    }

    private suspend fun writeProjectInfo(executionId: ExecutionId) {
        val projectInfo = buildString {
            appendLine(project.name)
            project.basePath?.let { appendLine(it) }
        }
        writeCodeExecutionData(executionId, "project.txt", projectInfo)
    }

    suspend fun writeWrappedScript(executionId: ExecutionId, code: String) {
        writeCodeExecutionData(executionId, "script-wrapped.kts", code)
    }

    suspend fun writeCodeReviewFile(executionId: ExecutionId, codeForReview: String): Path {
        return writeCodeExecutionData(executionId, "review.kts", codeForReview)
    }

    suspend fun removeCodeReviewFile(executionId: ExecutionId) {
        withContext(Dispatchers.IO) {
            runCatching {
                executionId.dir.resolve("review.kts").deleteIfExists()
            }
        }
    }

    suspend fun createCompilerOutputDir(executionId: ExecutionId): Path {
        return withContext(Dispatchers.IO) {
            val dir = executionId.dir.resolve("compiled")
            Files.createDirectories(dir)
            dir
        }
    }
}
