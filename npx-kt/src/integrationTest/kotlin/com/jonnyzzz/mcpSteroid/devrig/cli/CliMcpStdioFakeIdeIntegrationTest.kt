/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.devrig.DEVRIG_HOME_ENV
import com.jonnyzzz.mcpSteroid.server.NPX_NDJSON_MIME_TYPE
import com.jonnyzzz.mcpSteroid.server.NPX_PROJECTS_STREAM_PATH
import com.jonnyzzz.mcpSteroid.server.NpxBridgeToolCallRequest
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.NpxStreamEnvelope
import com.jonnyzzz.mcpSteroid.server.NpxStreamJson
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.WindowInfo
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.StdioMcpProcess
import com.jonnyzzz.mcpSteroid.testHelper.startStdioMcpProcess
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CliMcpStdioFakeIdeIntegrationTest {
    private val lifetime = CloseableStackHost()
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var process: StdioMcpProcess
    private val pid = ProcessHandle.current().pid()
    private val seq = AtomicLong(0)
    private var port: Int = 0
    private val bridgeToolCallCount = AtomicLong(0)
    private var receivedToolCall: NpxBridgeToolCallRequest? = null
    private var receivedProjectsStreamAuth: String? = null
    private var receivedWindowsAuth: String? = null
    private var receivedToolAuth: String? = null
    private lateinit var projectHome: Path

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        port = freePort()
        val devrigHome = Files.createDirectories(tempDir.resolve("devrig-home"))
        val fakeUserHome = Files.createDirectories(tempDir.resolve("user-home"))
        projectHome = Files.createDirectories(tempDir.resolve("sample-project"))
        startFakeIdeBridge(projectHome)
        writeMarker(fakeUserHome)

        val launcherPath = System.getProperty("devrig.launcher")
            ?: error("System property 'devrig.launcher' is not set. Run via `./gradlew :npx-kt:integrationTest`.")
        val launcher = File(launcherPath)
        check(launcher.canExecute()) { "Launcher is not executable: $launcherPath" }
        process = startStdioMcpProcess(
            launcher = launcher,
            lifetime = lifetime,
            // HOME redirects the subprocess JVM's user.home so the universal
            // marker location ~/.mcp-steroid/markers points at a temp dir
            // instead of polluting the developer's real home.
            environment = mapOf(
                DEVRIG_HOME_ENV to devrigHome.toString(),
                "HOME" to fakeUserHome.toString(),
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
        server.stop(0L, 0L)
    }

    @Test
    fun stdioMpcDiscoversOneIdeAndRoutesToolCallThroughDevrigBridge() {
        process.initialize()

        val projectName = waitForProjectName()
        assertEquals(true, projectName.startsWith("sample-"), "project name should include hash suffix: $projectName")
        assertNotEquals("sample", projectName)

        // Since S6 (commit 919e1e03) devrig no longer exposes the MCP prompts/resources
        // surfaces — the corpus is reached via the steroid_fetch_resource TOOL instead
        // (covered by FetchResourceToolTest). Both list endpoints must still answer
        // without error, but empty, and crucially must be served LOCALLY by devrig — never
        // routed to the IDE bridge.
        val resources = process.request("resources/list", buildJsonObject {})
        assertEquals(null, resources["error"], "resources/list returned error: $resources")
        assertEquals(
            0,
            resources["result"]?.jsonObject?.get("resources")?.jsonArray?.size,
            "devrig must expose no MCP resources post-S6: $resources",
        )

        val prompts = process.request("prompts/list", buildJsonObject {})
        assertEquals(null, prompts["error"], "prompts/list returned error: $prompts")
        assertEquals(
            0,
            prompts["result"]?.jsonObject?.get("prompts")?.jsonArray?.size,
            "devrig must expose no MCP prompts post-S6: $prompts",
        )

        assertEquals(0L, bridgeToolCallCount.get(), "Local MCP methods should not route tool calls to the bridge")

        val result = toolCall("steroid_execute_code", buildJsonObject {
            put("project_name", projectName)
            put("task_id", "fake-ide")
            put("reason", "verify bridge routing")
            put("code", "println(1)")
        })
        assertEquals(false, result["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertEquals("Bearer fake-token", receivedProjectsStreamAuth)
        assertEquals("Bearer fake-token", receivedToolAuth)
        assertEquals("steroid_execute_code", receivedToolCall?.name)
        assertEquals(
            "sample",
            receivedToolCall?.arguments?.get("project_name")?.jsonPrimitive?.content,
        )
        assertEquals(1L, bridgeToolCallCount.get(), "steroid_execute_code should have routed to the bridge")

        val windowsResult = toolCall("steroid_list_windows", buildJsonObject {})
        assertEquals("Bearer fake-token", receivedWindowsAuth)
        val windowsText = windowsResult["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: error("list_windows result missing text content: $windowsResult")
        val windows = McpJson.parseToJsonElement(windowsText).jsonObject["windows"]?.jsonArray
            ?: error("list_windows result missing windows: $windowsText")
        val window = windows.single().jsonObject
        assertEquals(projectName, window["projectName"]?.jsonPrimitive?.content)
        assertEquals(
            true,
            window["windowId"]?.jsonPrimitive?.content?.startsWith("fake-window-"),
            "window id should include routing suffix: $window",
        )
    }

    private fun waitForProjectName(): String {
        // ~30s budget (120 * 250ms). devrig's marker discovery scans every 2s and the
        // devrig JVM-25 subprocess startup + first bridge snapshot can intermittently
        // exceed a tight 10s window on a loaded CI agent (flaky timeout, passes on rerun).
        repeat(120) {
            val result = toolCall("steroid_list_projects", buildJsonObject {})
            val text = result["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: error("list_projects result missing text content: $result")
            val list = McpJson.parseToJsonElement(text).jsonObject
            val projects = list["projects"]?.jsonArray ?: error("missing projects: $list")
            if (projects.isNotEmpty()) {
                return projects.single().jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("project missing name: ${projects.single()}")
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for fake IDE project to appear in steroid_list_projects")
    }

    private fun toolCall(name: String, arguments: JsonObject): JsonObject {
        val response = process.request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeoutMillis = 20_000,
        )
        assertEquals(null, response["error"], "tools/call returned error: $response")
        return response["result"]?.jsonObject ?: error("tools/call response missing result: $response")
    }

    private fun startFakeIdeBridge(projectHome: Path) {
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post(NPX_PROJECTS_STREAM_PATH) {
                    receivedProjectsStreamAuth = call.request.headers["Authorization"]
                    call.receiveText()
                    call.respondTextWriter(ContentType.parse(NPX_NDJSON_MIME_TYPE)) {
                        write(NpxStreamJson.encodeEnvelope(snapshot(projectHome)))
                        write("\n")
                        flush()
                        while (currentCoroutineContext().isActive) {
                            delay(50.milliseconds)
                        }
                    }
                }
                get("/npx/v1/windows") {
                    receivedWindowsAuth = call.request.headers["Authorization"]
                    call.respondText(
                        text = McpJson.encodeToString(
                            NpxBridgeWindowsResponse.serializer(),
                            NpxBridgeWindowsResponse(
                                windows = listOf(
                                    WindowInfo(
                                        projectName = "sample",
                                        projectPath = projectHome.toString(),
                                        title = "Fake IDE",
                                        isActive = true,
                                        isVisible = true,
                                        bounds = null,
                                        windowId = "fake-window",
                                    )
                                ),
                                backgroundTasks = emptyList(),
                                pid = pid,
                                mcpUrl = "http://127.0.0.1:$port/mcp",
                                instanceId = "fake-instance",
                                seq = seq.incrementAndGet(),
                                schemaVersion = "1",
                                updatedAt = Instant.now().toString(),
                            )
                        ),
                        contentType = ContentType.Application.Json,
                    )
                }
                post("/npx/v1/tools/call/stream") {
                    bridgeToolCallCount.incrementAndGet()
                    receivedToolAuth = call.request.headers["Authorization"]
                    receivedToolCall = McpJson.decodeFromString(NpxBridgeToolCallRequest.serializer(), call.receiveText())
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        val result = ToolCallResult(listOf(ContentItem.Text("routed-ok")))
                        write("event: result\n")
                        write(
                            "data: " + buildJsonObject {
                                put("type", "result")
                                put("result", McpJson.encodeToJsonElement(ToolCallResult.serializer(), result))
                            } + "\n\n"
                        )
                        flush()
                    }
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }
    }

    private fun snapshot(projectHome: Path): NpxStreamEnvelope =
        NpxStreamEnvelope(
            type = "snapshot",
            instanceId = "fake-instance",
            seq = seq.incrementAndGet(),
            sentAt = Instant.now().toString(),
            pid = pid,
            projects = listOf(ProjectInfo("sample", projectHome.toString())),
        )

    private fun writeMarker(userHome: Path) {
        val markersDir = Files.createDirectories(PidMarker.markerDirectory(userHome))
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://127.0.0.1:$port/mcp",
                port = port,
                headers = mapOf("Authorization" to "Bearer fake-token"),
            ),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
            createdAt = Instant.now().toString(),
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        Files.writeString(markersDir.resolve(PidMarker.markerFileNameFor(pid)), PidMarkerJson.encode(marker))
    }
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
