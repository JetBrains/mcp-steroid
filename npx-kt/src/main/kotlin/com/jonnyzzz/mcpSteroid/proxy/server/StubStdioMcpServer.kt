/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpStdioServer
import com.jonnyzzz.mcpSteroid.mcp.PromptsCapability
import com.jonnyzzz.mcpSteroid.mcp.ResourcesCapability
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.ToolsCapability
import com.jonnyzzz.mcpSteroid.proxy.loadProxyVersion
import java.io.InputStream
import java.io.OutputStream

/**
 * Boot a real MCP stdio server inside the npx-kt CLI process.
 *
 * Wiring:
 *  - [McpServerCore] declares server identity + advertised capabilities.
 *  - [StubMcpSteroidTools.registerAll] registers every steroid_* tool spec onto
 *    the core. The handlers themselves are not yet implemented in npx-kt — see
 *    [StubMcpSteroidTools] — so calling a tool returns an error, but
 *    `tools/list` / `prompts/list` / `resources/list` describe the full surface.
 *  - [McpStdioServer] runs the transport on the supplied [input] / [output]
 *    streams (NDJSON or framed, auto-detected from the first inbound frame).
 *
 * The caller MUST pass the *original* `System.in` / `System.out` references —
 * not the JVM globals after [main] has already routed `System.out` to stderr.
 * Suspends until [input] reaches EOF.
 */
internal suspend fun runStubStdioMcpServer(
    input: InputStream,
    output: OutputStream,
) {
    val server = McpServerCore(
        serverInfo = ServerInfo(
            name = "mcp-steroid-proxy",
            version = loadProxyVersion(),
        ),
        capabilities = ServerCapabilities(
            tools = ToolsCapability(listChanged = true),
            prompts = PromptsCapability(listChanged = true),
            resources = ResourcesCapability(subscribe = false, listChanged = true),
        ),
    )

    StubMcpSteroidTools().registerAll(server)

    McpStdioServer(server, input = input, output = output).run()
}
