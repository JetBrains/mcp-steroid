/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

/**
 * The exact set of tool names that
 * [com.jonnyzzz.mcpSteroid.server.McpSteroidTools.registerAll] is expected to
 * register on the server. The protocol-level [CliMcpStdioIntegrationTest]
 * asserts `tools/list` advertises exactly these — a regression that drops or
 * renames a tool is caught here, against `devrig mcp` directly, with no live
 * agent. The live-agent × devrig-stdio path (against a real IDE) is covered
 * separately by `:test-integration`'s `DevrigStdioAgentMatrixTest`.
 */
internal val EXPECTED_STEROID_TOOL_NAMES: Set<String> = setOf(
    "steroid_list_projects",
    "steroid_list_windows",
    "steroid_execute_code",
    "steroid_execute_feedback",
    "steroid_take_screenshot",
    "steroid_input",
    "steroid_open_project",
    "steroid_fetch_resource",
)
