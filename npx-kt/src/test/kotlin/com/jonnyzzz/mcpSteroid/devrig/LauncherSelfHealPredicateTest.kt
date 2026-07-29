/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins [selfHealsLauncherOnStart] across every [DevrigCommand] variant that exists today. None of them
 * are the MCP-as-CLI tool facade yet (that variant, `RunTool`, lands in a later task) — so every
 * variant must self-heal, preserving today's unconditional `ensureBinLauncher(...)` call in
 * [mainImpl2]. When `RunTool` is added, the compiler forces `isMcpAsCliToolCommand` to classify it
 * (an exhaustive `when` with no `else`), and this test's `all { it.selfHealsLauncherOnStart() }` will
 * start failing for that one new variant until the exclusion is added deliberately.
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

    @Test
    fun `every existing DevrigCommand variant self-heals the launcher on start`() {
        for (command in everyCommand) {
            assertTrue(command.selfHealsLauncherOnStart(), "expected $command to self-heal the launcher")
        }
    }

    @Test
    fun `the debug and json flags on the command do not change whether it self-heals`() {
        for (base in everyCommand) {
            val debugAndJson = withDebugAndJson(base)
            assertTrue(debugAndJson.selfHealsLauncherOnStart(), "expected $debugAndJson to self-heal the launcher")
        }
    }

    private fun withDebugAndJson(command: DevrigCommand): DevrigCommand = when (command) {
        is DevrigCommand.MCP -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandBackend -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandBackendDownload -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandBackendStart -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandBackendStop -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandBackendProvision -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandProject -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandInstall -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandInstallDevrig -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandHelp -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandVersion -> command.copy(debug = true, json = true)
        is DevrigCommand.DevrigCommandParseError -> command.copy(debug = true, json = true)
    }
}
