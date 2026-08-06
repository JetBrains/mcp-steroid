/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedProject
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `devrig take_screenshot` and `devrig open_project` end to end: the generated command reaches the real
 * specs, which build [ScreenshotParams] / [OpenProjectParams] from the parsed arguments and — for
 * `take_screenshot` — whose image result flows through `--out` ([renderWithOut], unit-tested directly and
 * exhaustively in `CliToolSupportTest`; this file proves the WIRING, not the decode/write logic itself).
 * `open_project`'s generic `--wait` poll mechanics (deadline, interval, route predicate) are proven with a
 * fake clock in `WaitForProjectReadyTest`; the one polling test here proves the runtime actually invokes
 * that mechanism for a real `--wait` invocation.
 */
class ScreenshotAndOpenProjectCommandTest {

    @TempDir
    lateinit var home: Path

    private class RecordingScreenshot(private val result: ToolCallResult) : VisionScreenshotToolHandler {
        override suspend fun screenshotWindow(
            projectName: String,
            screenshotParams: ScreenshotParams,
            mcpProgressReporter: McpProgressReporter,
        ): ToolCallResult = result
    }

    private class RecordingOpenProject(private val result: ToolCallResult) : OpenProjectToolHandler {
        var params: OpenProjectParams? = null

        override suspend fun handleOpenProject(
            openProjectParams: OpenProjectParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult {
            this.params = openProjectParams
            return result
        }
    }

    private class SequencedListProjects(private val responses: List<ListProjectsResponse>) : ListProjectsToolHandler {
        var calls: Int = 0

        override suspend fun collectListProjectsResponse(): ListProjectsResponse {
            val response = responses[calls.coerceAtMost(responses.size - 1)]
            calls += 1
            return response
        }
    }

    private fun project(path: String) = ListedProject(
        projectName = "opaque-project-key", name = "raw-name", path = path, backendName = "iu-backend",
    )

    // ------------------------------ take_screenshot ------------------------------

    @Test
    fun `--out writes the decoded image end to end via the generated command`() {
        val raw = ByteArray(16) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(raw)
        val tools = FakeMcpSteroidTools().with(
            VisionScreenshotToolHandler::class.java,
            RecordingScreenshot(ToolCallResult(content = listOf(ContentItem.Image(data = b64, mimeType = "image/png")))),
        )
        val outFile = home.resolve("shots/x.png") // parent dir does not exist yet
        val command = parseRunTool(
            "take_screenshot",
            "--project_name=demo", "--task_id=t", "--reason=r", "--out=$outFile",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertTrue(Files.exists(outFile), "parent dirs must be created and the file written")
        assertEquals(raw.toList(), Files.readAllBytes(outFile).toList())
        assertTrue(
            run.stdout.contains("Saved --out:") && run.stdout.contains(outFile.toString()),
            "console mode must report the --out path; stdout was:\n${run.stdout}",
        )
    }

    // ------------------------------ open_project ------------------------------

    @Test
    fun `open_project dispatches to the real spec, normalizing project_path and defaulting trust_project to true`() {
        val open = RecordingOpenProject(ToolCallResult(content = listOf(ContentItem.Text("opening"))))
        val tools = FakeMcpSteroidTools().with(OpenProjectToolHandler::class.java, open)
        // No ListProjectsToolHandler double is registered: without --wait the runtime must never reach for
        // one, so a regression that polls anyway fails loudly ("no test double is registered") instead of
        // silently passing.
        val command = parseRunTool(
            "open_project", "--json",
            "--project_path=$home", "--task_id=t", "--reason=r",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        val params = open.params!!
        assertEquals(home.toRealPath().toString(), params.projectPath, "project_path must be resolved to a real, absolute path")
        assertEquals("t", params.taskId, "task_id must reach the open-project handler unchanged")
        assertEquals("r", params.reason, "reason must reach the open-project handler unchanged")
        assertEquals(true, params.trustProject, "trust_project must default to true when omitted")
    }

    @Test
    fun `open_project --wait polls list_projects and returns the opaque route once it appears`() {
        val open = RecordingOpenProject(ToolCallResult(content = listOf(ContentItem.Text("opening"))))
        val realPath = home.toRealPath().toString()
        val projects = SequencedListProjects(
            listOf(
                ListProjectsResponse(projects = emptyList()),
                ListProjectsResponse(projects = listOf(project(realPath))),
            ),
        )
        val tools = FakeMcpSteroidTools()
            .with(OpenProjectToolHandler::class.java, open)
            .with(ListProjectsToolHandler::class.java, projects)
        val command = parseRunTool(
            "open_project", "--json",
            "--project_path=$home", "--task_id=t", "--reason=r", "--wait",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals(2, projects.calls, "must poll again after the path is absent")
        val payload = run.envelope().getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("json").jsonObject
        assertEquals("opaque-project-key", payload.getValue("project_name").jsonPrimitive.content)
        assertEquals("iu-backend", payload.getValue("backend_name").jsonPrimitive.content)
        assertEquals(realPath, payload.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun `open_project --wait human output identifies the route to use next`() {
        val realPath = home.toRealPath().toString()
        val tools = FakeMcpSteroidTools()
            .with(
                OpenProjectToolHandler::class.java,
                RecordingOpenProject(ToolCallResult(content = listOf(ContentItem.Text("opening")))),
            )
            .with(
                ListProjectsToolHandler::class.java,
                SequencedListProjects(listOf(ListProjectsResponse(projects = listOf(project(realPath))))),
            )
        val command = parseRunTool(
            "open_project", "--project_path=$home", "--task_id=t", "--reason=r", "--wait",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        for (expected in listOf("opaque-project-key", "iu-backend", realPath)) {
            assertTrue(expected in run.stdout, "human output must include '$expected':\n${run.stdout}")
        }
    }
}
