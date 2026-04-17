/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.DebuggerSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.TestSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJPromptArticle
import kotlinx.serialization.json.*

/**
 * Simple tool that fetches any MCP Steroid resource by URI and returns its markdown content.
 * Agents can call this instead of ReadMcpResourceTool — it's a purpose-built MCP tool
 * visible in the tool list, making resource discovery more natural.
 */
class FetchResourceToolHandler : McpRegistrar {

    private val log = thisLogger()

    override fun register(server: McpServerCore) {
        val testSkillUri = TestSkillPromptArticle().uri
        val debuggerUri = DebuggerSkillPromptArticle().uri
        val skillUri = SkillPromptArticle().uri
        val codingGuideUri = CodingWithIntelliJPromptArticle().uri

        server.toolRegistry.registerTool(
            name = "steroid_fetch_resource",
            description = "Fetch an MCP Steroid guide/recipe by URI. Returns markdown with copy-paste code patterns. " +
                    "Read skill guides before starting work: " +
                    "$testSkillUri (tests), " +
                    "$debuggerUri (debug), " +
                    "$skillUri (power user), " +
                    "$codingGuideUri (full guide).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "The resource URI to fetch (from ListMcpResourcesTool or MCP server instructions)")
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
