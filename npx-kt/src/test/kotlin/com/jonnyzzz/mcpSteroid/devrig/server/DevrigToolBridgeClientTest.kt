/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class DevrigToolBridgeClientTest {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private var port: Int = 0
    private var receivedAuth: String? = null
    private var receivedBody: String? = null
    private val beforeResultEvents = mutableListOf<String>()
    private var streamResponse: String? = null
    private var holdStreamEntered: CompletableDeferred<Unit>? = null
    private var holdStreamRelease: CompletableDeferred<Unit>? = null
    private var httpStatus = HttpStatusCode.OK
    private var httpBody = ""

    @BeforeEach
    fun setUp() {
        port = freePort()
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post("/npx/v1/tools/call/stream") {
                    receivedAuth = call.request.headers["Authorization"]
                    receivedBody = call.receiveText()
                    if (httpStatus != HttpStatusCode.OK) {
                        call.respondText(httpBody, ContentType.Text.Plain, httpStatus)
                        return@post
                    }
                    call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                        val custom = streamResponse
                        if (custom != null) {
                            write(custom)
                            flush()
                            return@respondTextWriter
                        }
                        val hold = holdStreamRelease
                        if (hold != null) {
                            write("""{"type":"progress","message":"started"}""" + "\n")
                            flush()
                            holdStreamEntered?.complete(Unit)
                            hold.await()
                            return@respondTextWriter
                        }
                        beforeResultEvents.forEach { write(it) }
                        val result = ToolCallResult(
                            content = listOf(ContentItem.Text("ok")),
                            isError = false,
                        )
                        write(
                            buildJsonObject {
                                put("type", "result")
                                put("result", McpJson.encodeToJsonElement(ToolCallResult.serializer(), result))
                            }.toString() + "\n"
                        )
                        flush()
                    }
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }

        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 2_000
            }
            expectSuccess = false
        }
    }

    @AfterEach
    fun tearDown() {
        beforeResultEvents.clear()
        streamResponse = null
        holdStreamEntered = null
        holdStreamRelease = null
        httpStatus = HttpStatusCode.OK
        httpBody = ""
        httpClient.close()
        server.stop(0L, 0L)
    }

    @Test
    fun `bridge client sends bearer token and rewritten original project name`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val route = route(tempDir)
        val bridge = DevrigToolBridgeClient(
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route, "steroid_execute_code") {
            put("project_name", route.originalProjectName)
            put("task_id", "task")
            put("reason", "test")
            put("code", "println(1)")
        }

        assertEquals(false, result.isError)
        assertEquals("Bearer secret-token", receivedAuth)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_code", json["name"]?.jsonPrimitive?.content)
        assertEquals(
            "original-project",
            json["arguments"]?.jsonObject?.get("project_name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `execute feedback bridge handler forwards rating explanation and code`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val handler = DevrigExecuteFeedbackToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.handleFeedback(
            projectName = route.exposedProjectName,
            params = FeedbackParams(
                taskId = "feedback-task",
                successRating = 0.75,
                explanation = "worked",
                code = "println(1)",
            ),
        )

        assertEquals(false, result.isError)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_feedback", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("feedback-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("0.75", arguments["success_rating"]?.jsonPrimitive?.content)
        assertEquals("worked", arguments["explanation"]?.jsonPrimitive?.content)
        assertEquals("println(1)", arguments["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `screenshot bridge handler remembers returned execution id`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val screenshotExecutionId = "eid_20260518T125900-npx-screenshot"
        val resultBody = ToolCallResult(
            content = listOf(ContentItem.Text("screenshot saved in $screenshotExecutionId")),
            isError = false,
        )
        streamResponse = """{"type":"result","result": ${McpJson.encodeToJsonElement(ToolCallResult.serializer(), resultBody)}}""" + "\n"
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val handler = DevrigVisionScreenshotToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.screenshotWindow(
            projectName = route.exposedProjectName,
            screenshotParams = ScreenshotParams(
                taskId = "screenshot-task",
                reason = "capture state",
            ),
            mcpProgressReporter = object : McpProgressReporter {
                override fun report(message: String) = Unit
            },
        )

        assertEquals(false, result.isError)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_take_screenshot", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("screenshot-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("capture state", arguments["reason"]?.jsonPrimitive?.content)
        assertEquals(null, arguments["window_id"])
        assertEquals(42L, routing.routeScreenshotExecution(screenshotExecutionId))
    }

    @Test
    fun `input bridge handler forwards when screenshot id belongs to the same ide`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectA = Files.createDirectories(tempDir.resolve("project-a"))
        val projectB = Files.createDirectories(tempDir.resolve("project-b"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectA),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("project-a", projectA.toString())),
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, projectHome = projectB),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("project-b", projectB.toString())),
            ),
        )
        val route = routing.routes().values.single { it.idePid == 43L }
        routing.rememberScreenshotExecution("eid_same_ide", route)
        val handler = DevrigVisionInputToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.handleInputSequence(
            projectName = route.exposedProjectName,
            inputParams = InputParams(
                taskId = "input-task",
                reason = "press key",
                screenshotExecutionId = "eid_same_ide",
                sequence = emptyList(),
                rawSequence = "press:ENTER",
            ),
        )

        assertEquals(false, result.isError)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_input", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("project-b", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("input-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("press key", arguments["reason"]?.jsonPrimitive?.content)
        assertEquals("eid_same_ide", arguments["screenshot_execution_id"]?.jsonPrimitive?.content)
        assertEquals("press:ENTER", arguments["sequence"]?.jsonPrimitive?.content)
    }

    @Test
    fun `input bridge handler rejects screenshot id from another ide`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectA = Files.createDirectories(tempDir.resolve("project-a"))
        val projectB = Files.createDirectories(tempDir.resolve("project-b"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectA),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("project-a", projectA.toString())),
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, projectHome = projectB),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("project-b", projectB.toString())),
            ),
        )
        val screenshotRoute = routing.routes().values.single { it.idePid == 42L }
        val inputRoute = routing.routes().values.single { it.idePid == 43L }
        routing.rememberScreenshotExecution("eid_other_ide", screenshotRoute)
        val handler = DevrigVisionInputToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.handleInputSequence(
            projectName = inputRoute.exposedProjectName,
            inputParams = InputParams(
                taskId = "input-task",
                reason = "press key",
                screenshotExecutionId = "eid_other_ide",
                sequence = emptyList(),
                rawSequence = "press:ENTER",
            ),
        )

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("belongs to another IDE"))
        assertTrue(result.errorText().contains("call steroid_take_screenshot again"))
        assertEquals(null, receivedAuth)
        assertEquals(null, receivedBody)
    }

    @Test
    fun `open project bridge handler rejects zero discovered ides`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val routing = routingService()
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = tempDir.resolve("target").toString(),
                trustProject = true,
            )
        )

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("requires exactly one discovered IDE"))
        assertEquals(null, receivedAuth)
        assertEquals(null, receivedBody)
    }

    @Test
    fun `open project bridge handler rejects multiple discovered ides`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val firstHome = Files.createDirectories(tempDir.resolve("first"))
        val secondHome = Files.createDirectories(tempDir.resolve("second"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = firstHome),
                status = IdeMonitorStatus.CONNECTED,
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, projectHome = secondHome),
                status = IdeMonitorStatus.CONNECTED,
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = tempDir.resolve("target").toString(),
                trustProject = true,
            )
        )

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("requires exactly one discovered IDE"))
        assertEquals(null, receivedAuth)
        assertEquals(null, receivedBody)
    }

    @Test
    fun `open project bridge handler forwards request when exactly one ide is discovered`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
            )
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = targetProject.toString(),
                trustProject = false,
            )
        )

        assertEquals(false, result.isError)
        assertEquals("Bearer secret-token", receivedAuth)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_open_project", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals(targetProject.toString(), arguments["project_path"]?.jsonPrimitive?.content)
        assertEquals("false", arguments["trust_project"]?.jsonPrimitive?.content)
        assertEquals("open-project", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("Open project through devrig", arguments["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute code bridge handler forwards timeout dialog killer and progress events`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        beforeResultEvents += """{"type":"heartbeat","message":"ignored"}""" + "\n"
        beforeResultEvents += """{"type":"future","message":"ignored"}""" + "\n"
        beforeResultEvents += """{"type":"progress","message":"compile started"}""" + "\n"
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val progressMessages = mutableListOf<String>()
        val handler = DevrigExecuteCodeToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.executeCode(
            projectName = route.exposedProjectName,
            execCodeParams = ExecCodeParams(
                taskId = "exec-task",
                code = "println(1)",
                reason = "verify execute-code argument and progress forwarding",
                timeout = 17,
                dialogKiller = true,
            ),
            callProgress = object : McpProgressReporter {
                override fun report(message: String) {
                    progressMessages += message
                }
            },
        )

        assertEquals(false, result.isError)
        assertEquals(listOf("compile started"), progressMessages)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_code", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("exec-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("17", arguments["timeout"]?.jsonPrimitive?.content)
        assertEquals("true", arguments["dialog_killer"]?.jsonPrimitive?.content)
    }

    @Test
    fun `bridge client propagates cancellation while waiting for NDJSON result`(
        @TempDir tempDir: Path,
    ) {
        runBlocking {
            holdStreamEntered = CompletableDeferred()
            holdStreamRelease = CompletableDeferred()
            val bridge = DevrigToolBridgeClient(
                routing = DevrigProjectRoutingService { emptyMap() },
                httpClient = httpClient,
            )
            val result = async {
                bridge.callTool(
                    route(tempDir),
                    "steroid_execute_code",
                    object : McpProgressReporter {
                        override fun report(message: String) = Unit
                    },
                ) {
                    put("project_name", "original-project")
                }
            }

            withTimeout(5.seconds) {
                holdStreamEntered?.await()
            }
            result.cancel()
            try {
                assertFailsWith<CancellationException> {
                    result.await()
                }
            } finally {
                holdStreamRelease?.complete(Unit)
            }
        }
    }

    @Test
    fun `bridge client returns error result for malformed NDJSON data`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        streamResponse = "{not json}\n"
        val bridge = DevrigToolBridgeClient(
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("Malformed NDJSON data"))
    }

    @Test
    fun `bridge client returns error result for NDJSON error message`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        streamResponse = """{"type":"error","message":"upstream failed"}""" + "\n"
        val bridge = DevrigToolBridgeClient(
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("upstream failed"))
    }

    @Test
    fun `bridge client returns error result for upstream 401`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        httpStatus = HttpStatusCode.Unauthorized
        httpBody = "bad token"
        val bridge = DevrigToolBridgeClient(
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("HTTP 401"))
        assertTrue(result.errorText().contains("/npx/v1/tools/call/stream"))
        assertTrue(result.errorText().contains("bad token"))
    }

    @Test
    fun `bridge client returns error result for upstream 500`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        httpStatus = HttpStatusCode.InternalServerError
        httpBody = "bridge exploded"
        val bridge = DevrigToolBridgeClient(
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("HTTP 500"))
        assertTrue(result.errorText().contains("bridge exploded"))
    }

    @Test
    fun `bridge client returns error result when result message has no result field`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        streamResponse = """{"type":"result"}""" + "\n"
        val bridge = DevrigToolBridgeClient(
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("result message did not include result"))
    }

    @Test
    fun `bridge client returns error result when NDJSON stream closes without result`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        streamResponse = """{"type":"progress","message":"still running"}""" + "\n"
        val bridge = DevrigToolBridgeClient(
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("No result received"))
    }

    @Test
    fun `bridge client ignores heartbeat and unknown NDJSON messages before result`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val resultBody = ToolCallResult(
            content = listOf(ContentItem.Text("ndjson ok")),
            isError = false,
        )
        streamResponse = """{"type":"heartbeat"}""" + "\n" +
                """{"type":"future","message":"ignored"}""" + "\n" +
                """{"type":"result","result": ${McpJson.encodeToJsonElement(ToolCallResult.serializer(), resultBody)}}""" + "\n"
        val bridge = DevrigToolBridgeClient(
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(false, result.isError)
        assertEquals("ndjson ok", (result.content.single() as ContentItem.Text).text)
    }

    private fun routingService(vararg states: IdeMonitorState): DevrigProjectRoutingService =
        DevrigProjectRoutingService { states.associateBy { it.ide.pid } }

    private fun discoveredIde(pid: Long, projectHome: Path): DiscoveredIde =
        DiscoveredIde(
            pid = pid,
            mcpUrl = "http://127.0.0.1:$port/mcp",
            markerPath = projectHome.resolve("$pid.mcp-steroid").toString(),
            marker = PidMarker(
                schema = PidMarker.SCHEMA_VERSION,
                pid = pid,
                mcpSteroidServer = McpSteroidServerInfo(
                    mcpUrl = "http://127.0.0.1:$port/mcp",
                    port = port,
                    headers = mapOf("Authorization" to "Bearer secret-token"),
                ),
                ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
                plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
                createdAt = "2026-05-17T00:00:00Z",
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )

    private fun route(tempDir: Path, token: String = "secret-token"): ProjectRoute =
        ProjectRoute(
            idePid = 42,
            bridgeBaseUrl = "http://127.0.0.1:$port",
            headers = mapOf("Authorization" to "Bearer $token"),
            originalProjectName = "original-project",
            exposedProjectName = "original-project-abcdefgh",
            projectPath = tempDir.toString(),
            realProjectHome = tempDir.toRealPath(),
            hash8 = "abcdefgh",
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        )
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun ToolCallResult.errorText(): String =
    (content.single() as ContentItem.Text).text
