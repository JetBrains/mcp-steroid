/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy.cli

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.NpxKtMcpInstaller
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
import java.io.File
import java.util.Locale

/**
 * Asserts that the npx-kt launcher writes ONLY MCP NDJSON frames to its stdout.
 * Any banner from the shell launcher script, any logger output that leaked past
 * the stderr-only logback config, any `println` that bypassed the
 * `System.setOut(System.err)` redirect in `main()` would corrupt the JSON-RPC
 * stream for a real client. This test pins all three failure modes.
 *
 * Two variants:
 *  - **Host** — runs whatever launcher script matches the test JVM's OS:
 *    `bin/mcp-steroid-proxy` on POSIX, `bin/mcp-steroid-proxy.bat` on Windows.
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
class CliMcpStdioStdoutCleanlinessTest {

    private val installDir: File by lazy { NpxKtMcpInstaller.resolveInstallDir() }

    private val lifetime = CloseableStackHost()

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    fun `host launcher writes only JSON-RPC frames to stdout`() {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        val isWindows = "windows" in osName
        val launcherName = if (isWindows) "mcp-steroid-proxy.bat" else "mcp-steroid-proxy"
        val launcher = File(installDir, "bin/$launcherName")
        check(launcher.isFile) { "launcher missing at ${launcher.absolutePath}" }

        val command = if (isWindows) {
            // .bat must be invoked through cmd.exe; the application plugin's
            // generated script handles arg quoting on its own.
            listOf("cmd.exe", "/c", launcher.absolutePath)
        } else {
            check(launcher.canExecute()) { "launcher not executable: ${launcher.absolutePath}" }
            listOf(launcher.absolutePath)
        }
        val variantLabel = "host:${if (isWindows) "windows" else osName}"

        // RunProcessRequest's stdin overloads take ByteArray/String directly
        // and wrap them as a single-element Flow internally.
        val result = RunProcessRequest()
            .command(command)
            .stdin(StdoutCleanlinessHarness.handshakeBytes)
            .logPrefix("STDOUT-CLEAN-host")
            .description("npx-kt stdout cleanliness check (host)")
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
        // installDir.name is "mcp-steroid-proxy" — the application plugin's
        // applicationName.
        container.copyToContainer(installDir, "/tmp")
        val containerLauncher = "/tmp/${installDir.name}/bin/mcp-steroid-proxy"

        val result = container.startProcessInContainer {
            this
                .args(containerLauncher)
                .interactive()
                .stdin(flowOf(StdoutCleanlinessHarness.handshakeBytes))
                .description("npx-kt stdout cleanliness check")
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
