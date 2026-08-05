/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins [selfHealsLauncherOnStart] and [printsHeadliner] across every [DevrigCommand] variant.
 * [DevrigCommand.RunTool] — the generated MCP-as-CLI tool facade — is the one variant that must NOT
 * self-heal the launcher and must never print the headliner: it is a thin, stateless bridge forwarder
 * whose stdout has to stay pipeable (Tenet 3). Every other variant self-heals, preserving the behavior
 * of the unconditional `ensureBinLauncher(...)` call [mainImpl2] used to make.
 *
 * [printsHeadliner] is covered here too (not with a `debug`/`json`-toggling helper that changes
 * nothing, the way an earlier version of this test toggled both flags against a predicate that reads
 * neither — a tautology that cannot fail): unlike [selfHealsLauncherOnStart], it actually reads `json`,
 * so the tests below assert the value flips with it.
 */
class LauncherSelfHealPredicateTest {

    private val runTool = DevrigCommand.RunTool(toolName = "steroid_list_windows", commandName = "list_windows")

    private val everyCommand: List<DevrigCommand> = listOf(
        DevrigCommand.MCP(),
        DevrigCommand.DevrigCommandBackend(),
        DevrigCommand.DevrigCommandBackendDownload(),
        DevrigCommand.DevrigCommandBackendStart(),
        DevrigCommand.DevrigCommandBackendStop(),
        DevrigCommand.DevrigCommandBackendProvision(),
        DevrigCommand.DevrigCommandProject(),
        DevrigCommand.DevrigCommandInstall(agent = AiAgentCli.CLAUDE),
        DevrigCommand.DevrigCommandInstallDevrig(),
        DevrigCommand.DevrigCommandInstallOverview(),
        DevrigCommand.DevrigCommandInstallConfig(),
        DevrigCommand.DevrigCommandInstallPlugin(),
        DevrigCommand.DevrigCommandHelp(),
        DevrigCommand.DevrigCommandVersion(),
        DevrigCommand.DevrigCommandParseError(text = "bad args"),
    )

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
        runTool,
    )

    @Test
    fun `every DevrigCommand variant except the generated tool facade self-heals the launcher on start`() {
        for (command in everyCommand) {
            assertTrue(command.selfHealsLauncherOnStart(), "expected $command to self-heal the launcher")
        }
    }

    @Test
    fun `the generated tool facade never self-heals the launcher`() {
        assertTrue(!runTool.selfHealsLauncherOnStart(), "a stateless tool facade must not mutate launcher state")
    }

    @Test
    fun `tool-running non-MCP commands print the headliner in console mode but not under --json`() {
        for (command in toolRunningNonMcpCommands) {
            assertTrue(withJson(command, json = false).printsHeadliner(), "expected $command to print the headliner")
            assertTrue(!withJson(command, json = true).printsHeadliner(), "expected $command with --json to suppress the headliner")
        }
    }

    @Test
    fun `MCP, help, version, parse-error and generated tool commands never print the headliner, json or not`() {
        for (command in neverHeadlinedCommands) {
            assertTrue(!withJson(command, json = false).printsHeadliner(), "expected $command to never print the headliner")
            assertTrue(!withJson(command, json = true).printsHeadliner(), "expected $command to never print the headliner")
        }
    }

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
        is DevrigCommand.DevrigCommandInstallOverview -> command.copy(json = json)
        is DevrigCommand.DevrigCommandInstallConfig -> command.copy(json = json)
        is DevrigCommand.DevrigCommandInstallPlugin -> command.copy(json = json)
        is DevrigCommand.DevrigCommandHelp -> command.copy(json = json)
        is DevrigCommand.DevrigCommandVersion -> command.copy(json = json)
        is DevrigCommand.DevrigCommandParseError -> command.copy(json = json)
        is DevrigCommand.RunTool -> command.copy(json = json)
    }
}
