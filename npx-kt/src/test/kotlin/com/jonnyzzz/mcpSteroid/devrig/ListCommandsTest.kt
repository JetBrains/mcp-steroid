/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two listers, end to end: parsed by the generated command, dispatched by the runtime, rendered once.
 * They are the first tools to go all the way through, so this is where the generated-command lifecycle is
 * pinned as a whole — `devrig list_projects` / `devrig list_windows` must call the tool and render its
 * result, not merely parse.
 *
 * `list_projects` and `list_windows` declare no parameter at all, which is exactly why they are the pair
 * this task takes end to end: nothing about them can be got right by accident in a per-tool branch, so
 * whatever makes them work is the generic pipeline.
 */
class ListCommandsTest {

    @TempDir
    lateinit var home: Path

    private class FakeListProjects(private val response: ListProjectsResponse) : ListProjectsToolHandler {
        override suspend fun collectListProjectsResponse(): ListProjectsResponse = response
    }

    private class FakeListWindows(private val response: ListWindowsResponse) : ListWindowsToolHandler {
        override suspend fun collectListWindowsResponse(): ListWindowsResponse = response
    }

    private fun toolsWithProjects(response: ListProjectsResponse) =
        FakeMcpSteroidTools().with(ListProjectsToolHandler::class.java, FakeListProjects(response))

    private fun toolsWithWindows(response: ListWindowsResponse) =
        FakeMcpSteroidTools().with(ListWindowsToolHandler::class.java, FakeListWindows(response))

    private val oneProject = ListProjectsResponse(
        projects = listOf(
            ListedProject(
                projectName = "demo-abc123",
                name = "demo",
                path = "/work/demo",
                backendName = "IU-253",
            ),
        ),
    )

    // ------------------------- the generated command reaches the tool -------------------------

    @Test
    fun `list_projects --json renders the tool result in the frozen envelope`() {
        val run = runGeneratedToolForTest(home, parseRunTool("list_projects", "--json"), toolsWithProjects(oneProject))

        assertEquals(CliExit.OK, run.exit, "a successful lister must exit 0; stdout was:\n${run.stdout}")
        val envelope = run.envelope()
        assertEquals("devrig", envelope.getValue("tool").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("list_projects", envelope.getValue("command").jsonPrimitive.content)
        assertEquals(false, envelope.getValue("isError").jsonPrimitive.content.toBoolean())
        val content = envelope.getValue("data").jsonObject.getValue("content").jsonArray
        assertEquals(1, content.size, "a lister returns exactly one text content item")
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            McpJson.encodeToString(ListProjectsResponse.serializer(), oneProject),
            content[0].jsonObject.getValue("text").jsonPrimitive.content,
            "the tool's own response must reach the envelope verbatim",
        )
    }

    @Test
    fun `list_projects without --json prints the tool result on stdout`() {
        val run = runGeneratedToolForTest(home, parseRunTool("list_projects"), toolsWithProjects(oneProject))

        assertEquals(CliExit.OK, run.exit)
        assertEquals(
            McpJson.encodeToString(ListProjectsResponse.serializer(), oneProject) + "\n",
            run.stdout,
            "console mode prints the tool's text content and nothing else",
        )
    }

    @Test
    fun `list_windows --json renders the tool result in the frozen envelope`() {
        val response = ListWindowsResponse(windows = emptyList(), backgroundTasks = emptyList())
        val run = runGeneratedToolForTest(home, parseRunTool("list_windows", "--json"), toolsWithWindows(response))

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        val envelope = run.envelope()
        assertEquals("list_windows", envelope.getValue("command").jsonPrimitive.content)
        val content = envelope.getValue("data").jsonObject.getValue("content").jsonArray
        assertEquals(
            McpJson.encodeToString(ListWindowsResponse.serializer(), response),
            content.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `list_windows without --json prints the tool result on stdout`() {
        val response = ListWindowsResponse(windows = emptyList(), backgroundTasks = emptyList())
        val run = runGeneratedToolForTest(home, parseRunTool("list_windows"), toolsWithWindows(response))

        assertEquals(CliExit.OK, run.exit)
        assertEquals(McpJson.encodeToString(ListWindowsResponse.serializer(), response) + "\n", run.stdout)
    }

    @Test
    fun `a lister emits exactly one JSON document on stdout`() {
        val run = runGeneratedToolForTest(home, parseRunTool("list_projects", "--json"), toolsWithProjects(oneProject))

        // The frozen contract is ONE document: a second one (a banner line, a raw result printed beside
        // the envelope) would break every `devrig ... --json | jq` caller. Parsing the WHOLE of stdout is
        // the assertion — kotlinx.serialization rejects trailing content after the first document — plus
        // the outer braces, so a leading banner cannot hide in front of it.
        val trimmed = run.stdout.trimEnd()
        assertTrue(trimmed.startsWith("{"), "stdout must start with the envelope; got:\n${run.stdout}")
        assertTrue(trimmed.endsWith("}"), "stdout must end with the envelope; got:\n${run.stdout}")
        assertTrue(run.stdout.endsWith("\n"), "stdout must be line-terminated; got '${run.stdout.takeLast(20)}'")
        run.envelope()
    }

    // ------------------------- through the production runCli router -------------------------

    /** The single text payload the `--json` envelope carries. */
    private fun GeneratedToolRun.payloadText(): String =
        envelope().getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("text").jsonPrimitive.content

    @Test
    fun `runCli dispatches a generated list_projects command instead of failing`() {
        // The whole production path: parse → runCli's RunTool arm → the real StubMcpSteroidTools wiring →
        // render. Deliberately asserts the SHAPE and not the contents: IDE discovery reads the real
        // `~/.mcp-steroid` markers (HomePaths.markersDir is anchored at the user home by design, so a
        // scratch home cannot isolate it), so this machine's open IDEs may legitimately appear. What is
        // machine-independent is that the tool ran and its own response reached the envelope — which is
        // exactly the claim: the arm dispatches rather than throwing.
        val run = runCliForToolTest(home, parseRunTool("list_projects", "--json"))

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("list_projects", run.envelope().getValue("command").jsonPrimitive.content)
        McpJson.decodeFromString(ListProjectsResponse.serializer(), run.payloadText())
    }

    @Test
    fun `runCli dispatches a generated list_windows command instead of failing`() {
        val run = runCliForToolTest(home, parseRunTool("list_windows", "--json"))

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("list_windows", run.envelope().getValue("command").jsonPrimitive.content)
        McpJson.decodeFromString(ListWindowsResponse.serializer(), run.payloadText())
    }
}
