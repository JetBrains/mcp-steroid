/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpStdioServer
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.ToolsCapability
import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.devrig.DevrigVersionMetadata

/**
 * Boot a real MCP stdio server inside the devrig CLI process.
 *
 * Wiring:
 *  - [McpServerCore] declares server identity and advertised capabilities.
 *  - [StubMcpSteroidTools.registerAll] registers every steroid_* tool spec onto
 *    the core. The handlers themselves are not yet implemented in devrig — see
 *    [StubMcpSteroidTools] — so calling a tool returns an error, but
 *    `tools/list` / `prompts/list` / `resources/list` describe the full surface.
 *  - [McpStdioServer] runs the transport on [DevrigServices.mcpStdin] /
 *    [DevrigServices.mcpStdout] (NDJSON or framed, auto-detected from the first
 *    inbound frame).
 *
 * [DevrigServices] owns the original `System.in` / `System.out` references —
 * not the JVM globals after main has already routed `System.out` to stderr.
 * Suspends until [DevrigServices.mcpStdin] reaches EOF.
 */
suspend fun runStubStdioMcpServer(services: DevrigServices) {
    val server = McpServerCore(
        serverInfo = ServerInfo(
            name = "devrig",
            version = DevrigVersionMetadata.getDevrigVersion(),
        ),
        capabilities = ServerCapabilities(
            tools = ToolsCapability(listChanged = true),
        ),
    )

    val tools = StubMcpSteroidTools(services)
    tools.registerAll(server)

    McpStdioServer(server, input = services.mcpStdin, output = services.mcpStdout).run()
}
