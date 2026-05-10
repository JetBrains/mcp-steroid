/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationManager
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

/**
 * Central, explicit registration of every MCP tool, resource, and prompt this plugin exposes.
 *
 * Each tool handler is an [com.jonnyzzz.mcpSteroid.mcp.McpTool] implementation and is
 * registered by passing the instance itself to the narrow
 * [com.jonnyzzz.mcpSteroid.mcp.McpToolRegistrar] interface exposed by the server.
 */
class McpSteroidToolsIJ : McpSteroidTools() {
    override fun <T> handler(type: Class<T>): T = ApplicationManager.getApplication().getService(type)

    override fun registerExtra(server: McpServerCore) {
        super.registerExtra(server)

        val resources = server.resourceRegistry
        val prompts = server.promptRegistry

        //Questionable if we need resources, not MCP
        ResourceRegistrar { handler<PromptsContextHandler>() }.register(resources, prompts)
    }
}

