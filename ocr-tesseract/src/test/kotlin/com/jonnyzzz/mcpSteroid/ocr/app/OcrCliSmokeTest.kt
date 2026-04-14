/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ocr.app

import com.jonnyzzz.mcpSteroid.ocr.OcrResult
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for the OCR CLI tool. Runs the installed distribution binary
 * (via `installDist`) as a subprocess on all platforms.
 *
 * The launcher path is passed via `-Docr.test.launcher` system property,
 * set by the Gradle test task after `installDist` completes.
 */
class OcrCliSmokeTest {

    private val launcher: String = requireNotNull(System.getProperty("ocr.test.launcher")) {
        "System property 'ocr.test.launcher' not set — run via Gradle (:ocr-tesseract:test)"
    }

    private val installDir: Path = Paths.get(
        requireNotNull(System.getProperty("ocr.test.install.dir")) {
            "System property 'ocr.test.install.dir' not set — run via Gradle (:ocr-tesseract:test)"
        }
    )

    @Test
    fun `extract text from hello-ocr image`() {
        val result = runOcr("hello-ocr.png")
        assertContainsTokens(result, "HELLO", "OCR")
    }

    @Test
    fun `extract text from multi-line image`() {
        val result = runOcr("multi-line.png")
        assertContainsTokens(result, "FIRST", "LINE", "SECOND")
    }

    @Test
    fun `extract text from numbers image`() {
        val result = runOcr("numbers.png")
        assertContainsTokens(result, "12345", "TEST")
    }

    @Test
    fun `argfile expansion works`() {
        val imagePath = loadImagePath("hello-ocr.png")
        val argFile = Files.createTempFile("ocr-test-", ".args")
        Files.writeString(argFile, "--image\n$imagePath\n--lang\neng\n--level\ntext_line")

        val output = runLauncher("@${argFile.toAbsolutePath()}")
        val result = json.decodeFromString(OcrResult.serializer(), output)
        assertContainsTokens(result, "HELLO", "OCR")
    }

    private fun runOcr(imageName: String): OcrResult {
        val imagePath = loadImagePath(imageName)
        val output = runLauncher("--image", imagePath, "--lang", "eng", "--level", "text_line")
        return json.decodeFromString(OcrResult.serializer(), output)
    }

    /**
     * Runs the OCR CLI via the installed distribution launcher script.
     * On Windows: `bin/ocr-tesseract.bat`, on Unix: `bin/ocr-tesseract`.
     */
    private fun runLauncher(vararg args: String): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val command = if (isWindows) {
            mutableListOf("cmd.exe", "/c", launcher)
        } else {
            // Ensure the launcher is executable
            val launcherFile = Paths.get(launcher)
            if (!Files.isExecutable(launcherFile)) {
                launcherFile.toFile().setExecutable(true)
            }
            mutableListOf(launcher)
        }
        command.addAll(args)

        val pb = ProcessBuilder(command)
            .directory(installDir.toFile())
            .redirectErrorStream(false)
        pb.environment()["JAVA_HOME"] = System.getProperty("java.home")
        // On Windows, add native/ to PATH so LoadLibrary() finds transitive DLL
        // dependencies (libleptonica1850.dll, MSVC runtime) when loading libtesseract551.dll.
        val nativeDir = installDir.resolve("native")
        if (Files.isDirectory(nativeDir)) {
            val path = pb.environment().getOrDefault("PATH", "")
            pb.environment()["PATH"] = "${nativeDir.toAbsolutePath()}${File.pathSeparator}$path"
        }

        val process = pb.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        val finished = process.waitFor(120, TimeUnit.SECONDS)
        assertTrue(finished) { "OCR process timed out after 120s" }

        assertEquals(0, process.exitValue()) {
            "OCR process failed with exit code ${process.exitValue()}.\nstderr: $stderr\nstdout: $stdout"
        }
        return stdout.trim()
    }

    private fun loadImagePath(name: String): String {
        val url = requireNotNull(javaClass.getResource("/ocr/$name")) { "Test image not found: /ocr/$name" }
        return Paths.get(url.toURI()).toAbsolutePath().toString()
    }

    private fun assertContainsTokens(result: OcrResult, vararg tokens: String) {
        val text = result.blocks.joinToString(" ") { it.text }
        val normalized = normalize(text)
        for (token in tokens) {
            assertTrue(normalized.contains(token)) {
                "Expected OCR output to contain '$token' in: $normalized"
            }
        }
    }

    private fun normalize(text: String): String {
        val builder = StringBuilder()
        for (ch in text.uppercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit()) {
                builder.append(ch)
            } else {
                builder.append(' ')
            }
        }
        return builder.toString().replace("  ", " ").trim()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
