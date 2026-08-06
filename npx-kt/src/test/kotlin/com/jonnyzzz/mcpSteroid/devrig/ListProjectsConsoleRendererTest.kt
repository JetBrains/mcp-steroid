/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.IntelliJInfo
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListedProject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Human-output coverage migrated from the former handwritten `project` command to `list_projects`. */
class ListProjectsConsoleRendererTest {
    private fun render(response: ListProjectsResponse = ListProjectsResponse(emptyList())): String {
        val buffer = ByteArrayOutputStream()
        renderListProjectsTable(response, PrintStream(buffer, true, Charsets.UTF_8))
        return buffer.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun project(
        projectName: String,
        name: String = projectName,
        path: String = "/projects/$projectName",
        backendName: String? = "iu-main",
    ): ListedProject = ListedProject(projectName, name, path, backendName)

    private fun backend(
        backendName: String = "iu-main",
        name: String = "IntelliJ IDEA",
        version: String = "2026.1",
        build: String = "IU-261.1",
    ): BackendRef = BackendRef(backendName, IntelliJInfo(name, version, build))

    @Test
    fun `projects are sorted by routing key and arrows align`() {
        val text = render(
            ListProjectsResponse(
                projects = listOf(project("zulu-long"), project("alpha")),
                backends = listOf(backend()),
            )
        )

        val alpha = text.indexOf("[1] alpha")
        val zulu = text.indexOf("[2] zulu-long")
        assertTrue(alpha in 0 until zulu, text)
        val arrowColumns = text.lines().filter { '→' in it }.map { it.indexOf('→') }
        assertEquals(1, arrowColumns.toSet().size, text)
    }

    @Test
    fun `empty output explains the next backend discovery action`() {
        val text = render()

        assertTrue(text.startsWith("No open projects.\n"), text)
        assertTrue("devrig backend" in text, text)
        assertTrue(text.endsWith("\n\n"), text)
    }

    @Test
    fun `project row distinguishes routing key raw name path and backend identity`() {
        val text = render(
            ListProjectsResponse(
                projects = listOf(project("my-app-route", name = "my-app", path = "/work/my-app")),
                backends = listOf(backend()),
            )
        )

        assertTrue("Listing 1 open project(s) across 1 backend(s):" in text, text)
        assertTrue("my-app-route" in text && "/work/my-app" in text, text)
        assertTrue("Raw project name: my-app" in text, text)
        assertTrue("IntelliJ IDEA 2026.1 (iu-main; build IU-261.1)" in text, text)
    }

    @Test
    fun `unknown backend remains visible instead of dropping the project`() {
        val text = render(ListProjectsResponse(projects = listOf(project("orphan", backendName = null))))

        assertTrue("[1] orphan" in text, text)
        assertTrue("Backend: unknown" in text, text)
    }

    @Test
    fun `invalid MCP payload is a data error without a stacktrace`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exit = renderListProjectsTable(
            ToolCallResult(content = listOf(ContentItem.Text("not-json"))),
            PrintStream(stdout, true, Charsets.UTF_8),
            PrintStream(stderr, true, Charsets.UTF_8),
        )
        val outText = stdout.toString(Charsets.UTF_8).replace("\r\n", "\n")
        val errText = stderr.toString(Charsets.UTF_8).replace("\r\n", "\n")

        assertEquals(CliExit.DATA_ERROR, exit, errText)
        assertEquals("", outText)
        assertTrue(errText.startsWith("ERROR: list_projects returned invalid data:"), errText)
        assertTrue("Exception" !in errText && "\tat " !in errText, errText)
    }
}
