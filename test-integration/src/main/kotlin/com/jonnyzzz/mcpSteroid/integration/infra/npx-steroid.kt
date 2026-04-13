/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.prepareNpxProxyFromZipFile

/**
 * Holds the NPX stdio proxy command that AI agents use to connect to MCP Steroid.
 *
 * Deploy via [NpxSteroidDriver.Companion.deploy] after the MCP Steroid server is ready.
 * The deployed proxy forwards stdio JSON-RPC requests to the MCP Steroid HTTP endpoint
 * inside the container, bridging agents that only support stdio MCP transport.
 *
 * The NPX package zip is resolved via Gradle configuration (`:npx` subproject) and
 * passed as the `test.integration.npx.package.zip` system property.
 *
 * Used by [AiAgentDriver] when the container is created with [AiMode.AI_NPX].
 */
class NpxSteroidDriver(
    val npxCommand: StdioMcpCommand,
) {

    companion object {
        /**
         * Deploy the NPX proxy inside [container] and return a ready [NpxSteroidDriver].
         *
         * Extracts the NPX package zip (from [IdeTestFolders.npxPackageZip]) and copies
         * the proxy files into the container. Creates an isolated home directory so that
         * multiple concurrent proxies don't collide. The proxy is pointed at
         * [McpSteroidDriver.guestMcpUrl] so it can reach the MCP Steroid server on
         * the container's loopback interface.
         *
         * Must be called after [McpSteroidDriver.waitForMcpReady].
         */
        fun deploy(
            container: ContainerDriver,
            mcpSteroid: McpSteroidDriver,
        ): NpxSteroidDriver {
            val npxZipFile = IdeTestFolders.npxPackageZip
            val userHome = "/home/npx-${System.currentTimeMillis()}"
            println("[NpxSteroidDriver] Deploying NPX proxy (userHome=$userHome, url=${mcpSteroid.guestMcpUrl}, zip=${npxZipFile.name})...")
            val npxCommand = container.prepareNpxProxyFromZipFile(npxZipFile, mcpSteroid.guestMcpUrl, userHome)
            println("[NpxSteroidDriver] NPX proxy ready")
            return NpxSteroidDriver(npxCommand)
        }
    }
}
