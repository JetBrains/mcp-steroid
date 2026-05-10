/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger


/**
 * Registry for MCP prompts.
 */
class McpPromptRegistry : McpPromptRegistrar {
    private val log = thisLogger()
    private val prompts = mutableMapOf<String, McpPromptDefinition>()

    /**
     * Register a prompt with its renderer.
     */
    override fun registerPrompt(
        prompt: Prompt,
        renderer: (PromptGetParams) -> PromptGetResult,
    ) {
        prompts[prompt.name] = McpPromptDefinition(prompt, renderer)
        log.info("Registered MCP prompt: ${prompt.name}")
    }

    /**
     * Get all registered prompts.
     */
    fun listPrompts(): List<Prompt> = prompts.values.map { it.prompt }

    /**
     * Render a prompt by name.
     */
    fun getPrompt(params: PromptGetParams): PromptGetResult? {
        val definition = prompts[params.name] ?: return null
        return definition.renderer(params)
    }
}

private data class McpPromptDefinition(
    val prompt: Prompt,
    val renderer: (PromptGetParams) -> PromptGetResult,
)
