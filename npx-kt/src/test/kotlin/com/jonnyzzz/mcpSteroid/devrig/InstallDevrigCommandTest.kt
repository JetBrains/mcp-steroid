/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `devrig install devrig` (issue #398): ONE behavior, same result on every call — register devrig's own
 * launcher + PATH, print ONE info message with the next steps, and NOTHING else. The tests drive the pure
 * [runInstallDevrigCommand] overload whose parameter list is the command's complete side-effect surface,
 * so the recorded call list is an honest guard that no agent config and no IDE is ever touched.
 */
class InstallDevrigCommandTest {

    @Test
    fun `registers the launcher and prints the one next-steps info message`(@TempDir tmp: Path) {
        val r = run(tmp)

        assertEquals(0, r.exitCode)
        assertEquals(listOf("registerLauncher"), r.calls)
        // Expected strings interpolate the SAME Path values the renderer gets: Path.toString() is
        // platform-dependent (backslashes on a Windows JVM), so literals would fail on Windows.
        assertContains(r.stdout, "devrig is installed: ${r.launcher}")
        assertContains(r.stdout, "Next steps:")
        assertContains(r.stdout, "devrig install plugin")
        assertContains(r.stdout, "MCP Steroid plugin")
        assertContains(r.stdout, "devrig install claude")
        assertContains(r.stdout, "devrig install config")
        assertEquals("", r.stderr, "the info message is the ONLY output; got stderr:\n${r.stderr}")
    }

    @Test
    fun `a launcher missing after registration fails with 64 and prints no info message`(@TempDir tmp: Path) {
        val r = run(tmp, launcherAppearsOnRegister = false)

        assertEquals(64, r.exitCode)
        assertEquals("", r.stdout, "no info message when registration failed; got:\n${r.stdout}")
        assertContains(r.stderr, "did not create the launcher")
    }

    @Test
    fun `install devrig never touches agent configs or IDEs - launcher registration is its only side effect`(@TempDir tmp: Path) {
        // The seam below is the command's COMPLETE capability set: the function takes no AiAgentCliRunner
        // and no plugin-install hook, so it has no way to spawn an agent CLI or reach an IDE. Locking the
        // exact call list is the strongest honest guard for the issue #398 contract — 'devrig install
        // devrig' registers devrig itself and nothing else; agent configs are only edited by
        // 'devrig install <agent>', the IDE plugin only by 'devrig install plugin'.
        val r = run(tmp)

        assertEquals(listOf("registerLauncher"), r.calls)
        // The message must PROMOTE the explicit commands instead of running anything itself.
        assertContains(r.stdout, "devrig install plugin")
        assertContains(r.stdout, "devrig install claude")
    }

    @Test
    fun `info message names the plugin, agent, and config commands with the launcher location`() {
        val launcher = Path.of("/home/u/.mcp-steroid/bin/devrig")
        val text = renderInstallDevrigInfo(launcher)

        assertContains(text, "devrig is installed: $launcher")
        assertContains(text, "add ${launcher.parent} to PATH")
        assertContains(text, "devrig install plugin")
        assertContains(text, "install the MCP Steroid plugin into your running JetBrains IDEs")
        // ALL agents listed on the one agent line, none singled out (derived from the enum).
        assertContains(text, "devrig install " + AiAgentCli.entries.joinToString("|") { it.binary })
        assertContains(text, "devrig install config")
    }

    // ── harness ──

    private class Run(
        val exitCode: Int,
        val calls: List<String>,
        val launcher: Path,
        val stdout: String,
        val stderr: String,
    )

    private fun run(tmp: Path, launcherAppearsOnRegister: Boolean = true): Run {
        val launcher = tmp.resolve("bin").resolve("devrig")
        val calls = mutableListOf<String>()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runInstallDevrigCommand(
            out = PrintStream(stdout, true, Charsets.UTF_8),
            err = PrintStream(stderr, true, Charsets.UTF_8),
            launcherPath = launcher,
            registerLauncher = {
                calls += "registerLauncher"
                if (launcherAppearsOnRegister) {
                    Files.createDirectories(launcher.parent)
                    Files.writeString(launcher, "#!/bin/sh\n")
                }
            },
        )
        return Run(
            exitCode = exitCode,
            calls = calls,
            launcher = launcher,
            stdout = stdout.toString(Charsets.UTF_8).replace("\r\n", "\n"),
            stderr = stderr.toString(Charsets.UTF_8).replace("\r\n", "\n"),
        )
    }
}
