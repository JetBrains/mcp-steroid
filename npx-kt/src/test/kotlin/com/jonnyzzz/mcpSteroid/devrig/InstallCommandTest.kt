/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliInvocation
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class InstallCommandTest {
    private val launcher = Path.of("/opt/devrig/bin/devrig")
    private val javaHome = Path.of("/opt/jdk-21")

    @Test
    fun `self mcp command uses current java home on unix`() {
        val command = selfMcpCommand(launcher, javaHome, windows = false)

        assertEquals("/usr/bin/env", command.command)
        assertEquals(listOf("JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mpc"), command.args)
    }

    @Test
    fun `self mcp command uses cmd exe for bat launchers on windows`() {
        val command = selfMcpCommand(Path.of("/opt/devrig/bin/devrig.bat"), Path.of("/opt/jdk-21"), windows = true)

        assertEquals("cmd.exe", command.command)
        assertEquals(
            listOf("/d", "/c", "set \"JAVA_HOME=/opt/jdk-21\" && call \"/opt/devrig/bin/devrig.bat\" mpc"),
            command.args,
        )
    }

    @Test
    fun `install command invokes claude mcp add with self stdio command`() {
        val result = runInstallFor(AiAgentCli.CLAUDE)

        assertEquals(0, result.exitCode)
        assertEquals("claude", result.invocation.binary)
        assertEquals(
            listOf("mcp", "add", "mcp-steroid", "--", "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mpc"),
            result.invocation.args,
        )
        assertTrue(result.stdout.contains("Installed devrig MCP for Claude as mcp-steroid."), result.stdout)
    }

    @Test
    fun `install command invokes codex mcp add with self stdio command`() {
        val result = runInstallFor(AiAgentCli.CODEX)

        assertEquals(0, result.exitCode)
        assertEquals("codex", result.invocation.binary)
        assertEquals(
            listOf("mcp", "add", "mcp-steroid", "--", "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mpc"),
            result.invocation.args,
        )
    }

    @Test
    fun `install command invokes gemini mcp add with self stdio command`() {
        val result = runInstallFor(AiAgentCli.GEMINI)

        assertEquals(0, result.exitCode)
        assertEquals("gemini", result.invocation.binary)
        assertEquals(
            listOf(
                "mcp", "add", "--type", "stdio", "--scope", "user", "--trust", "mcp-steroid",
                "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mpc",
            ),
            result.invocation.args,
        )
    }

    @Test
    fun `install command returns agent cli failure code`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var capturedInvocation: AiAgentCliInvocation? = null
        val exitCode = runInstallCommand(
            command = DevrigCommand.DevrigCommandInstall(AiAgentCli.CLAUDE),
            launcher = launcher,
            javaHome = javaHome,
            out = PrintStream(stdout, true, Charsets.UTF_8),
            err = PrintStream(stderr, true, Charsets.UTF_8),
            runner = { invocation ->
                capturedInvocation = invocation
                AiAgentCliResult(exitCode = 17, output = "agent error")
            },
        )

        assertEquals(17, exitCode)
        assertEquals("claude", capturedInvocation?.binary)
        assertTrue(stdout.toString(Charsets.UTF_8).isBlank(), stdout.toString(Charsets.UTF_8))
        assertTrue(stderr.toString(Charsets.UTF_8).contains("agent error"), stderr.toString(Charsets.UTF_8))
        assertTrue(stderr.toString(Charsets.UTF_8).contains("failed with exit code 17"), stderr.toString(Charsets.UTF_8))
    }

    private fun runInstallFor(agent: AiAgentCli): InstallRunResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        lateinit var capturedInvocation: AiAgentCliInvocation
        val exitCode = runInstallCommand(
            command = DevrigCommand.DevrigCommandInstall(agent),
            launcher = launcher,
            javaHome = javaHome,
            out = PrintStream(stdout, true, Charsets.UTF_8),
            err = PrintStream(stderr, true, Charsets.UTF_8),
            runner = { invocation ->
                capturedInvocation = invocation
                AiAgentCliResult(exitCode = 0, output = "registered\n")
            },
        )
        return InstallRunResult(
            exitCode = exitCode,
            invocation = capturedInvocation,
            stdout = stdout.toString(Charsets.UTF_8),
            stderr = stderr.toString(Charsets.UTF_8),
        )
    }

    private data class InstallRunResult(
        val exitCode: Int,
        val invocation: AiAgentCliInvocation,
        val stdout: String,
        val stderr: String,
    )
}
