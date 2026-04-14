package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Playground test: starts Rider in Docker and keeps it running indefinitely.
 *
 * Use this to manually connect to the IDE via MCP, take screenshots, watch
 * the live video stream, and experiment with API calls interactively.
 *
 * After the test starts, look for the `session-info.txt` file in the run
 * directory or the console output for connection parameters:
 *
 * ```
 * MCP_STEROID=http://localhost:<port>/mcp
 * VIDEO_DASHBOARD=http://localhost:<port>/
 * CONTAINER_ID=<id>
 * ```
 *
 * Connect with Claude Code:
 * ```
 * claude --mcp-config '{"mcpServers":{"mcp-steroid":{"url":"http://localhost:<PORT>/mcp"}}}'
 * ```
 *
 * Stop the test (Ctrl+C or IDE stop button) to tear down the container.
 */
class RiderPlaygroundTest {
    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `rider playground`() {
        val session = IntelliJContainer.create(
            lifetime,
            consoleTitle = "Rider Playground",
            distribution = IdeDistribution.Latest(IdeProduct.Rider),
            aiMode = AiMode.NONE,
        ).waitForProjectReady(projectJdkVersion = null)

        println()
        println("=".repeat(60))
        println("  RIDER PLAYGROUND READY")
        println("=".repeat(60))
        println("  MCP:   ${session.mcpSteroid.hostMcpUrl}")
        println("  Run:   ${session.runDirInContainer}")
        println("=".repeat(60))
        println()
        println("Connect with:")
        println("  claude --mcp-config '{\"mcpServers\":{\"mcp-steroid\":{\"url\":\"${session.mcpSteroid.hostMcpUrl}\"}}}'")
        println()
        println("Waiting indefinitely. Stop the test to tear down.")

        // Block indefinitely — the container stays alive until the test is stopped
        Thread.currentThread().join()
    }
}