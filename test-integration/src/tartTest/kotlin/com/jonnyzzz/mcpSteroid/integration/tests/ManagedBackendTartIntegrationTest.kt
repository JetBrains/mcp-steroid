/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

class ManagedBackendTartIntegrationTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    fun `devrig downloads starts and stops an isolated macOS IDEA Community backend`() {
        val runDir = Files.createTempDirectory("managed-backends-tart-").toFile()
        val vmName = "managed-backends-test"
        val baseImage = "ghcr.io/cirruslabs/macos-sequoia-base:latest"
        val tartRunLog = runDir.resolve("tart-run.log")
        var tartRunProcess: Process? = null

        requireCommand("sshpass")

        try {
            deleteVmIfPresent(vmName, runDir)
            exec("pull Tart base image", listOf("tart", "pull", baseImage), runDir, Duration.ofMinutes(30))
            exec("clone Tart VM", listOf("tart", "clone", baseImage, vmName), runDir, Duration.ofMinutes(10))
            exec("size Tart VM", listOf("tart", "set", vmName, "--cpu", "4", "--memory", "6144", "--disk-size", "60"), runDir)

            tartRunProcess = ProcessBuilder("tart", "run", "--no-graphics", vmName)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(tartRunLog))
                .redirectError(ProcessBuilder.Redirect.appendTo(tartRunLog))
                .start()

            val ip = waitForIp(vmName, runDir)
            waitForSsh(ip, runDir)

            val javaHomeArchive = packageHostJavaHome(runDir)
            scpToGuest(ip, javaHomeArchive, "~/java-home.tar.gz", runDir)
            scpToGuest(ip, IdeTestFolders.devrigPackageZip, "~/devrig.zip", runDir)
            sshScript(
                ip = ip,
                description = "install devrig distribution",
                runDir = runDir,
                script = """
                    set -euo pipefail
                    rm -rf "${'$'}HOME/devrig-cli" "${'$'}HOME/java-home" ${'$'}HOME/.mcp-steroid
                    mkdir -p "${'$'}HOME/devrig-cli" "${'$'}HOME/java-home" ${'$'}HOME/.mcp-steroid
                    tar -xzf "${'$'}HOME/java-home.tar.gz" -C "${'$'}HOME/java-home"
                    ditto -x -k "${'$'}HOME/devrig.zip" "${'$'}HOME/devrig-cli"
                    app_dir="${'$'}(find "${'$'}HOME/devrig-cli" -maxdepth 1 -type d -name 'devrig-*' | head -1)"
                    test -n "${'$'}app_dir"
                    mv "${'$'}app_dir" "${'$'}HOME/devrig-cli/app"
                    cat > "${'$'}HOME/devrig-cli/devrig" <<'SH'
                    #!/usr/bin/env bash
                    set -euo pipefail
                    export JAVA_HOME="${'$'}HOME/java-home"
                    export PATH="${'$'}JAVA_HOME/bin:${'$'}PATH"
                    exec "${'$'}HOME/devrig-cli/app/bin/devrig" "${'$'}@"
                    SH
                    chmod +x "${'$'}HOME/devrig-cli/devrig"
                    "${'$'}HOME/devrig-cli/devrig" --version
                """.trimIndent(),
            )

            sshScript(
                ip = ip,
                description = "download IDEA Community backend",
                runDir = runDir,
                timeout = Duration.ofMinutes(25),
                script = """
                    set -euo pipefail
                    "${'$'}HOME/devrig-cli/devrig" backend download idea-community
                    backend_dir="${'$'}(find ${'$'}HOME/.mcp-steroid/backends -mindepth 1 -maxdepth 1 -type d -name 'idea-community-*' | head -1)"
                    test -n "${'$'}backend_dir"
                    id="${'$'}(basename "${'$'}backend_dir")"
                    app_dir="${'$'}(find "${'$'}backend_dir" -mindepth 1 -maxdepth 1 -type d -name 'IntelliJ IDEA*.app' | head -1)"
                    test -n "${'$'}app_dir"
                    vmoptions="${'$'}backend_dir/${'$'}(basename "${'$'}app_dir").vmoptions"
                    test -f "${'$'}vmoptions"
                    grep -F -- "-Didea.config.path=${'$'}HOME/.mcp-steroid/caches/${'$'}id/config" "${'$'}vmoptions"
                    grep -F -- "-Didea.system.path=${'$'}HOME/.mcp-steroid/caches/${'$'}id/system" "${'$'}vmoptions"
                    grep -F -- "-Didea.log.path=${'$'}HOME/.mcp-steroid/caches/${'$'}id/logs" "${'$'}vmoptions"
                    grep -F -- "-Didea.plugins.path=${'$'}HOME/.mcp-steroid/caches/${'$'}id/plugins" "${'$'}vmoptions"
                    printf '%s\n' "${'$'}id" > /tmp/managed-backend-id
                """.trimIndent(),
            )

            sshScript(
                ip = ip,
                description = "start and verify macOS backend isolation",
                runDir = runDir,
                timeout = Duration.ofMinutes(5),
                script = """
                    set -euo pipefail
                    "${'$'}HOME/devrig-cli/devrig" backend start idea-community | tee /tmp/managed-backend-start.txt
                    grep -E '^pid: [0-9]+' /tmp/managed-backend-start.txt
                    for _ in ${'$'}(seq 1 60); do
                      if pgrep -f 'IntelliJ IDEA( CE)?[.]app|com.intellij.idea.Main|/Contents/MacOS/idea' >/dev/null; then
                        break
                      fi
                      sleep 2
                    done
                    pgrep -f 'IntelliJ IDEA( CE)?[.]app|com.intellij.idea.Main|/Contents/MacOS/idea'
                    if [ -d "${'$'}HOME/Library/Application Support/JetBrains" ] && [ "${'$'}(find "${'$'}HOME/Library/Application Support/JetBrains" -mindepth 1 -maxdepth 1 -type d | wc -l)" -ne 0 ]; then
                      find "${'$'}HOME/Library/Application Support/JetBrains" -mindepth 1 -maxdepth 1 -type d >&2
                      exit 1
                    fi
                """.trimIndent(),
            )

            sshScript(
                ip = ip,
                description = "stop macOS backend",
                runDir = runDir,
                timeout = Duration.ofMinutes(3),
                script = """
                    set -euo pipefail
                    "${'$'}HOME/devrig-cli/devrig" backend stop idea-community
                    for _ in ${'$'}(seq 1 30); do
                      if ! pgrep -f 'IntelliJ IDEA( CE)?[.]app|com.intellij.idea.Main|/Contents/MacOS/idea' >/dev/null; then
                        exit 0
                      fi
                      sleep 1
                    done
                    pgrep -af 'IntelliJ IDEA( CE)?[.]app|com.intellij.idea.Main|/Contents/MacOS/idea' >&2
                    exit 1
                """.trimIndent(),
            )
        } finally {
            tartRunProcess?.destroy()
            deleteVmIfPresent(vmName, runDir)
            runDir.deleteRecursively()
        }
    }

    private fun packageHostJavaHome(runDir: File): File {
        val javaHome = File(System.getProperty("java.home"))
        require(javaHome.resolve("bin/java").isFile) { "Current JVM home does not contain bin/java: ${javaHome.absolutePath}" }
        val archive = runDir.resolve("java-home.tar.gz")
        exec("package host Java runtime", listOf("tar", "-C", javaHome.absolutePath, "-czf", archive.absolutePath, "."), runDir, Duration.ofMinutes(5))
        return archive
    }

    private fun requireCommand(command: String) {
        val runDir = Files.createTempDirectory("managed-backends-command-check-").toFile()
        try {
            val result = execAllowFailure("check $command", listOf("/bin/sh", "-c", "command -v $command"), runDir)
            assertEquals(0, result.exitCode, "Required command is not available: $command\n${result.stderr}")
        } finally {
            runDir.deleteRecursively()
        }
    }

    private fun waitForIp(vmName: String, runDir: File): String {
        repeat(90) {
            val result = execAllowFailure("tart ip $it", listOf("tart", "ip", vmName), runDir, Duration.ofSeconds(10))
            val ip = result.stdout.trim()
            if (result.exitCode == 0 && ip.isNotBlank()) return ip
            Thread.sleep(2_000)
        }
        error("Tart VM never received an IP; see ${runDir.resolve("tart-run.log").absolutePath}")
    }

    private fun waitForSsh(ip: String, runDir: File) {
        repeat(90) {
            val result = execAllowFailure("ssh ready $it", sshBase(ip) + "true", runDir, Duration.ofSeconds(10))
            if (result.exitCode == 0) return
            Thread.sleep(2_000)
        }
        error("Tart VM at $ip never accepted SSH")
    }

    private fun sshScript(ip: String, description: String, runDir: File, script: String, timeout: Duration = Duration.ofMinutes(10)) {
        val localScript = runDir.resolve("${description.safeFileName()}.sh")
        localScript.writeText(script.trimIndent() + "\n")
        val remoteScript = "/tmp/${localScript.name}"
        scpToGuest(ip, localScript, remoteScript, runDir)
        exec(description, sshBase(ip) + listOf("bash", remoteScript), runDir, timeout)
    }

    private fun scpToGuest(ip: String, localFile: File, remotePath: String, runDir: File) {
        exec(
            description = "scp ${localFile.name}",
            command = listOf(
                "sshpass", "-p", "admin",
                "scp",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "PreferredAuthentications=password",
                "-o", "PubkeyAuthentication=no",
                "-o", "NumberOfPasswordPrompts=1",
                localFile.absolutePath,
                "admin@$ip:$remotePath",
            ),
            runDir = runDir,
            timeout = Duration.ofMinutes(5),
        )
    }

    private fun deleteVmIfPresent(vmName: String, runDir: File) {
        execAllowFailure("stop Tart VM", listOf("tart", "stop", vmName), runDir, Duration.ofMinutes(3))
        execAllowFailure("delete Tart VM", listOf("tart", "delete", vmName), runDir, Duration.ofMinutes(5))
    }

    private fun sshBase(ip: String) = listOf(
        "sshpass", "-p", "admin",
        "ssh",
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "PreferredAuthentications=password",
        "-o", "PubkeyAuthentication=no",
        "-o", "NumberOfPasswordPrompts=1",
        "admin@$ip",
    )

    private fun exec(description: String, command: List<String>, runDir: File, timeout: Duration = Duration.ofMinutes(2)): ExecResult {
        val result = execAllowFailure(description, command, runDir, timeout)
        assertEquals(
            0,
            result.exitCode,
            "Command failed: $description\ncommand=${command.joinToString(" ")}\nstdout=${result.stdout}\nstderr=${result.stderr}",
        )
        return result
    }

    private fun execAllowFailure(
        description: String,
        command: List<String>,
        runDir: File,
        timeout: Duration = Duration.ofMinutes(2),
    ): ExecResult {
        runDir.mkdirs()
        val outFile = runDir.resolve("${description.safeFileName()}.out")
        val errFile = runDir.resolve("${description.safeFileName()}.err")
        val process = ProcessBuilder(command)
            .redirectOutput(outFile)
            .redirectError(errFile)
            .start()
        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Command timed out after $timeout: $description\ncommand=${command.joinToString(" ")}")
        }
        return ExecResult(
            exitCode = process.exitValue(),
            stdout = outFile.readText(),
            stderr = errFile.readText(),
        )
    }

    private fun String.safeFileName() = replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "command" }

    private data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)
}
