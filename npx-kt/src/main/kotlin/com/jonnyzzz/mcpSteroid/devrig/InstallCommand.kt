/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliRunner
import com.jonnyzzz.mcpSteroid.aiAgents.ProcessAiAgentCliRunner
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.mcpAddStdioInvocation
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.isRegularFile

private const val DEVRIG_MCP_SERVER_NAME = "mcp-steroid"

fun DevrigServices.runInstallCommand(
    command: DevrigCommand.DevrigCommandInstall,
    runner: AiAgentCliRunner = ProcessAiAgentCliRunner(),
): Int = runInstallCommand(
    command = command,
    launcher = resolveDevrigLauncher(),
    javaHome = Path.of(System.getProperty("java.home")),
    out = mcpStdout,
    err = System.err,
    runner = runner,
)

fun runInstallCommand(
    command: DevrigCommand.DevrigCommandInstall,
    launcher: Path,
    javaHome: Path,
    out: PrintStream,
    err: PrintStream,
    runner: AiAgentCliRunner,
): Int {
    val mcpCommand = selfMcpCommand(launcher, javaHome)
    val invocation = mcpAddStdioInvocation(command.agent, mcpCommand, DEVRIG_MCP_SERVER_NAME)
    val result = runner.run(invocation)
    if (result.output.isNotBlank()) {
        err.print(result.output)
        if (!result.output.endsWith("\n")) err.println()
    }
    if (result.exitCode != 0) {
        err.println("${command.agent.displayName} MCP registration failed with exit code ${result.exitCode}.")
        return result.exitCode
    }

    out.println("Installed devrig MCP for ${command.agent.displayName} as $DEVRIG_MCP_SERVER_NAME.")
    out.println("JAVA_HOME: $javaHome")
    out.println("Command: ${mcpCommand.command} ${mcpCommand.args.joinToString(" ")}")
    return 0
}

fun selfMcpCommand(
    launcher: Path,
    javaHome: Path,
    windows: Boolean = isWindows(),
): StdioMcpCommand {
    val normalizedLauncher = launcher.toAbsolutePath().normalize()
    val normalizedJavaHome = javaHome.toAbsolutePath().normalize()
    return if (windows) {
        StdioMcpCommand(
            command = "cmd.exe",
            args = listOf("/d", "/c", "set \"JAVA_HOME=$normalizedJavaHome\" && call \"$normalizedLauncher\" mpc"),
        )
    } else {
        StdioMcpCommand(
            command = "/usr/bin/env",
            args = listOf("JAVA_HOME=$normalizedJavaHome", normalizedLauncher.toString(), "mpc"),
        )
    }
}

private fun resolveDevrigLauncher(): Path {
    val name = if (isWindows()) "devrig.bat" else "devrig"
    val expected = DevrigRoot.path.resolve("bin").resolve(name)
    if (expected.isRegularFile()) return expected

    // Fat-jar / non-Gradle-distribution layouts don't have `<root>/bin/devrig`.
    // Fall back to the current process's launcher so `devrig install`
    // still records a reproducible command line.
    val processCommand = ProcessHandle.current().info().command().orElse(null)
    if (processCommand != null) {
        val launcher = Path.of(processCommand)
        if (launcher.isRegularFile()) return launcher
    }

    error("devrig launcher is missing: $expected")
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").lowercase().contains("windows")
