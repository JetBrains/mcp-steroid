/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

/**
 * IJ-side adapter: resolves tool handlers through the platform's app-level
 * service container. Resource and prompt registrars are wired separately at
 * the [SteroidsMcpServer] callsite so each caller sees the full surface in
 * one place.
 */
@Service(Service.Level.APP)
class McpSteroidToolsIJ : McpSteroidTools() {
    override fun <T> handler(type: Class<T>): T = ApplicationManager.getApplication().getService(type)
}
