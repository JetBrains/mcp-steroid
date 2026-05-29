/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.NpxBridgeToolCallRequest
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class DevrigListWindowsToolHandler(
    private val services: DevrigServices,
) : ListWindowsToolHandler {
    override suspend fun collectListWindowsResponse(): ListWindowsResponse = coroutineScope {
        val states = services.ideMonitor.states.value.values.toList()
        val responses = states.map { state ->
            async {
                val bridgeBaseUrl = DevrigProjectRoutingService.bridgeBaseUrl(state.ide.mcpUrl)
                val response = services.commandHttpClient.get("$bridgeBaseUrl/npx/v1/windows") {
                    headers {
                        for ((name, value) in state.ide.marker.mcpSteroidServer.headers) {
                            append(name, value)
                        }
                    }
                }
                if (response.status.value !in 200..299) {
                    error("HTTP ${response.status.value} from ${state.ide.label} /npx/v1/windows: ${response.bodyAsText()}")
                }
                state.ide.pid to McpJson.decodeFromString(NpxBridgeWindowsResponse.serializer(), response.bodyAsText())
            }
        }.awaitAll()

        val firstState = states.firstOrNull()
        ListWindowsResponse(
            ide = firstState?.ide?.marker?.ide ?: com.jonnyzzz.mcpSteroid.IdeInfo("devrig", "", "devrig"),
            plugin = firstState?.ide?.marker?.plugin ?: com.jonnyzzz.mcpSteroid.PluginInfo("com.jonnyzzz.mcp-steroid.devrig", "devrig", ""),
            pid = firstState?.ide?.pid ?: ProcessHandle.current().pid(),
            windows = responses.flatMap { (pid, response) ->
                response.windows.map { services.projectRouting.rewriteWindow(pid, it) }
            },
            backgroundTasks = responses.flatMap { (pid, response) ->
                response.backgroundTasks.map { services.projectRouting.rewriteBackgroundTask(pid, it) }
            },
        )
    }
}

class DevrigExecuteCodeToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : ExecuteCodeToolHandler {
    override suspend fun executeCode(
        projectName: String,
        execCodeParams: ExecCodeParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        return bridge.callTool(route, "steroid_execute_code", callProgress) {
            put("project_name", route.originalProjectName)
            put("code", execCodeParams.code)
            put("task_id", execCodeParams.taskId)
            put("reason", execCodeParams.reason)
            execCodeParams.timeout?.let { put("timeout", it) }
            execCodeParams.dialogKiller?.let { put("dialog_killer", it) }
        }
    }
}

class DevrigExecuteFeedbackToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : ExecuteFeedbackToolHandler {
    override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        return bridge.callTool(route, "steroid_execute_feedback") {
            put("project_name", route.originalProjectName)
            put("task_id", params.taskId)
            put("success_rating", params.successRating)
            params.explanation?.let { put("explanation", it) }
            params.code?.let { put("code", it) }
        }
    }
}

class DevrigVisionScreenshotToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : VisionScreenshotToolHandler {
    override suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter,
    ): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        return bridge.callTool(route, "steroid_take_screenshot", mcpProgressReporter) {
            put("project_name", route.originalProjectName)
            put("task_id", screenshotParams.taskId)
            put("reason", screenshotParams.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            screenshotParams.windowId?.let { put("window_id", it) }
        }
    }
}

class DevrigVisionInputToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : VisionInputToolHandler {
    override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        val rawSequence = inputParams.rawSequence
            ?: return ToolCallResult.errorResult("Input sequence cannot be forwarded without the original sequence string")
        return bridge.callTool(route, "steroid_input") {
            put("project_name", route.originalProjectName)
            put("task_id", inputParams.taskId)
            put("reason", inputParams.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            put("window_id", inputParams.windowId)
            put("sequence", rawSequence)
        }
    }
}

class DevrigOpenProjectToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : OpenProjectToolHandler {
    override suspend fun handleOpenProject(openProjectParams: OpenProjectParams): ToolCallResult {
        // Prefer a devrig-managed backend if the agent has one running; otherwise route to the newest
        // discovered IDE instead of failing. Every discovered IDE runs the MCP Steroid plugin.
        // Off the call dispatcher: the managed-pid lookup scans the local backends dir + checks pid liveness.
        val ide = withContext(Dispatchers.IO) { bridge.routing.openProjectTargetIde() }
            ?: return ToolCallResult.errorResult(
                "steroid_open_project requires at least one discovered IDE with the MCP Steroid plugin; start an IDE or call steroid_list_projects"
            )
        val route = ProjectRoute(
            idePid = ide.pid,
            bridgeBaseUrl = DevrigProjectRoutingService.bridgeBaseUrl(ide.mcpUrl),
            headers = ide.marker.mcpSteroidServer.headers,
            originalProjectName = "",
            exposedProjectName = "",
            projectPath = "",
            realProjectHome = java.nio.file.Path.of(".").toAbsolutePath().normalize(),
            projectHash = "",
            ide = ide.marker.ide,
            plugin = ide.marker.plugin,
        )
        return bridge.callTool(route, "steroid_open_project") {
            put("project_path", openProjectParams.projectPath)
            put("trust_project", openProjectParams.trustProject)
            put("task_id", "open-project")
            put("reason", "Open project through devrig")
        }
    }
}

class DevrigToolBridgeClient(
    val routing: DevrigProjectRoutingService,
    private val httpClient: HttpClient,
) {
    suspend fun callTool(
        route: ProjectRoute,
        toolName: String,
        progress: McpProgressReporter? = null,
        arguments: JsonObjectBuilder.() -> Unit,
    ): ToolCallResult {
        val args = buildJsonObject { JsonObjectBuilder(this).arguments() }
        val requestBody = McpJson.encodeToString(
            NpxBridgeToolCallRequest.serializer(),
            NpxBridgeToolCallRequest(name = toolName, arguments = args),
        )
        val url = "${route.bridgeBaseUrl}/npx/v1/tools/call/stream"
        var result: ToolCallResult? = null
        var errorMessage: String? = null

        httpClient.preparePost(url) {
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                for ((name, value) in route.headers) {
                    append(name, value)
                }
            }
            setBody(requestBody)
        }.execute { response ->
            if (response.status.value !in 200..299) {
                errorMessage = "HTTP ${response.status.value} from $url: ${response.bodyAsText()}"
                return@execute
            }
            readNdjson(response.bodyAsChannel()) { line ->
                if (errorMessage != null) return@readNdjson
                val json = try {
                    McpJson.parseToJsonElement(line).jsonObject
                } catch (e: Exception) {
                    errorMessage = "Malformed NDJSON data from $url: ${e.javaClass.simpleName}: ${e.message}; data=${line.take(200)}"
                    return@readNdjson
                }
                when (json["type"]?.jsonPrimitive?.contentOrNull) {
                    "progress" -> json["message"]?.jsonPrimitive?.contentOrNull?.let { progress?.report(it) }
                    "result" -> {
                        val resultElement = json["result"]
                        if (resultElement == null) {
                            errorMessage = "NDJSON result message did not include result from $url"
                            return@readNdjson
                        }
                        result = try {
                            McpJson.decodeFromJsonElement(ToolCallResult.serializer(), resultElement)
                        } catch (e: Exception) {
                            errorMessage = "Malformed NDJSON result from $url: ${e.javaClass.simpleName}: ${e.message}; data=${line.take(200)}"
                            return@readNdjson
                        }
                    }
                    "error" -> errorMessage = json["message"]?.jsonPrimitive?.contentOrNull ?: "Tool call failed"
                }
            }
        }

        errorMessage?.let { return ToolCallResult.errorResult(it) }
        return result ?: ToolCallResult.errorResult("No result received from $url")
    }
}

class JsonObjectBuilder(private val target: kotlinx.serialization.json.JsonObjectBuilder) {
    fun put(key: String, value: String) = target.put(key, value)
    fun put(key: String, value: Int) = target.put(key, value)
    fun put(key: String, value: Double) = target.put(key, value)
    fun put(key: String, value: Boolean) = target.put(key, value)
    fun put(key: String, value: JsonElement) = target.put(key, value)
    fun putJsonArray(key: String, builder: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit) =
        target.putJsonArray(key, builder)
}

suspend fun readNdjson(
    channel: ByteReadChannel,
    emit: suspend (line: String) -> Unit,
) {
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: break
        if (line.isBlank()) continue
        emit(line)
    }
}
