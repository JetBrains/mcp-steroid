/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.IntelliJInfo
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
import kotlinx.serialization.json.JsonObject
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
        backends = listOf(BackendRef("IU-253", IntelliJInfo("IntelliJ IDEA", "2025.3", "IU-253.1"))),
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
        // list_projects' payload is a JSON object, so under --json it is unpacked as `json`, reachable
        // in one parse — never double-encoded as an escaped string under `text` (see contentDataJson()).
        assertTrue("text" !in content[0].jsonObject, "must carry exactly one payload key: ${content[0]}")
        assertEquals(
            McpJson.encodeToJsonElement(ListProjectsResponse.serializer(), oneProject),
            content[0].jsonObject.getValue("json"),
            "the tool's own response must reach the envelope verbatim, unpacked as JSON",
        )
    }

    @Test
    fun `list_projects without --json prints a readable project table`() {
        val run = runGeneratedToolForTest(home, parseRunTool("list_projects"), toolsWithProjects(oneProject))

        assertEquals(CliExit.OK, run.exit)
        assertTrue("Listing 1 open project(s) across 1 backend(s):" in run.stdout, run.stdout)
        assertTrue("demo-abc123" in run.stdout && "/work/demo" in run.stdout, run.stdout)
        assertTrue("Raw project name: demo" in run.stdout, run.stdout)
        assertTrue("IntelliJ IDEA 2025.3 (IU-253; build IU-253.1)" in run.stdout, run.stdout)
    }

    @Test
    fun `project aliases execute the same list_projects handler and render the same outputs`() {
        val canonicalConsole = runGeneratedToolForTest(
            home,
            parseRunTool("list_projects"),
            toolsWithProjects(oneProject),
        )
        val canonicalJson = runGeneratedToolForTest(
            home,
            parseRunTool("list_projects", "--json"),
            toolsWithProjects(oneProject),
        )
        for (aliasName in listOf("projects", "project")) {
            val aliasConsole = runGeneratedToolForTest(home, parseRunTool(aliasName), toolsWithProjects(oneProject))
            assertEquals(canonicalConsole, aliasConsole)
            val aliasJson = runGeneratedToolForTest(
                home,
                parseRunTool(aliasName, "--json"),
                toolsWithProjects(oneProject),
            )
            assertEquals(canonicalJson, aliasJson)
            assertEquals("list_projects", aliasJson.envelope().getValue("command").jsonPrimitive.content)
        }
    }

    @Test
    fun `list_windows --json renders the tool result in the frozen envelope`() {
        val response = ListWindowsResponse(windows = emptyList(), backgroundTasks = emptyList())
        val run = runGeneratedToolForTest(home, parseRunTool("list_windows", "--json"), toolsWithWindows(response))

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        val envelope = run.envelope()
        assertEquals("list_windows", envelope.getValue("command").jsonPrimitive.content)
        val content = envelope.getValue("data").jsonObject.getValue("content").jsonArray
        val item = content.single().jsonObject
        assertTrue("text" !in item, "must carry exactly one payload key: $item")
        assertEquals(
            McpJson.encodeToJsonElement(ListWindowsResponse.serializer(), response),
            item.getValue("json"),
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
        assertEquals("", run.stderr, "--json must not stream progress beside its document")
        assertTrue("Tool call started" !in run.stdout, "progress polluted JSON stdout:\n${run.stdout}")
    }

    // ------------------------- through the production runCli router -------------------------

    /** The single JSON payload the `--json` envelope carries, unpacked (not double-encoded as text). */
    private fun GeneratedToolRun.payloadJson(): JsonObject =
        envelope().getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("json").jsonObject

    @Test
    fun `runCli dispatches a generated list_projects command instead of failing`() {
        // The whole production path: parse → runCli's RunTool arm → the real StubMcpSteroidTools wiring →
        // render. `runCliForToolTest` injects an EMPTY IDE routing table, so the outcome is fixed rather
        // than dependent on which IDEs the developer happens to have open — see its KDoc for why a scratch
        // home does not achieve that on its own.
        val run = runCliForToolTest(home, parseRunTool("list_projects", "--json"))

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("list_projects", run.envelope().getValue("command").jsonPrimitive.content)
        assertEquals(
            ListProjectsResponse(projects = emptyList()),
            McpJson.decodeFromJsonElement(ListProjectsResponse.serializer(), run.payloadJson()),
            "with no route the lister must answer empty, not reach out",
        )
    }

    @Test
    fun `runCli dispatches a generated list_windows command instead of failing`() {
        val run = runCliForToolTest(home, parseRunTool("list_windows", "--json"))

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("list_windows", run.envelope().getValue("command").jsonPrimitive.content)
        assertEquals(
            ListWindowsResponse(windows = emptyList(), backgroundTasks = emptyList()),
            McpJson.decodeFromJsonElement(ListWindowsResponse.serializer(), run.payloadJson()),
            "with no route the lister must answer empty, not reach out",
        )
    }
}
