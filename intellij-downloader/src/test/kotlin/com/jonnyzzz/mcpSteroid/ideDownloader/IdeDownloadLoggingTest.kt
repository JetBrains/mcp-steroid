/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class IdeDownloadLoggingTest {

    @Test
    fun `products API fetch message is debug only and never stdout`() {
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeReleaseLookup") as Logger
        withCapturedLogger(logger) { appender ->
            logger.level = Level.WARN
            val stdout = captureStdout {
                logFetchingProductsInfo("https://example.test/products")
            }

            assertFalse(stdout, stdout.contains("[IDE-DOWNLOAD] Fetching products info"))
            assertTrue("DEBUG message must be filtered at WARN", appender.list.isEmpty())

            logger.level = Level.DEBUG
            logFetchingProductsInfo("https://example.test/products")

            assertTrue(
                appender.list.any {
                    it.level == Level.DEBUG &&
                        it.formattedMessage == "[IDE-DOWNLOAD] Fetching products info from https://example.test/products"
                },
            )
        }
    }

    @Test
    fun `Android Studio fetch message is debug only and never stdout`() {
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.AndroidStudioReleaseLookup") as Logger
        withCapturedLogger(logger) { appender ->
            logger.level = Level.WARN
            val stdout = captureStdout {
                logFetchingAndroidStudioDownloads("https://developer.android.com/studio")
            }

            assertFalse(stdout, stdout.contains("[IDE-DOWNLOAD] Fetching Android Studio downloads"))
            assertTrue("DEBUG message must be filtered at WARN", appender.list.isEmpty())

            logger.level = Level.DEBUG
            logFetchingAndroidStudioDownloads("https://developer.android.com/studio")

            assertTrue(
                appender.list.any {
                    it.level == Level.DEBUG &&
                        it.formattedMessage ==
                        "[IDE-DOWNLOAD] Fetching Android Studio downloads from https://developer.android.com/studio"
                },
            )
        }
    }

    @Test
    fun `malformed archive URL falls back and logs warning`() {
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloader") as Logger
        withCapturedLogger(logger) { appender ->
            logger.level = Level.WARN

            val fileName = archiveFileNameFromUrl("http://example.com/[bad]", "fallback.tar.gz")

            assertEquals("fallback.tar.gz", fileName)
            assertTrue(
                "expected WARN about malformed archive URL",
                appender.list.any {
                    it.level == Level.WARN &&
                        it.formattedMessage.contains("Failed to parse archive file name from URL http://example.com/[bad]")
                },
            )
        }
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    private fun withCapturedLogger(
        logger: Logger,
        block: (ListAppender<ILoggingEvent>) -> Unit,
    ) {
        val originalLevel = logger.level
        val originalAdditive = logger.isAdditive
        val appender = ListAppender<ILoggingEvent>()
        appender.context = logger.loggerContext
        appender.start()
        logger.addAppender(appender)
        logger.isAdditive = false
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = originalLevel
            logger.isAdditive = originalAdditive
        }
    }
}
