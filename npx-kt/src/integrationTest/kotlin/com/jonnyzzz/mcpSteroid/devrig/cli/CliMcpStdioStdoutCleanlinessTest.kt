/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.devrig.DEVRIG_HOME_ENV
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.DevrigMcpInstaller
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.StdoutCleanlinessHarness
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Locale

/**
 * Asserts that the devrig launcher writes ONLY MCP NDJSON frames to its stdout.
 * Any banner from the shell launcher script, any logger output that leaked past
 * the stderr-only logback config, any `println` that bypassed the
 * `System.setOut(System.err)` redirect in `main()` would corrupt the JSON-RPC
 * stream for a real client. This test pins all three failure modes.
 *
 * Two variants:
 *  - **Host** — runs whatever launcher script matches the test JVM's OS:
 *    `bin/devrig` on POSIX, `bin/devrig.bat` on Windows.
 *    Whichever OS the test runs on (Mac dev box, Linux CI, Windows TC agent)
 *    is the OS covered. Future-proof: `.bat` path is wired, no Windows runner
 *    today.
 *  - **Docker** — re-runs the launcher inside the dedicated `mcp-cli`
 *    container (a Debian + JRE image defined alongside the agent containers
 *    under `test-helper/src/main/docker/mcp-cli/Dockerfile`). Drives Linux
 *    coverage even when the host is Mac/Windows.
 *
 * Both variants are fire-and-forget (one fixed handshake → wait for exit →
 * inspect stdout) so they go through the project's
 * [com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunner] —
 * [RunProcessRequest.startProcess] for the host, `startProcessInContainer` for
 * Docker — instead of a raw [ProcessBuilder].
 */
@Suppress("FunctionName")
class CliMcpStdioStdoutCleanlinessTest {

    private val installDir: File by lazy { DevrigMcpInstaller.resolveInstallDir() }

    private val lifetime = CloseableStackHost()

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    fun `host launcher writes only JSON-RPC frames to stdout`() {
        val (variantLabel, command) = hostMpcCommand()

        // RunProcessRequest's stdin overloads take ByteArray/String directly
        // and wrap them as a single-element Flow internally.
        val result = RunProcessRequest()
            .command(command)
            .stdin(StdoutCleanlinessHarness.handshakeBytes)
            .logPrefix("STDOUT-CLEAN-host")
            .description("devrig stdout cleanliness check (host)")
            .timeoutSeconds(30)
            .quietly()
            .startProcess()
            .awaitForProcessFinish()

        check(result.exitCode == 0) {
            "[$variantLabel] launcher exited with ${result.exitCode}\n" +
                    "stdout=\n${result.stdout}\nstderr=\n${result.stderr}"
        }

        StdoutCleanlinessHarness.assertStdoutClean(
            stdout = result.stdout,
            variantLabel = variantLabel,
            stderrForDiagnostics = result.stderr,
        )
    }

    @Test
    fun `host startup failure before handshake is visible on stderr`(@TempDir tempDir: Path) {
        val (_, command) = hostMpcCommand()
        val missingHome = tempDir.resolve("missing-devrig-home")

        val result = RunProcessRequest()
            .command(command)
            .stdin(StdoutCleanlinessHarness.handshakeBytes)
            .environment(mapOf(DEVRIG_HOME_ENV to missingHome.toString()))
            .logPrefix("STDOUT-CLEAN-host")
            .description("devrig startup failure before MCP handshake")
            .timeoutSeconds(30)
            .quietly()
            .startProcess()
            .awaitForProcessFinish()

        check(result.exitCode == 64) {
            "launcher exited with ${result.exitCode}\nstdout=\n${result.stdout}\nstderr=\n${result.stderr}"
        }
        check(result.stdout.isBlank()) {
            "startup failure must not emit partial MCP frames on stdout; got:\n${result.stdout}"
        }
        check(result.stderr.contains("Startup failure:")) {
            "startup failure must be visible on stderr; got:\n${result.stderr}"
        }
        check(result.stderr.contains(DEVRIG_HOME_ENV) && result.stderr.contains("cannot resolve canonical path")) {
            "stderr must identify the invalid home override; got:\n${result.stderr}"
        }
    }

    private fun hostMpcCommand(): Pair<String, List<String>> {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        val isWindows = "windows" in osName
        val launcherName = if (isWindows) "devrig.bat" else "devrig"
        val launcher = File(installDir, "bin/$launcherName")
        check(launcher.isFile) { "launcher missing at ${launcher.absolutePath}" }

        // `mpc` is the launcher's opt-in subcommand for stdio MCP mode (see
        // `com.jonnyzzz.mcpSteroid.devrig.DevrigArgs.command`). Without it the launcher behaves
        // like a normal CLI (`--help`) and prints help text to stdout — which would make
        // this very test fail for the wrong reason.
        val command = if (isWindows) {
            // .bat must be invoked through cmd.exe; the application plugin's
            // generated script handles arg quoting on its own.
            listOf("cmd.exe", "/c", launcher.absolutePath, "mpc")
        } else {
            check(launcher.canExecute()) { "launcher not executable: ${launcher.absolutePath}" }
            listOf(launcher.absolutePath, "mpc")
        }
        return "host:${if (isWindows) "windows" else osName}" to command
    }

    @Test
    fun `linux launcher inside docker writes only JSON-RPC frames to stdout`() {
        val dockerfile = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-helper/src/main/docker/mcp-cli/Dockerfile")
            .toFile()
        check(dockerfile.isFile) { "mcp-cli Dockerfile missing at $dockerfile" }

        val image = buildDockerImage(
            logPrefix = "MCP-CLI",
            dockerfilePath = dockerfile,
            timeoutSeconds = 600,
        )
        val container = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest().image(image),
        )

        // `docker cp <localDir> <id>:/tmp` puts the directory at /tmp/<basename>.
        // installDir.name is "devrig" — the application plugin's
        // applicationName.
        container.copyToContainer(installDir, "/tmp")
        val containerLauncher = "/tmp/${installDir.name}/bin/devrig"

        val result = container.startProcessInContainer {
            this
                .args(containerLauncher, "mpc")
                .interactive()
                .stdin(flowOf(StdoutCleanlinessHarness.handshakeBytes))
                .description("devrig stdout cleanliness check")
                .timeoutSeconds(60)
                .quietly()
        }.awaitForProcessFinish()

        check(result.exitCode == 0) {
            "[docker:linux] launcher exited with ${result.exitCode}\n" +
                    "stdout=\n${result.stdout}\nstderr=\n${result.stderr}"
        }

        StdoutCleanlinessHarness.assertStdoutClean(
            stdout = result.stdout,
            variantLabel = "docker:linux",
            stderrForDiagnostics = result.stderr,
        )
    }
}
