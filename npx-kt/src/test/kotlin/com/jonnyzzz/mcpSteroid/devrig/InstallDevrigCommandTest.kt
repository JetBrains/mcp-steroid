/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `devrig install devrig` (issue #398): registers devrig itself — the launcher + PATH — and NOTHING
 * else. The tests drive the pure [runInstallDevrigCommand] overload whose parameter list is the
 * command's COMPLETE side-effect surface, so the recorded call list is an honest guard that no agent
 * config is ever touched.
 */
class InstallDevrigCommandTest {

    private val detected = mapOf(
        AiAgentCli.CLAUDE to Path.of("/opt/homebrew/bin/claude"),
        AiAgentCli.CODEX to null,
        AiAgentCli.GEMINI to Path.of("/usr/local/bin/gemini"),
    )

    @Test
    fun `no flags re-registers the launcher from the running install and prints the agent guidance`(@TempDir tmp: Path) {
        val r = run(DevrigCommand.DevrigCommandInstallDevrig(), tmp)

        assertEquals(0, r.exitCode)
        assertEquals(listOf("registerOwnInstall", "offerPluginToRunningIdes"), r.calls)
        assertContains(r.stdout, "devrig is registered")
        assertContains(r.stdout, "PATH setup is best-effort")
        assertContains(r.stdout, "did NOT configure any AI agent")
        // CLI paths are interpolated from the same Path values the fake detector returns — Path.toString()
        // is platform-dependent (backslashes on Windows), so the expected text must be derived, not literal.
        assertContains(r.stdout, "devrig install claude")
        assertContains(r.stdout, "claude CLI found: ${detected[AiAgentCli.CLAUDE]}")
        assertContains(r.stdout, "devrig install codex")
        assertContains(r.stdout, "codex CLI not found on PATH")
        assertContains(r.stdout, "devrig install gemini")
        assertContains(r.stdout, "gemini CLI found: ${detected[AiAgentCli.GEMINI]}")
        assertContains(r.stdout, "devrig install config")
    }

    @Test
    fun `install-script flags keep the explicit install-script registration path`(@TempDir tmp: Path) {
        val r = run(
            DevrigCommand.DevrigCommandInstallDevrig(installScript = "/opt/devrig/bin/devrig", jdkHome = "/opt/jdk"),
            tmp,
        )

        assertEquals(0, r.exitCode)
        assertEquals(listOf("registerInstallScriptLauncher", "offerPluginToRunningIdes"), r.calls)
        assertEquals(
            listOf(Path.of("/opt/devrig/bin/devrig") to Path.of("/opt/jdk")),
            r.installScriptRegistrations,
        )
        // The bootstrap installer's terminal shows this output too — the guidance is for both flows.
        assertContains(r.stdout, "did NOT configure any AI agent")
        assertContains(r.stdout, "devrig install config")
    }

    @Test
    fun `install-script without jdk-home falls back to the JDK devrig runs under`(@TempDir tmp: Path) {
        val r = run(DevrigCommand.DevrigCommandInstallDevrig(installScript = "/opt/devrig/bin/devrig"), tmp)

        assertEquals(0, r.exitCode)
        assertEquals(
            Path.of(System.getProperty("java.home")),
            r.installScriptRegistrations.single().second,
        )
    }

    @Test
    fun `a launcher missing after registration fails with 64 and skips guidance and the plugin offer`(@TempDir tmp: Path) {
        val r = run(DevrigCommand.DevrigCommandInstallDevrig(), tmp, launcherAppearsOnRegister = false)

        assertEquals(64, r.exitCode)
        assertFalse(r.calls.contains("offerPluginToRunningIdes"), r.calls.toString())
        assertEquals("", r.stdout, "no success guidance when registration failed; got:\n${r.stdout}")
        assertContains(r.stderr, "did not create the launcher")
    }

    @Test
    fun `install devrig never touches agent configs - registration plus plugin offer are its only side effects`(@TempDir tmp: Path) {
        // The seams below are the command's COMPLETE capability set: the function takes no
        // AiAgentCliRunner and has no other way to spawn an agent CLI. Locking the exact call list is
        // the strongest honest guard for the issue #398 contract — 'devrig install devrig' registers
        // devrig itself and nothing else; agent configs are only edited by 'devrig install <agent>'.
        for (command in listOf(
            DevrigCommand.DevrigCommandInstallDevrig(),
            DevrigCommand.DevrigCommandInstallDevrig(installScript = "/opt/devrig/bin/devrig", jdkHome = "/opt/jdk"),
        )) {
            val r = run(command, Files.createTempDirectory(tmp, "guard"))
            val registration =
                if (command.installScript == null) "registerOwnInstall" else "registerInstallScriptLauncher"
            assertEquals(listOf(registration, "offerPluginToRunningIdes"), r.calls, "for $command")
            // The output must SAY agents were not configured — the explicit next step, never a silent end.
            assertContains(r.stdout, "did NOT configure any AI agent")
        }
    }

    @Test
    fun `guidance lists one install command per agent with CLI detection and points at install config`() {
        // Expected strings interpolate the SAME Path values the renderer gets: Path.toString() is
        // platform-dependent (backslashes on a Windows JVM), so literals would fail on Windows.
        val launcher = Path.of("/home/u/.mcp-steroid/bin/devrig")
        val text = renderInstallDevrigGuidance(launcher, detected)

        assertContains(text, "devrig is registered: $launcher")
        assertContains(text, "PATH setup is best-effort")
        assertContains(text, "add ${launcher.parent} to PATH")
        assertContains(text, "did NOT configure any AI agent")
        for (agent in AiAgentCli.entries) {
            assertContains(text, "devrig install ${agent.binary}")
        }
        assertContains(text, "claude CLI found: ${detected[AiAgentCli.CLAUDE]}")
        assertContains(text, "codex CLI not found on PATH")
        assertContains(text, "gemini CLI found: ${detected[AiAgentCli.GEMINI]}")
        assertContains(text, "devrig install config")
    }

    // ── harness ──

    private class Run(
        val exitCode: Int,
        val calls: List<String>,
        val installScriptRegistrations: List<Pair<Path, Path>>,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
        command: DevrigCommand.DevrigCommandInstallDevrig,
        tmp: Path,
        launcherAppearsOnRegister: Boolean = true,
    ): Run {
        val launcher = tmp.resolve("bin").resolve("devrig")
        val calls = mutableListOf<String>()
        val installScriptRegistrations = mutableListOf<Pair<Path, Path>>()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        fun writeLauncher() {
            if (!launcherAppearsOnRegister) return
            Files.createDirectories(launcher.parent)
            Files.writeString(launcher, "#!/bin/sh\n")
        }
        val exitCode = runInstallDevrigCommand(
            command = command,
            out = PrintStream(stdout, true, Charsets.UTF_8),
            err = PrintStream(stderr, true, Charsets.UTF_8),
            launcherPath = launcher,
            registerOwnInstall = {
                calls += "registerOwnInstall"
                writeLauncher()
            },
            registerInstallScriptLauncher = { script, jdkHome ->
                calls += "registerInstallScriptLauncher"
                installScriptRegistrations += script to jdkHome
                writeLauncher()
            },
            detectAgentCli = { detected[it] },
            offerPluginToRunningIdes = { calls += "offerPluginToRunningIdes" },
        )
        return Run(
            exitCode = exitCode,
            calls = calls,
            installScriptRegistrations = installScriptRegistrations,
            stdout = stdout.toString(Charsets.UTF_8).replace("\r\n", "\n"),
            stderr = stderr.toString(Charsets.UTF_8).replace("\r\n", "\n"),
        )
    }
}
