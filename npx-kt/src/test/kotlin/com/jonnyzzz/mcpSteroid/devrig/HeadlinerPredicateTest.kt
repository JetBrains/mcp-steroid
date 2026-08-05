/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins [printsHeadliner] across every [DevrigCommand] variant, with `json` toggled both ways: the banner
 * is a human-console affordance, and a stray banner line ahead of a `--json` document breaks every
 * consumer that parses stdout.
 */
class HeadlinerPredicateTest {

    private val toolRunningNonMcpCommands: List<DevrigCommand> = listOf(
        DevrigCommand.DevrigCommandBackend(),
        DevrigCommand.DevrigCommandBackendDownload(),
        DevrigCommand.DevrigCommandBackendStart(),
        DevrigCommand.DevrigCommandBackendStop(),
        DevrigCommand.DevrigCommandBackendProvision(),
        DevrigCommand.DevrigCommandProject(),
        DevrigCommand.DevrigCommandInstall(agent = AiAgentCli.CLAUDE),
        DevrigCommand.DevrigCommandInstallDevrig(),
        DevrigCommand.DevrigCommandInstallPlugin(),
    )

    private val neverHeadlinedCommands: List<DevrigCommand> = listOf(
        DevrigCommand.MCP(),
        DevrigCommand.DevrigCommandInstallOverview(),
        DevrigCommand.DevrigCommandInstallConfig(),
        DevrigCommand.DevrigCommandHelp(),
        DevrigCommand.DevrigCommandVersion(),
        DevrigCommand.DevrigCommandParseError(text = "bad args"),
        // A generated MCP-as-CLI tool facade emits data to stdout that must stay clean for piping, so it
        // never prints the banner — console or --json alike (isMcpAsCliToolCommand suppresses it).
        DevrigCommand.RunTool(toolName = "steroid_list_windows", commandName = "list_windows"),
    )

    @Test
    fun `tool-running non-MCP commands print the headliner in console mode but not under --json`() {
        for (command in toolRunningNonMcpCommands) {
            assertTrue(withJson(command, json = false).printsHeadliner(), "expected $command to print the headliner")
            assertTrue(!withJson(command, json = true).printsHeadliner(), "expected $command with --json to suppress the headliner")
        }
    }

    @Test
    fun `MCP, informational install and help-like commands never print the headliner, json or not`() {
        for (command in neverHeadlinedCommands) {
            assertTrue(!withJson(command, json = false).printsHeadliner(), "expected $command to never print the headliner")
            assertTrue(!withJson(command, json = true).printsHeadliner(), "expected $command to never print the headliner")
        }
    }

    /**
     * Exhaustive so the compiler forces every new [DevrigCommand] variant into one of the two lists above —
     * a variant added without classification would otherwise silently escape this test.
     */
    private fun withJson(command: DevrigCommand, json: Boolean): DevrigCommand = when (command) {
        is DevrigCommand.MCP -> command.copy(json = json)
        is DevrigCommand.DevrigCommandBackend -> command.copy(json = json)
        is DevrigCommand.DevrigCommandBackendDownload -> command.copy(json = json)
        is DevrigCommand.DevrigCommandBackendStart -> command.copy(json = json)
        is DevrigCommand.DevrigCommandBackendStop -> command.copy(json = json)
        is DevrigCommand.DevrigCommandBackendProvision -> command.copy(json = json)
        is DevrigCommand.DevrigCommandProject -> command.copy(json = json)
        is DevrigCommand.DevrigCommandInstall -> command.copy(json = json)
        is DevrigCommand.DevrigCommandInstallDevrig -> command.copy(json = json)
        is DevrigCommand.DevrigCommandInstallPlugin -> command.copy(json = json)
        is DevrigCommand.DevrigCommandInstallOverview -> command.copy(json = json)
        is DevrigCommand.DevrigCommandInstallConfig -> command.copy(json = json)
        is DevrigCommand.DevrigCommandHelp -> command.copy(json = json)
        is DevrigCommand.DevrigCommandVersion -> command.copy(json = json)
        is DevrigCommand.DevrigCommandParseError -> command.copy(json = json)
        is DevrigCommand.RunTool -> command.copy(json = json)
    }
}
