/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.JsonObject

/**
 * A single MCP tool, bundling the metadata and the invocation logic. Replaces the
 * previous parameter-list passed to [McpToolRegistrar.registerTool] so each tool is
 * a self-describing object.
 */
interface McpTool {
    val name: String
    val description: String?
    val inputSchema: JsonObject

    suspend fun call(context: ToolCallContext): ToolCallResult
}

abstract class McpToolBase : McpTool {
    private val params = mutableListOf<InputSchemaElement<*>>()

    protected fun <R> InputSchemaElement<R>.registerToSchema() = apply { params.add(this) }

    final override val inputSchema: JsonObject
        get() = InputSchemaElement.buildSchema(params)
}


/**
 * Narrow role interface exposed by [McpToolRegistry] for registering a single MCP tool.
 * Callers pass an [McpTool] instance — name/description/schema/handler live on that object.
 */
fun interface McpToolRegistrar {
    fun registerTool(tool: McpTool)
}

/**
 * Narrow role interface exposed by [McpResourceRegistry] for registering a single MCP resource.
 */
fun interface McpResourceRegistrar {
    fun registerResource(
        uri: String,
        name: String,
        description: String?,
        mimeType: String,
        contentProvider: () -> String,
    )
}

/**
 * Narrow role interface exposed by [McpPromptRegistry] for registering a single MCP prompt.
 */
fun interface McpPromptRegistrar {
    fun registerPrompt(
        prompt: Prompt,
        renderer: (PromptGetParams) -> PromptGetResult,
    )
}

/**
 * Narrow role interface exposed by [McpResourceRegistry] for reading a resource at runtime.
 * Separate from [McpResourceRegistrar] because tools that *read* resources at call time don't
 * need the ability to register new ones.
 */
fun interface McpResourceReader {
    fun readResource(uri: String): ResourceReadResult?
}
