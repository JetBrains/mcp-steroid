/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.json.*

/**
 * Simple tool that fetches any `mcp-steroid://` resource by URI and returns its markdown content.
 * Agents can call this instead of ReadMcpResourceTool — it's a purpose-built MCP tool
 * visible in the tool list, making resource discovery more natural.
 */
class FetchResourceToolHandler : McpRegistrar {

    private val log = thisLogger()

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_fetch_resource",
            description = "Fetch an MCP Steroid guide/recipe by URI. Returns markdown with copy-paste code patterns. " +
                    "Read skill guides before starting work: " +
                    "mcp-steroid://prompt/test-skill (tests), " +
                    "mcp-steroid://prompt/debugger-skill (debug), " +
                    "mcp-steroid://prompt/skill (power user), " +
                    "mcp-steroid://skill/coding-with-intellij (full guide).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "The mcp-steroid:// URI to fetch")
                    }
                }
                putJsonArray("required") { add("uri") }
            },
        ) {
            val uri = it.params.arguments?.get("uri")?.jsonPrimitive?.content
                ?: return@registerTool ToolCallResult(
                    content = listOf(ContentItem.Text(text = "ERROR: Missing required parameter: uri")),
                    isError = true
                )

            log.info("steroid_fetch_resource: $uri")

            val result = server.resourceRegistry.readResource(uri)
                ?: return@registerTool ToolCallResult(
                    content = listOf(ContentItem.Text(text = "ERROR: Resource not found: $uri")),
                    isError = true
                )

            val text = result.contents.firstOrNull()?.text
                ?: return@registerTool ToolCallResult(
                    content = listOf(ContentItem.Text(text = "ERROR: Resource empty: $uri")),
                    isError = true
                )

            ToolCallResult(content = listOf(ContentItem.Text(text = text)))
        }
    }
}
