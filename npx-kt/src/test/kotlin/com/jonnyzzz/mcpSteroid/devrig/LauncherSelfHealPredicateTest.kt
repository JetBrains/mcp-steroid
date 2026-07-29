/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins [selfHealsLauncherOnStart] and [printsHeadliner] across every [DevrigCommand] variant that
 * exists today. None of them are the MCP-as-CLI tool facade yet (that variant, `RunTool`, lands in a
 * later task) — so every variant self-heals, preserving today's unconditional `ensureBinLauncher(...)`
 * call in [mainImpl2]. When `RunTool` is added, the compiler forces `isMcpAsCliToolCommand` to classify
 * it (an exhaustive `when` with no `else`), and `every existing DevrigCommand variant self-heals the
 * launcher on start` will start failing for that one new variant until the exclusion is added
 * deliberately.
 *
 * [printsHeadliner] is covered here too (not with a `debug`/`json`-toggling helper that changes
 * nothing, the way an earlier version of this test toggled both flags against a predicate that reads
 * neither — a tautology that cannot fail): unlike [selfHealsLauncherOnStart], it actually reads `json`,
 * so the tests below assert the value flips with it.
 */
class LauncherSelfHealPredicateTest {

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
    )

    private val neverHeadlinedCommands: List<DevrigCommand> = listOf(
        DevrigCommand.MCP(),
        DevrigCommand.DevrigCommandHelp(),
        DevrigCommand.DevrigCommandVersion(),
        DevrigCommand.DevrigCommandParseError(text = "bad args"),
    )

    @Test
    fun `every existing DevrigCommand variant self-heals the launcher on start`() {
        for (command in everyCommand) {
            assertTrue(command.selfHealsLauncherOnStart(), "expected $command to self-heal the launcher")
        }
    }

    @Test
    fun `tool-running non-MCP commands print the headliner in console mode but not under --json`() {
        for (command in toolRunningNonMcpCommands) {
            assertTrue(withJson(command, json = false).printsHeadliner(), "expected $command to print the headliner")
            assertTrue(!withJson(command, json = true).printsHeadliner(), "expected $command with --json to suppress the headliner")
        }
    }

    @Test
    fun `MCP, help, version and parse-error commands never print the headliner, json or not`() {
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
        is DevrigCommand.DevrigCommandHelp -> command.copy(json = json)
        is DevrigCommand.DevrigCommandVersion -> command.copy(json = json)
        is DevrigCommand.DevrigCommandParseError -> command.copy(json = json)
    }
}
