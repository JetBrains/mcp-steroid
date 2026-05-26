/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.IntelliJMcpServerInfo

/**
 * Real probe implementation — directly references the bundled IntelliJ
 * MCP server plugin's API (`com.intellij.mcpserver.impl.McpServerService`).
 *
 * **This class is registered as an application service only via
 * `META-INF/mcpServer-integration.xml`**, which is processed by the
 * IDE only when the optional dependency on `com.intellij.mcpServer` is
 * satisfied. That means the JVM only loads (and link-resolves) this
 * class — and the `McpServerService` reference it carries — when the
 * MCP server plugin is actually present. No reflection, no runtime
 * gate, no `NoClassDefFoundError` window.
 */
internal class IntelliJMcpServerProbeImpl : IntelliJMcpServerProbe {
    private val log = thisLogger()

    override fun probe(): IntelliJMcpServerInfo? = try {
        val service = McpServerService.getInstance()
        if (!service.isRunning) {
            null
        } else {
            val sseUrl = service.serverSseUrl
            // The 253 bundle exposes `serverSseUrl` but not `serverStreamUrl`
            // (added later). Same listener, sibling path, so we derive the
            // streamable HTTP URL from the SSE URL. Newer IDEs will return
            // the same string this derivation produces.
            val derivedStreamUrl = sseUrl.removeSuffix("/sse") + "/stream"
            IntelliJMcpServerInfo(
                enabled = true,
                port = service.port,
                streamUrl = derivedStreamUrl,
                sseUrl = sseUrl,
                headers = emptyMap(),
            )
        }
    } catch (e: Exception) {
        log.warn("Failed to query bundled IntelliJ MCP Server", e)
        null
    }
}
