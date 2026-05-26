package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

/**
 * MCP Steroid's own Ktor-hosted MCP server — the one this plugin writes the
 * marker for. Sibling of [IntelliJMcpServerInfo] (the IDE-bundled MCP plugin)
 * and [IntelliJWebServerInfo] (IntelliJ's built-in web server).
 *
 * [port] mirrors the TCP port already embedded in [mcpUrl], surfaced as a
 * typed field so consumers don't need to parse the URL. [headers] carries
 * the HTTP headers a client MUST send on every request to this server —
 * notably `Authorization: Bearer <token>`. Storing them as a typed map keeps
 * the three server-info classes shaped consistently and frees consumers from
 * understanding the auth scheme.
 */
@Serializable
data class McpSteroidServerInfo(
    val mcpUrl: String,
    val port: Int,
    val headers: Map<String, String>,
)
