/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.DevrigMcpInstaller
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

@Suppress("FunctionName")
class CliInstallIntegrationTest {
    private val installDir: File by lazy { DevrigMcpInstaller.resolveInstallDir() }
    private val lifetime = CloseableStackHost()

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    fun `install registers claude`() {
        installRegisters(InstallAgentCase("claude", "Claude", "claude-cli"))
    }

    @Test
    fun `install registers codex`() {
        installRegisters(InstallAgentCase("codex", "Codex", "codex-cli"))
    }

    @Test
    fun `install registers gemini`() {
        installRegisters(InstallAgentCase("gemini", "Gemini", "gemini-cli"))
    }

    private fun installRegisters(agent: InstallAgentCase) {
        val dockerfile = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-helper/src/main/docker/${agent.dockerfileBase}/Dockerfile")
            .toFile()
        val image = buildDockerImage(
            logPrefix = "DEVRIG-INSTALL-${agent.cliName.uppercase()}",
            dockerfilePath = dockerfile,
            timeoutSeconds = 600,
            quietly = true,
        )
        val container = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(image)
                .quietly(),
        )
        container.copyToContainer(installDir, "/tmp")

        val result = container.startProcessInContainer {
            this
                .args("/tmp/${installDir.name}/bin/devrig", "install", agent.cliName)
                .description("devrig install ${agent.cliName}")
                .timeoutSeconds(120)
                .quietly()
        }.awaitForProcessFinish().assertExitCode(0, "devrig install ${agent.cliName}")

        val combined = result.stdout + "\n" + result.stderr
        // The install narrates what it did and confirms the registration (idempotent upsert).
        assertTrue(combined.contains("'mcp-steroid' is registered for ${agent.displayName}."), combined)
        assertTrue(combined.contains("JAVA_HOME"), combined)
        assertTrue(combined.contains("/tmp/${installDir.name}/bin/devrig mcp"), combined)
        assertTrue(combined.contains("${agent.cliName} mcp list"), combined)
    }

    private data class InstallAgentCase(
        val cliName: String,
        val displayName: String,
        val dockerfileBase: String,
    ) {
        override fun toString(): String = cliName
    }
}
