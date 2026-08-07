/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import java.io.PrintStream
import kotlinx.serialization.SerializationException

/** Renders the list-projects MCP payload for a human while JSON callers keep the shared CLI envelope. */
fun renderListProjectsTable(result: ToolCallResult, out: PrintStream, err: PrintStream): Int {
    val content = result.content.singleOrNull() as? ContentItem.Text
    if (content == null) {
        err.println("ERROR: list_projects returned ${result.content.size} content items instead of one text payload")
        return CliExit.DATA_ERROR
    }
    val response = try {
        McpJson.decodeFromString(ListProjectsResponse.serializer(), content.text)
    } catch (e: SerializationException) {
        val detail = e.message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "malformed JSON" }
        err.println("ERROR: list_projects returned invalid data: $detail")
        return CliExit.DATA_ERROR
    }
    renderListProjectsTable(response, out)
    return CliExit.OK
}

/** Pure table renderer shared by canonical `list_projects` and its `projects`/`project` aliases. */
fun renderListProjectsTable(response: ListProjectsResponse, out: PrintStream) {
    val projects = response.projects.sortedBy { it.projectName }
    if (projects.isEmpty()) {
        out.println("No open projects.")
        out.println("Run `devrig backend` to inspect available IDE backends.")
        out.println()
        return
    }

    out.println("Listing ${projects.size} open project(s) across ${response.backends.size} backend(s):")
    out.println()
    val backends = response.backends.associateBy { it.backendName }
    val padWidth = projects.maxOf { it.projectName.codePointWidth() }.coerceAtMost(40)
    for ((index, project) in projects.withIndex()) {
        out.println("  [${index + 1}] ${project.projectName.padEndCodePoints(padWidth)}  →  ${project.path}")
        out.println("        Raw project name: ${project.name}")
        val backendName = project.backendName
        val intellij = backendName?.let(backends::get)?.intellij
        if (backendName != null && intellij != null) {
            val displayName = if (intellij.version in intellij.name) intellij.name else "${intellij.name} ${intellij.version}"
            out.println("        $displayName ($backendName; build ${intellij.build})")
        } else {
            out.println("        Backend: ${backendName ?: "unknown"}")
        }
        if (index < projects.lastIndex) out.println()
    }
    out.println()
}
