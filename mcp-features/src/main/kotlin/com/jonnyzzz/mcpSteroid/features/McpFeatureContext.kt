/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.features

import com.jonnyzzz.mcpSteroid.mcp.*

/**
 * Context interface that consumers implement to provide tool handlers.
 * Each method corresponds to one MCP tool.
 *
 * Implementations are free to use IDE-specific APIs, file I/O,
 * external services, etc. — this interface only declares the contract.
 */
interface McpFeatureContext {
    // --- Tool handlers ---

    /** List open projects with their paths and metadata */
    suspend fun listProjects(context: ToolCallContext): ToolCallResult

    /** List open IDE windows */
    suspend fun listWindows(context: ToolCallContext): ToolCallResult

    /** Execute code snippet in the IDE's scripting environment */
    suspend fun executeCode(context: ToolCallContext): ToolCallResult

    /** Discover available IDE actions matching a query */
    suspend fun discoverActions(context: ToolCallContext): ToolCallResult

    /** Take a screenshot of the IDE window */
    suspend fun takeScreenshot(context: ToolCallContext): ToolCallResult

    /** Send input events to the IDE (keyboard/mouse) */
    suspend fun sendInput(context: ToolCallContext): ToolCallResult

    /** Open a project by path */
    suspend fun openProject(context: ToolCallContext): ToolCallResult

    /** Submit structured feedback about a tool execution */
    suspend fun executeFeedback(context: ToolCallContext): ToolCallResult

    // --- Optional resource/prompt providers ---

    /** Register additional resources beyond the built-in tool set */
    fun registerResources(registry: McpResourceRegistry) {}

    /** Register additional prompts beyond the built-in tool set */
    fun registerPrompts(registry: McpPromptRegistry) {}
}
