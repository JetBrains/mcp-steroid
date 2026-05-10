/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import java.io.File

/**
 * Resolves the local `installDist` directory produced by `:npx-kt:installDist`
 * — the input that integration tests hand to [AiAgentSession.registerNpxKtMcp]
 * and friends.
 *
 * The Gradle `:npx-kt:integrationTest` task surfaces this path through the
 * `npx.kt.launcher` system property; this helper walks up from the launcher
 * script back to the install root.
 */
object NpxKtMcpInstaller {

    fun resolveInstallDir(): File {
        val launcherProperty = System.getProperty("npx.kt.launcher")
            ?: error(
                "System property 'npx.kt.launcher' is not set. " +
                        "Run via `./gradlew :npx-kt:integrationTest`."
            )
        // Walk up from `<dist>/bin/mcp-steroid-proxy` to the install root.
        return File(launcherProperty).parentFile.parentFile
    }
}

/**
 * Ship the npx-kt `installDist` tree into [container] and return the
 * [StdioMcpCommand] the agent should register as a stdio MCP server.
 *
 * Used by every Docker-backed [AiAgentSession] to implement
 * [AiAgentSession.registerNpxKtMcp] — the implementation pattern is identical
 * across Claude/Codex/Gemini sessions, so it lives here rather than being
 * duplicated three times. Mirrors the JS-proxy installer
 * [com.jonnyzzz.mcpSteroid.testHelper.NpxProxyInstaller], which does the same
 * thing for the Node.js npx package consumed by the IDE-side tests.
 *
 * The agent containers (claude-cli, codex-cli, gemini-cli) install
 * `temurin-21-jre` from the Adoptium APT repo (matching the npx-kt build's
 * `jvmToolchain(21)`), so the launcher script's `java` invocation works out
 * of the box.
 */
internal fun ContainerDriver.installNpxKtMcp(installDir: File): StdioMcpCommand {
    require(installDir.isDirectory) { "npx-kt installDir does not exist: $installDir" }
    // `docker cp <localDir> <id>:/tmp` puts the directory at /tmp/<basename>.
    // installDir.name is "mcp-steroid-proxy" — the application plugin's
    // applicationName, set in npx-kt/build.gradle.kts.
    copyToContainer(installDir, "/tmp")
    val launcher = "/tmp/${installDir.name}/bin/mcp-steroid-proxy"
    return StdioMcpCommand(
        command = launcher,
        args = emptyList(),
    )
}
