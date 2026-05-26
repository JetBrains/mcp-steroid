/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

/** Holds the devrig MCP stdio command used by AI agents in Docker tests. */
class DevrigSteroidDriver(
    val devrigCommand: StdioMcpCommand,
) {

    companion object {
        /** Installs the launcher inside [container] after [McpSteroidDriver.waitForMcpReady]. */
        fun deploy(
            container: ContainerDriver,
            mcpSteroid: McpSteroidDriver,
        ): DevrigSteroidDriver {
            val devrigZipFile = IdeTestFolders.devrigPackageZip
            val launcherPath = "/home/agent/devrig"
            println("[DevrigSteroidDriver] Deploying devrig MCP stdio (mcp=${mcpSteroid.guestMcpUrl}, zip=${devrigZipFile.name})...")
            container.copyToContainer(devrigZipFile, "/tmp/devrig.zip")
            container.startProcessInContainer {
                args(
                    "bash",
                    "-lc",
                    $$"""
                    set -eu
                    rm -rf /home/agent/devrig-cli "$$launcherPath"
                    mkdir -p /home/agent/devrig-cli
                    unzip -q /tmp/devrig.zip -d /home/agent/devrig-cli
                    app_dir="$(find /home/agent/devrig-cli -mindepth 1 -maxdepth 1 -type d -name 'devrig-*' | head -1)"
                    test -n "$app_dir"
                    mv "$app_dir" /home/agent/devrig-cli/app
                    chmod +x /home/agent/devrig-cli/app/bin/devrig
                    cat > "$$launcherPath" <<'EOF'
                    #!/usr/bin/env bash
                    set -eu
                    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-default}"
                    export PATH="$JAVA_HOME/bin:/home/agent/devrig-cli/app/bin:/usr/local/bin:/usr/bin:/bin:${PATH:-}"
                    exec /home/agent/devrig-cli/app/bin/devrig "$@"
                    EOF
                    chmod +x "$$launcherPath"
                    "$$launcherPath" --version
                    """.trimIndent()
                )
                    .timeoutSeconds(120)
                    .description("install devrig MCP stdio launcher")
            }.awaitForProcessFinish()
                .assertExitCode(0) { "install devrig MCP stdio launcher" }

            println("[DevrigSteroidDriver] devrig MCP stdio ready")
            return DevrigSteroidDriver(StdioMcpCommand(command = launcherPath, args = listOf("mpc")))
        }
    }
}
