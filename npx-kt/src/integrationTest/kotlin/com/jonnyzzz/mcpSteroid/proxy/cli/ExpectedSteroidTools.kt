/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.cli

/**
 * The exact set of tool names that
 * [com.jonnyzzz.mcpSteroid.server.McpSteroidTools.registerAll] is expected to
 * register on the server. Both the protocol-level
 * [CliMcpStdioIntegrationTest] and the agent-level
 * [CliMcpAgentIntegrationTestBase] consult this list, so a regression that
 * drops or renames a tool is caught from both directions instead of one.
 */
internal val EXPECTED_STEROID_TOOL_NAMES: Set<String> = setOf(
    "steroid_list_projects",
    "steroid_list_windows",
    "steroid_execute_code",
    "steroid_apply_patch",
    "steroid_execute_feedback",
    "steroid_action_discovery",
    "steroid_take_screenshot",
    "steroid_input",
    "steroid_open_project",
    "steroid_fetch_resource",
)
