/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ocr.app

import com.jonnyzzz.mcpSteroid.ocr.OcrResult
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for the OCR CLI tool. Runs the CLI as a **subprocess** (not in-process)
 * to match production usage and avoid native library crashes in the test JVM.
 *
 * These tests validate that:
 * 1. Native libraries load correctly on the current platform (Linux/macOS/Windows)
 * 2. Tesseract can extract text from simple images
 * 3. The JSON output format is correct
 * 4. The @argfile mechanism works
 */
class OcrCliSmokeTest {

    @Test
    fun `extract text from hello-ocr image`() {
        val result = runOcrSubprocess("hello-ocr.png")
        assertContainsTokens(result, "HELLO", "OCR")
    }

    @Test
    fun `extract text from multi-line image`() {
        val result = runOcrSubprocess("multi-line.png")
        assertContainsTokens(result, "FIRST", "LINE", "SECOND")
    }

    @Test
    fun `extract text from numbers image`() {
        val result = runOcrSubprocess("numbers.png")
        assertContainsTokens(result, "12345", "TEST")
    }

    @Test
    fun `argfile expansion works`() {
        val imagePath = loadImagePath("hello-ocr.png")
        val argFile = Files.createTempFile("ocr-test-", ".args")
        Files.writeString(argFile, "--image\n$imagePath\n--lang\neng\n--level\ntext_line")

        val result = runOcrSubprocessRaw("@${argFile.toAbsolutePath()}")
        val parsed = json.decodeFromString(OcrResult.serializer(), result)
        assertContainsTokens(parsed, "HELLO", "OCR")
    }

    /**
     * Runs the OCR CLI as a subprocess using `java -cp ... OcrCliKt`.
     * This matches how OcrProcessClient invokes the tool in production.
     */
    private fun runOcrSubprocess(imageName: String): OcrResult {
        val imagePath = loadImagePath(imageName)
        val output = runOcrSubprocessRaw("--image", imagePath, "--lang", "eng", "--level", "text_line")
        return json.decodeFromString(OcrResult.serializer(), output)
    }

    private fun runOcrSubprocessRaw(vararg args: String): String {
        val javaHome = System.getProperty("java.home")
        val javaExe = Paths.get(javaHome, "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")

        val command = mutableListOf(javaExe, "-cp", classpath)
        // Pass tessdata location to subprocess if available
        val tessdataPrefix = System.getProperty("tessdata.prefix")
        if (tessdataPrefix != null) {
            command.add("-Dtessdata.prefix=$tessdataPrefix")
        }
        command.add("com.jonnyzzz.mcpSteroid.ocr.app.OcrCliKt")
        command.addAll(args)

        val pb = ProcessBuilder(command)
            .redirectErrorStream(false)
        // Also pass via env var as fallback
        if (tessdataPrefix != null) {
            pb.environment()["TESSDATA_PREFIX"] = tessdataPrefix
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
