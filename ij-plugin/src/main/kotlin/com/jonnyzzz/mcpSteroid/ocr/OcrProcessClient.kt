/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ocr

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfoRt
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service for OCR text extraction from images.
 *
 * Uses the bundled ocr-tesseract CLI tool to perform OCR on screenshots.
 * The OCR process runs as a separate subprocess to avoid native library conflicts.
 */
@Service(Service.Level.APP)
class OcrProcessClient {
    private val log = thisLogger()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extract text from an image file using OCR.
     *
     * @param imagePath Path to the image file
     * @param language OCR language code (default: "eng")
     * @param level OCR detection level (TEXT_LINE or WORD)
     * @return OCR result with detected text blocks and their bounding boxes
     */
    fun extractText(imagePath: Path, language: String = "eng", level: OcrLevel = OcrLevel.TEXT_LINE): OcrResult {
        require(Files.exists(imagePath)) { "OCR image does not exist: $imagePath" }
        val executable = resolveExecutable()

        // Write arguments to an argfile to avoid "Command is too long" on Windows.
        // The argfile is placed next to the image in the mcp-run directory.
        val argFile = imagePath.resolveSibling("ocr-args.txt")
        val args = listOf("--image", imagePath.toString(), "--lang", language, "--level", level.cliToken())
        Files.writeString(argFile, args.joinToString("\n"), StandardCharsets.UTF_8)

        val commandLine = if (SystemInfoRt.isWindows) {
            // On Windows, the generated .bat wrapper expands CLASSPATH with long absolute paths
            // that exceed cmd.exe's 8191-char limit. Bypass the wrapper and invoke java directly
            // with a wildcard classpath.
            val javaHome = System.getProperty("java.home")
            val javaExe = Path.of(javaHome, "bin", "java.exe")
            val libDir = executable.root.resolve("lib")
            GeneralCommandLine(
                javaExe.toString(),
                "-cp", "${libDir.toAbsolutePath()}${File.separator}*",
                "com.jonnyzzz.mcpSteroid.ocr.app.OcrCliKt",
            )
        } else {
            GeneralCommandLine(executable.path.toString())
        }
        commandLine.withParameters("@${argFile.toAbsolutePath()}")
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(executable.root.toFile())

        commandLine.withEnvironment("JAVA_HOME", System.getProperty("java.home"))

        val output = runProcess(commandLine)
        val stdout = output.stdout.trim()
        if (stdout.isBlank()) {
            throw IllegalStateException("OCR returned empty output. stderr=${output.stderr.trim()}")
        }
        return json.decodeFromString(OcrResult.serializer(), stdout)
    }

    /**
     * Check if OCR is available (executable exists and can be found).
     */
    fun isAvailable(): Boolean {
        return try {
            resolveExecutable()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun runProcess(commandLine: GeneralCommandLine): ProcessOutput {
        val output = try {
            ExecUtil.execAndGetOutput(commandLine, OCR_TIMEOUT_MS)
        } catch (e: ExecutionException) {
            throw IllegalStateException("Failed to start OCR process: ${e.message}", e)
        }

        if (output.isTimeout || output.isCancelled) {
            throw IllegalStateException("OCR process did not finish before timeout")
        }
        if (output.exitCode != 0) {
            val stderr = output.stderr.trim()
            throw IllegalStateException("OCR process failed with exit code ${output.exitCode}: $stderr")
        }
        if (output.stderr.isNotBlank()) {
            log.warn("OCR process stderr: ${output.stderr.trim()}")
        }
        return output
    }

    private fun resolveExecutable(): OcrExecutable {
        val plugin = PluginDescriptorProvider.getInstance().descriptor
        val ocrRoot = plugin.pluginPath.resolve("ocr-tesseract")
        val bin = if (SystemInfoRt.isWindows) "bin/ocr-tesseract.bat" else "bin/ocr-tesseract"
        val executable = ocrRoot.resolve(bin)

        if (!Files.exists(executable)) {
            throw IllegalStateException("OCR executable not found: $executable")
        }
        if (!SystemInfoRt.isWindows && !Files.isExecutable(executable)) {
            executable.toFile().setExecutable(true)
        }
        return OcrExecutable(executable, ocrRoot)
    }

    private fun OcrLevel.cliToken(): String = name.lowercase()

    private data class OcrExecutable(val path: Path, val root: Path)

    companion object {
        private const val OCR_TIMEOUT_MS = 120_000

        fun getInstance(): OcrProcessClient = service()
    }
}
