package com.jonnyzzz.mcpSteroid.server

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
