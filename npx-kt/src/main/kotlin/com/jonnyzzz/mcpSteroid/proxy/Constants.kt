/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

// JSONRPC_VERSION, MCP_PROTOCOL_VERSION and JsonRpcErrorCodes live in :mcp-core
// (package com.jonnyzzz.mcpSteroid.mcp). Import them directly; do not re-declare.

const val SESSION_HEADER = "Mcp-Session-Id"

const val AGGREGATE_TOOL_PROJECTS = "steroid_list_projects"
const val AGGREGATE_TOOL_WINDOWS = "steroid_list_windows"

object BeaconEvents {
    const val STARTED = "npx_started"
    const val HEARTBEAT = "npx_heartbeat"
    const val DISCOVERY_CHANGED = "npx_discovery_changed"
    const val TOOL_CALL = "npx_tool_call"
    const val UPGRADE_RECOMMENDED = "npx_upgrade_recommended"
}
