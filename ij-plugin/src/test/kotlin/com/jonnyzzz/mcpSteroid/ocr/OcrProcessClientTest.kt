/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ocr

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class OcrProcessClientTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testExtractsHelloOcrText(): Unit = timeoutRunBlocking(90.seconds) {
        val image = loadImage("hello-ocr.png")
        val result = OcrProcessClient.getInstance().extractText(image)
        assertContainsTokens(result, "HELLO", "OCR")
    }

    fun testExtractsMultiLineText(): Unit = timeoutRunBlocking(90.seconds) {
        val image = loadImage("multi-line.png")
        val result = OcrProcessClient.getInstance().extractText(image)
        assertContainsTokens(result, "FIRST", "LINE", "SECOND")
    }

    fun testExtractsNumbers(): Unit = timeoutRunBlocking(90.seconds) {
        val image = loadImage("numbers.png")
        val result = OcrProcessClient.getInstance().extractText(image)
        assertContainsTokens(result, "12345", "TEST")
    }

    private fun loadImage(name: String): Path = requireNotNull(javaClass.getResource("/ocr/$name")).let { url ->
        Paths.get(url.toURI())
    }

    private fun assertContainsTokens(result: OcrResult, vararg tokens: String) {
        val text = result.blocks.joinToString(" ") { it.text }
        val normalized = normalize(text)
        for (token in tokens) {
            assertTrue("Expected OCR output to contain '$token' in: $normalized", normalized.contains(token))
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
}
