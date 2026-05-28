/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

@TestApplication
class ExceptionCaptureServiceTest {
    @Test
    fun julSevereErrorIsCaptured(): Unit = timeoutRunBlocking(100.seconds) {
        val service = ExceptionCaptureService()
        val rootLogger = Logger.getLogger("")
        val handlersBefore = rootLogger.handlers.toList()
        val failure = IllegalStateException("boom")

        try {
            val logger = Logger.getLogger(ExceptionCaptureServiceTest::class.java.name)

            val flow = service.exceptions
            val capturedException = async(start = CoroutineStart.UNDISPATCHED) {
                flow.first { it.throwable === failure }
            }

            assertEquals(handlersBefore.size + 1, rootLogger.handlers.size)

            val record = LogRecord(Level.SEVERE, "Failure while testing").apply {
                thrown = failure
                parameters = arrayOf("detail-1")
            }
            logger.log(record)

            val captured = capturedException.await()

            assertSame(failure, captured.throwable, "The original throwable should be preserved")
            assertEquals("Failure while testing: boom\ndetail-1", captured.message)
            assertTrue(captured.stacktrace.contains("IllegalStateException: boom"))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun julHandlerIsRemovedOnDispose() {
        val service = ExceptionCaptureService()
        val rootLogger = Logger.getLogger("")
        val handlersBefore = rootLogger.handlers.toList()

        // isn't pure call
        @Suppress("UnusedFlow")
        service.exceptions

        assertEquals(handlersBefore.size + 1, rootLogger.handlers.size)

        service.dispose()
        assertEquals(handlersBefore.size, rootLogger.handlers.size)
    }

    @Test
    fun julSevereErrorWithNullParametersIsCaptured(): Unit = timeoutRunBlocking(100.seconds) {
        val service = ExceptionCaptureService()
        val failure = IllegalStateException("missing params")

        try {
            val logger = Logger.getLogger("${ExceptionCaptureServiceTest::class.java.name}.nullParameters")
            val flow = service.exceptions
            val capturedException = async(start = CoroutineStart.UNDISPATCHED) {
                flow.first { it.throwable === failure }
            }

            val record = LogRecord(Level.SEVERE, "Failure with null parameters").apply {
                thrown = failure
                parameters = null
            }
            logger.log(record)

            val captured = capturedException.await()
            assertSame(failure, captured.throwable, "The original throwable should be preserved")
            assertEquals("Failure with null parameters: missing params", captured.message)
            assertTrue(captured.stacktrace.contains("IllegalStateException: missing params"))
        } finally {
            service.dispose()
        }
    }
}
