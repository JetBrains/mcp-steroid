/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import java.io.File

/** Resolves the devrig distribution directory from the Gradle-provided launcher path. */
object DevrigMcpInstaller {

    fun resolveInstallDir(): File {
        val launcherProperty = System.getProperty("devrig.launcher")
            ?: error(
                "System property 'devrig.launcher' is not set. " +
                        "Run via `./gradlew :npx-kt:integrationTest`."
            )
        // Walk up from `<dist>/bin/devrig` to the distribution root.
        return File(launcherProperty).parentFile.parentFile
    }
}

/** Copies a devrig distribution tree into [container] and returns its stdio MCP command. */
internal fun ContainerDriver.installDevrigMcp(installDir: File): StdioMcpCommand {
    require(installDir.isDirectory) { "devrig installDir does not exist: $installDir" }
    // `docker cp <localDir> <id>:/tmp` puts the directory at /tmp/<basename>.
    // installDir.name is "devrig", the application plugin's applicationName.
    copyToContainer(installDir, "/tmp")
    val launcher = "/tmp/${installDir.name}/bin/devrig"
    // `mpc` is the opt-in subcommand for the stdio MCP server. Without it, the launcher
    // behaves like a normal CLI (`--help`, `--version`) and ignores MCP frames on stdin.
    return StdioMcpCommand(
        command = launcher,
        args = listOf("mpc"),
    )
}
