/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ocr.app

import com.jonnyzzz.mcpSteroid.ocr.OcrLevel
import com.jonnyzzz.mcpSteroid.ocr.OcrResult
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Paths
import java.util.Locale

/**
 * Smoke tests for the OCR CLI tool. Runs the full OCR pipeline (native library
 * loading, Tesseract initialization, text extraction) against test images.
 *
 * These tests validate that:
 * 1. Native libraries load correctly on the current platform (Linux/macOS/Windows)
 * 2. Tesseract can extract text from simple images
 * 3. The JSON output format is correct
 *
 * Runs as part of the `ocr-tesseract` module tests — no IntelliJ Platform needed.
 */
class OcrCliSmokeTest {

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
        val argFile = java.nio.file.Files.createTempFile("ocr-test-", ".args")
        java.nio.file.Files.writeString(argFile, "--image\n${imagePath}\n--lang\neng\n--level\ntext_line")

        val result = captureMainOutput("@${argFile.toAbsolutePath()}")
        val parsed = json.decodeFromString(OcrResult.serializer(), result)
        assertContainsTokens(parsed, "HELLO", "OCR")
    }

    private fun runOcr(imageName: String, level: OcrLevel = OcrLevel.TEXT_LINE): OcrResult {
        val imagePath = loadImagePath(imageName)
        val output = captureMainOutput("--image", imagePath, "--lang", "eng", "--level", level.name.lowercase())
        return json.decodeFromString(OcrResult.serializer(), output)
    }

    private fun captureMainOutput(vararg args: String): String {
        val oldOut = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        try {
            main(args.toList().toTypedArray())
        } finally {
            System.setOut(oldOut)
        }
        return baos.toString().trim()
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
