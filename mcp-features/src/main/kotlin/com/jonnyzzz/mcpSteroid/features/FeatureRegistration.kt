/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.features

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

/**
 * Register all features from the given context with the MCP server.
 * This is the single entry point that wires up all tools, resources, and prompts.
 *
 * @param server The MCP server core to register tools/resources/prompts with
 * @param context The feature context providing handler implementations
 * @param toolOverrides Optional overrides for tool definitions (e.g., dynamic descriptions)
 */
fun registerFeatures(
    server: McpServerCore,
    context: McpFeatureContext,
    toolOverrides: Map<String, McpToolDefinitions.() -> com.jonnyzzz.mcpSteroid.features.ToolDef> = emptyMap(),
) {
    fun toolDef(def: ToolDef): ToolDef {
        val override = toolOverrides[def.name]
        return if (override != null) McpToolDefinitions.override() else def
    }

    val listProjects = toolDef(McpToolDefinitions.LIST_PROJECTS)
    server.toolRegistry.registerTool(
        name = listProjects.name,
        description = listProjects.description,
        inputSchema = listProjects.inputSchema,
        handler = { context.listProjects(it) }
    )

    val listWindows = toolDef(McpToolDefinitions.LIST_WINDOWS)
    server.toolRegistry.registerTool(
        name = listWindows.name,
        description = listWindows.description,
        inputSchema = listWindows.inputSchema,
        handler = { context.listWindows(it) }
    )

    val executeCode = toolDef(McpToolDefinitions.EXECUTE_CODE)
    server.toolRegistry.registerTool(
        name = executeCode.name,
        description = executeCode.description,
        inputSchema = executeCode.inputSchema,
        handler = { context.executeCode(it) }
    )

    val actionDiscovery = toolDef(McpToolDefinitions.ACTION_DISCOVERY)
    server.toolRegistry.registerTool(
        name = actionDiscovery.name,
        description = actionDiscovery.description,
        inputSchema = actionDiscovery.inputSchema,
        handler = { context.discoverActions(it) }
    )

    val visionScreenshot = toolDef(McpToolDefinitions.VISION_SCREENSHOT)
    server.toolRegistry.registerTool(
        name = visionScreenshot.name,
        description = visionScreenshot.description,
        inputSchema = visionScreenshot.inputSchema,
        handler = { context.takeScreenshot(it) }
    )

    val visionInput = toolDef(McpToolDefinitions.VISION_INPUT)
    server.toolRegistry.registerTool(
        name = visionInput.name,
        description = visionInput.description,
        inputSchema = visionInput.inputSchema,
        handler = { context.sendInput(it) }
    )

    val openProject = toolDef(McpToolDefinitions.OPEN_PROJECT)
    server.toolRegistry.registerTool(
        name = openProject.name,
        description = openProject.description,
        inputSchema = openProject.inputSchema,
        handler = { context.openProject(it) }
    )

    val executeFeedback = toolDef(McpToolDefinitions.EXECUTE_FEEDBACK)
    server.toolRegistry.registerTool(
        name = executeFeedback.name,
        description = executeFeedback.description,
        inputSchema = executeFeedback.inputSchema,
        handler = { context.executeFeedback(it) }
    )

    // Let context register additional resources and prompts
    context.registerResources(server.resourceRegistry)
    context.registerPrompts(server.promptRegistry)
}
