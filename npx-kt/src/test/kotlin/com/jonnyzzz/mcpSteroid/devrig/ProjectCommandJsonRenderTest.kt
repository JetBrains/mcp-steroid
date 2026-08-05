/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListedProject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** JSON coverage migrated to the unified generated-command envelope used by both command spellings. */
class ListProjectsJsonRenderTest {
    private fun render(response: ListProjectsResponse = ListProjectsResponse(emptyList())): JsonObject {
        val result = ToolCallResult(
            content = listOf(ContentItem.Text(McpJson.encodeToString(response))),
        )
        val buffer = ByteArrayOutputStream()
        val exit = Presentation.Json().render(result, "list_projects", PrintStream(buffer, true, Charsets.UTF_8))
        assertEquals(CliExit.OK, exit)
        return CLI_ENVELOPE_JSON.parseToJsonElement(buffer.toString(Charsets.UTF_8)).jsonObject
    }

    @Test
    fun `output uses the one generated command envelope`() {
        val root = render()

        assertEquals(setOf("tool", "command", "isError", "data"), root.keys)
        assertEquals("list_projects", root.getValue("command").jsonPrimitive.content)
        assertFalse(root.getValue("isError").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `structured project data is available without parsing an escaped JSON string`() {
        val root = render(
            ListProjectsResponse(
                projects = listOf(ListedProject("alpha-route", "alpha", "/projects/alpha", "iu-main")),
            )
        )
        val project = root.getValue("data").jsonObject
            .getValue("content").jsonArray.single().jsonObject
            .getValue("json").jsonObject
            .getValue("projects").jsonArray.single().jsonObject

        assertEquals("alpha-route", project.getValue("project_name").jsonPrimitive.content)
        assertEquals("alpha", project.getValue("name").jsonPrimitive.content)
        assertEquals("/projects/alpha", project.getValue("path").jsonPrimitive.content)
        assertEquals("iu-main", project.getValue("backend_name").jsonPrimitive.content)
    }
}
