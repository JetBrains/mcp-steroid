/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedWindow
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

/**
 * `devrig take_screenshot` and `devrig open_project` end to end: the generated command reaches the real
 * specs, which build [ScreenshotParams] / [OpenProjectParams] from the parsed arguments and — for
 * `take_screenshot` — whose image result flows through `--out` ([renderWithOut], unit-tested directly and
 * exhaustively in `CliToolSupportTest`; this file proves the WIRING, not the decode/write logic itself).
 * `open_project`'s generic `--wait` poll mechanics (deadline, interval, ready predicate) are proven with a
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

    private class SequencedListWindows(private val responses: List<ListWindowsResponse>) : ListWindowsToolHandler {
        var calls: Int = 0

        override suspend fun collectListWindowsResponse(): ListWindowsResponse {
            val response = responses[calls.coerceAtMost(responses.size - 1)]
            calls += 1
            return response
        }
    }

    private fun window(path: String, initialized: Boolean) = ListedWindow(
        projectName = "k", projectPath = path, title = null, isActive = false, isVisible = true,
        bounds = null, windowId = "w", modalDialogShowing = false, indexingInProgress = false,
        projectInitialized = initialized,
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
        // No ListWindowsToolHandler double is registered: without --wait the runtime must never reach for
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
        assertEquals(true, params.trustProject, "trust_project must default to true when omitted")
    }

    @Test
    fun `open_project --wait polls list_windows until the project reports ready, then exits OK`() {
        val open = RecordingOpenProject(ToolCallResult(content = listOf(ContentItem.Text("opening"))))
        val realPath = home.toRealPath().toString()
        val windows = SequencedListWindows(
            listOf(
                ListWindowsResponse(windows = listOf(window(realPath, initialized = false)), backgroundTasks = emptyList()),
                ListWindowsResponse(windows = listOf(window(realPath, initialized = true)), backgroundTasks = emptyList()),
            ),
        )
        val tools = FakeMcpSteroidTools()
            .with(OpenProjectToolHandler::class.java, open)
            .with(ListWindowsToolHandler::class.java, windows)
        val command = parseRunTool(
            "open_project", "--json",
            "--project_path=$home", "--task_id=t", "--reason=r", "--wait",
        )

        val run = runGeneratedToolForTest(home, command, tools)

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertTrue(windows.calls >= 2, "must poll again after a not-ready response (polled ${windows.calls} times)")
    }
}
