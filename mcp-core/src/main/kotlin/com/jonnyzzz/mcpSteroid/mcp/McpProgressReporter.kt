/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

/**
 * Interface for reporting progress during script execution.
 */
interface McpProgressReporter {
    /**
     * Report progress. Implementations may throttle or batch messages.
     */
    fun report(message: String)
}

/**
 * No-op implementation that discards all progress messages.
 */
object NoOpProgressReporter : McpProgressReporter {
    override fun report(message: String) = Unit
}
