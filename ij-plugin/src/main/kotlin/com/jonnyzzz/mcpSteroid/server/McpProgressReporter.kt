/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

// Re-export from mcp-core for backward compatibility
typealias McpProgressReporter = com.jonnyzzz.mcpSteroid.mcp.McpProgressReporter

@Suppress("unused")
val NoOpProgressReporter: McpProgressReporter = com.jonnyzzz.mcpSteroid.mcp.NoOpProgressReporter
