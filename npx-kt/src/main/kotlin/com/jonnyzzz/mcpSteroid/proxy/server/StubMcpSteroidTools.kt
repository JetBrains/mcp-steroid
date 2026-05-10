/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.server

import com.jonnyzzz.mcpSteroid.server.McpSteroidTools

/**
 * Mock [McpSteroidTools] implementation used while the npx-kt stdio MCP server is
 * being built out. [registerAll] still wires every steroid_* tool spec into the
 * server, so `tools/list`, `prompts/list`, and `resources/list` work end-to-end.
 * Any actual `tools/call` invocation, however, hits [handler] and throws —
 * intentionally — so a regression that depends on a real handler surfaces as a
 * loud `isError=true` result instead of silently falling through.
 *
 * NOTE: we throw [UnsupportedOperationException] rather than calling [TODO].
 * `TODO()` raises [NotImplementedError], which extends `Error` (not
 * `Exception`); both `McpToolRegistry.callTool` and the stdio dispatch loop
 * catch only `Exception`, so a `NotImplementedError` would tear down the entire
 * stdio server instead of becoming a proper MCP `ToolCallResult(isError=true)`.
 */
internal class StubMcpSteroidTools : McpSteroidTools() {
    override fun <T> handler(type: Class<T>): T =
        throw UnsupportedOperationException(
            "not yet ready: handler<${type.name}>() is not wired in npx-kt yet"
        )
}
