/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.DevrigEndpointInfo

/**
 * Test helper: the devrig bridge endpoint the plugin advertises in the pid-marker for a given MCP url.
 * Mirrors what `ServerUrlWriter` writes — the Ktor base (mcpUrl minus `/mcp`) plus the bridge prefix.
 * devrig reads this verbatim; tests use it so stub markers carry a valid [DevrigEndpointInfo].
 */
fun testDevrigEndpoint(mcpUrl: String, headers: Map<String, String> = emptyMap()): DevrigEndpointInfo =
    DevrigEndpointInfo(
        rpcBaseUrl = mcpUrl.trimEnd('/').removeSuffix("/mcp") + "/api/jonnyzzz/mcp-steroid/v1",
        headers = headers,
    )
