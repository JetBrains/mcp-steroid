/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

inline val kotlincProcessClient: KotlincProcessClient get() = service()

@Service(Service.Level.APP)
class KotlincProcessClient {
    private val log = thisLogger()

    fun kotlinc(args: List<String>, workingDir: Path? = null): ProcessOutput {
        val executable = resolveExecutable()

        // Always use an argfile to avoid "Command is too long" on Windows.
        // kotlinc natively supports @argfile. Write to a temp file under the working dir.
        val effectiveWorkDir = workingDir ?: executable.root
        val argFile = Files.createTempFile(effectiveWorkDir, "kotlinc-", ".args")
        writeKotlincArgFile(argFile, args)

        val commandLine = if (SystemInfoRt.isWindows) {
            GeneralCommandLine("cmd.exe", "/c", executable.path.toString())
        } else {
            GeneralCommandLine(executable.path.toString())
        }

        commandLine.withParameters("@${argFile.toAbsolutePath()}")
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(effectiveWorkDir.toFile())

        commandLine.withEnvironment("JAVA_HOME", System.getProperty("java.home"))
        commandLine.withEnvironment("KOTLIN_HOME", executable.root.toString())

        return runProcess(commandLine)
    }

    private fun runProcess(commandLine: GeneralCommandLine): ProcessOutput {
        val output = try {
            ExecUtil.execAndGetOutput(commandLine, 120_000)
        } catch (e: ExecutionException) {
            throw IllegalStateException("Failed to start kotlinc process: ${e.message}", e)
        }

        if (output.isTimeout || output.isCancelled) {
            throw IllegalStateException("Kotlinc process did not finish before timeout")
        }
        return output
    }

    private fun resolveExecutable(): KotlincExecutable {
        val customHome = Registry.stringValue("mcp.steroid.kotlinc.home").takeIf { it.isNotBlank() }
        val kotlincRoot = if (customHome != null) {
            val path = Path.of(customHome)
            if (!Files.isDirectory(path)) {
                throw IllegalStateException("Custom kotlinc home does not exist: $customHome")
            }
            log.info("Using external kotlinc home: $customHome")
            path
        } else {
            val plugin = PluginDescriptorProvider.getInstance().descriptor
            plugin.pluginPath.resolve("kotlinc")
        }

        val bin = if (SystemInfoRt.isWindows) "bin/kotlinc.bat" else "bin/kotlinc"
        val executable = kotlincRoot.resolve(bin)

        if (!Files.exists(executable)) {
            throw IllegalStateException("Kotlinc executable not found: $executable")
        }
        if (!SystemInfoRt.isWindows && !Files.isExecutable(executable)) {
            executable.toFile().setExecutable(true)
        }
        return KotlincExecutable(executable, kotlincRoot)
    }

    private data class KotlincExecutable(val path: Path, val root: Path)
}
