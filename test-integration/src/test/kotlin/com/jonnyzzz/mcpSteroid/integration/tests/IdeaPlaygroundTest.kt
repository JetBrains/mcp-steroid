/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Playground: starts IntelliJ IDEA (the `ide-agent` image) in Docker and keeps it running
 * indefinitely so you can debug interactively — connect via MCP, watch the video stream, and
 * (the point of this one) **attach a JVM debugger** to the in-container IDE.
 *
 * The IDE JVM always starts with the JDWP agent open (`server=y,suspend=n`); the mapped host
 * port is printed below and saved to `session-info.txt` as `IDE_DEBUG_PORT=<host-port>`. Attach
 * IntelliJ's "Remote JVM Debug" to `localhost:<that port>` (module classpath `ij-plugin`) and set
 * breakpoints — e.g. `DialogWindowsLookup.withDialogWindows` (the EDT+ModalityState.any()
 * enumeration that hangs while a modal is up). See test-integration/AGENTS.md →
 * "Remote-debugging the Dockerized IDE" and docs/dialog-killer-modality-hang.md.
 *
 * Reproduce the modal hang by driving MCP (curl / Claude) to open a modal and then run the
 * dialog killer while you step through the EDT dispatch.
 *
 * Stop the test (Ctrl+C / IDE stop) to tear down the container.
 */
class IdeaPlaygroundTest {
    private val lifetime by lazy { CloseableStackHost() }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `idea playground`() {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "IDEA Playground",
        )).waitForProjectReady()

        println()
        println("=".repeat(60))
        println("  IDEA PLAYGROUND READY")
        println("=".repeat(60))
        println("  MCP:        ${session.mcpSteroid.hostMcpUrl}")
        println("  Run dir:    ${session.runDirInContainer}")
        println("  Debug port: see [IDE-DEBUG] line above / session-info.txt IDE_DEBUG_PORT=")
        println("=".repeat(60))
        println("Connect with:")
        println("  claude --mcp-config '{\"mcpServers\":{\"mcp-steroid\":{\"url\":\"${session.mcpSteroid.hostMcpUrl}\"}}}'")
        println("Attach IntelliJ 'Remote JVM Debug' to localhost:<IDE_DEBUG_PORT> (module ij-plugin).")
        println("Waiting indefinitely. Stop the test to tear down.")

        Thread.currentThread().join()
    }
}
