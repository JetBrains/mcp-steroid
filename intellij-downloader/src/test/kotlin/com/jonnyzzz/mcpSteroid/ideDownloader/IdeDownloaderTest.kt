/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdeDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `downloadFile writes destination and removes temp file`() {
        val payload = "downloaded archive".toByteArray()
        val dest = File(tmp.root, "idea.tar.gz")
        val tempFile = File(tmp.root, "idea.tar.gz.tmp")

        withArchiveServer(payload) { url ->
            downloadFile(url, dest)
        }

        assertTrue("destination archive must exist after download", dest.isFile)
        assertFalse("temporary archive must be removed after successful move", tempFile.exists())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `downloadFile replaces existing destination bytes`() {
        val stale = "stale archive".toByteArray()
        val payload = "fresh archive".toByteArray()
        val dest = File(tmp.root, "idea.tar.gz")
        dest.writeBytes(stale)
        val tempFile = File(tmp.root, "idea.tar.gz.tmp")

        withArchiveServer(payload) { url ->
            downloadFile(url, dest)
        }

        assertTrue("destination archive must still exist after replacement", dest.isFile)
        assertFalse("temporary archive must be removed after replacement", tempFile.exists())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `resolveAndDownload verifies checksum URL and deletes archive on mismatch`() {
        val payload = "verified archive".toByteArray()
        val goodSha256 = sha256(payload)
        var checksumText = "$goodSha256  archive.tar.gz\n"

        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange -> sendBytes(exchange, payload) }
            server.createContext("/archive.tar.gz.sha256") { exchange -> sendText(exchange, checksumText) }
        }) { baseUrl ->
            val distribution = IdeDistribution.FromUrl(
                product = IdeProduct.IntelliJIdeaCommunity,
                url = "$baseUrl/archive.tar.gz",
                checksumUrl = "$baseUrl/archive.tar.gz.sha256",
            )

            val archive = distribution.resolveAndDownload(tmp.root, os = HostOs.LINUX)

            assertArrayEquals(payload, archive.readBytes())

            checksumText = "${"0".repeat(64)}  archive.tar.gz\n"
            val error = expectError { distribution.resolveAndDownload(tmp.root, os = HostOs.LINUX) }

            assertTrue("expected checksum mismatch, got: ${error.message}", error.message!!.contains("SHA-256 mismatch"))
            assertFalse("corrupt cached/downloaded archive must be deleted", archive.exists())
        }
    }

    @Test
    fun `fetchChecksumWithRetry retries IOException and stops after success`() {
        val checksumUrl = "https://cache-redirector.jetbrains.com/archive.tar.gz.sha256"
        val checksumText = "${"a".repeat(64)}  archive.tar.gz\n"
        val attempts = AtomicInteger()
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloader") as Logger

        withCapturedLogger(logger) { appender ->
            logger.level = Level.WARN
            withChecksumTextReader(
                reader = { url, accept ->
                    assertEquals(checksumUrl, url)
                    assertEquals("text/plain,*/*", accept)
                    if (attempts.incrementAndGet() == 1) {
                        throw IOException("CDN 502")
                    }
                    checksumText
                },
            ) {
                assertEquals(checksumText, fetchChecksumWithRetry(checksumUrl, backoffMs = longArrayOf(0L)))
            }

            assertEquals("checksum fetch must stop after first successful retry", 2, attempts.get())
            val warnings = appender.list.filter {
                it.level == Level.WARN && it.formattedMessage.contains("Checksum fetch attempt 1/3 failed")
            }
            assertEquals("expected exactly one checksum retry warning", 1, warnings.size)
            assertTrue(warnings.single().formattedMessage.contains("CDN 502"))
        }
    }

    @Test
    fun `fetchChecksumWithRetry wraps final IOException after bounded attempts`() {
        val checksumUrl = "https://cache-redirector.jetbrains.com/archive.tar.gz.sha256"
        val attempts = AtomicInteger()

        withChecksumTextReader(
            reader = { _, _ ->
                val attempt = attempts.incrementAndGet()
                throw IOException("CDN 502 attempt $attempt")
            },
        ) {
            val error = expectError {
                fetchChecksumWithRetry(checksumUrl, attempts = 3, backoffMs = longArrayOf(0L))
            }

            assertTrue("expected IOException, got: ${error.javaClass.name}", error is IOException)
            assertEquals("checksum fetch must stop after the configured attempts", 3, attempts.get())
            assertEquals("Failed to fetch SHA-256 checksum from $checksumUrl after 3 attempts", error.message)
            assertEquals("CDN 502 attempt 3", error.cause?.message)
        }
    }

    @Test
    fun `resolveAndDownload does not retry malformed checksum text`() {
        val checksumUrl = "https://cache-redirector.jetbrains.com/archive.tar.gz.sha256"
        val attempts = AtomicInteger()

        withChecksumTextReader(
            reader = { _, _ ->
                attempts.incrementAndGet()
                "not-a-sha256\n"
            },
        ) {
            val error = expectError {
                IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdeaCommunity,
                    url = "https://cache-redirector.jetbrains.com/archive.tar.gz",
                    checksumUrl = checksumUrl,
                ).resolveAndDownload(tmp.root, os = HostOs.LINUX)
            }

            assertTrue("expected malformed checksum failure, got: ${error.message}", error.message!!.contains("Invalid SHA-256 checksum"))
            assertEquals("malformed checksum text must not be retried", 1, attempts.get())
        }
    }

    @Test
    fun `resolveAndDownload with inline checksum does not fetch checksum URL`() {
        val payload = "inline checksum archive".toByteArray()
        val goodSha256 = sha256(payload)
        val attempts = AtomicInteger()

        withChecksumTextReader(
            reader = { _, _ ->
                attempts.incrementAndGet()
                throw AssertionError("inline checksum must not fetch checksum URL")
            },
        ) {
            withArchiveServer(payload) { url ->
                val archive = IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdeaCommunity,
                    url = url,
                    expectedSha256 = goodSha256,
                ).resolveAndDownload(tmp.root, os = HostOs.LINUX)

                assertArrayEquals(payload, archive.readBytes())
            }
        }
        assertEquals("inline checksum path must not use the checksum text reader", 0, attempts.get())
    }

    @Test
    fun `resolveAndDownload serializes concurrent downloads for the same archive`() {
        val payload = "shared archive".toByteArray()
        val goodSha256 = sha256(payload)
        val archiveRequests = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)

        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange ->
                archiveRequests.incrementAndGet()
                Thread.sleep(100)
                sendBytes(exchange, payload)
            }
            server.createContext("/archive.tar.gz.sha256") { exchange ->
                sendText(exchange, "$goodSha256  archive.tar.gz\n")
            }
        }) { baseUrl ->
            val distribution = IdeDistribution.FromUrl(
                product = IdeProduct.IntelliJIdeaCommunity,
                url = "$baseUrl/archive.tar.gz",
                checksumUrl = "$baseUrl/archive.tar.gz.sha256",
            )
            val barrier = CyclicBarrier(4)
            val futures = List(4) {
                executor.submit<File> {
                    barrier.await()
                    distribution.resolveAndDownload(tmp.root, os = HostOs.LINUX)
                }
            }

            try {
                futures.forEach { future ->
                    assertEquals(File(tmp.root, "archive.tar.gz"), future.get(10, TimeUnit.SECONDS))
                }
            } finally {
                executor.shutdownNow()
            }
        }

        assertEquals("only one caller should download the shared archive", 1, archiveRequests.get())
        assertFalse(File(tmp.root, "archive.tar.gz.tmp").exists())
        assertArrayEquals(payload, File(tmp.root, "archive.tar.gz").readBytes())
    }

    @Test
    fun `resolveAndDownload without checksum logs warning but succeeds`() {
        val payload = "unverified archive".toByteArray()
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloader") as Logger

        withCapturedLogger(logger) { appender ->
            logger.level = Level.WARN
            withArchiveServer(payload) { url ->
                val archive = IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdeaCommunity,
                    url = url,
                ).resolveAndDownload(tmp.root, os = HostOs.LINUX)

                assertArrayEquals(payload, archive.readBytes())
            }

            assertTrue(
                "expected WARN about missing SHA-256",
                appender.list.any {
                    it.level == Level.WARN &&
                        it.formattedMessage.contains("No SHA-256 checksum available")
                },
            )
        }
    }

    @Test
    fun `resolveAndDownload logs resolved archive before verified cache hit`() {
        val payload = "cached archive".toByteArray()
        val goodSha256 = sha256(payload)
        val dest = File(tmp.root, "archive.tar.gz")
        dest.writeBytes(payload)
        File(tmp.root, "archive.tar.gz.sha256").writeText("$goodSha256  archive.tar.gz\n")
        val archiveRequests = AtomicInteger()
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloader") as Logger

        withCapturedLogger(logger) { appender ->
            logger.level = Level.DEBUG
            withServer({ server ->
                server.createContext("/archive.tar.gz") { exchange ->
                    archiveRequests.incrementAndGet()
                    sendBytes(exchange, payload)
                }
                server.createContext("/archive.tar.gz.sha256") { exchange ->
                    sendText(exchange, "$goodSha256  archive.tar.gz\n")
                }
            }) { baseUrl ->
                val url = "$baseUrl/archive.tar.gz"
                val archive = IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdeaCommunity,
                    url = url,
                    checksumUrl = "$url.sha256",
                ).resolveAndDownload(tmp.root, os = HostOs.LINUX)

                assertEquals(dest, archive)
                assertEquals("cache hit must not fetch the archive bytes", 0, archiveRequests.get())
                val resolvedMessage = "[IDE-DOWNLOAD] Resolved archive: $url -> $dest"
                val cachedMessage = "[IDE-DOWNLOAD] Using verified cached archive: $dest"
                assertEquals(1, appender.list.count { it.level == Level.INFO && it.formattedMessage == resolvedMessage })
                val resolvedIndex = appender.list.indexOfFirst { it.level == Level.INFO && it.formattedMessage == resolvedMessage }
                val cachedIndex = appender.list.indexOfFirst { it.level == Level.DEBUG && it.formattedMessage == cachedMessage }
                assertTrue("expected resolved archive INFO log", resolvedIndex >= 0)
                assertTrue("cache-hit DEBUG log must follow resolved archive INFO log", cachedIndex > resolvedIndex)
            }
        }
    }

    @Test
    fun `resolveAndDownload logs resolved archive before cold download`() {
        val payload = "fresh archive".toByteArray()
        val goodSha256 = sha256(payload)
        val archiveRequests = AtomicInteger()
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloader") as Logger

        withCapturedLogger(logger) { appender ->
            logger.level = Level.DEBUG
            withServer({ server ->
                server.createContext("/archive.tar.gz") { exchange ->
                    archiveRequests.incrementAndGet()
                    sendBytes(exchange, payload)
                }
                server.createContext("/archive.tar.gz.sha256") { exchange ->
                    sendText(exchange, "$goodSha256  archive.tar.gz\n")
                }
            }) { baseUrl ->
                val url = "$baseUrl/archive.tar.gz"
                val dest = File(tmp.root, "archive.tar.gz")
                val archive = IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdeaCommunity,
                    url = url,
                    checksumUrl = "$url.sha256",
                ).resolveAndDownload(tmp.root, os = HostOs.LINUX)

                assertEquals(dest, archive)
                assertArrayEquals(payload, archive.readBytes())
                assertEquals("cold path must download the archive once", 1, archiveRequests.get())
                val resolvedMessage = "[IDE-DOWNLOAD] Resolved archive: $url -> $dest"
                val downloadingMessage = "[IDE-DOWNLOAD] Downloading $url -> $dest"
                assertEquals(1, appender.list.count { it.level == Level.INFO && it.formattedMessage == resolvedMessage })
                val resolvedIndex = appender.list.indexOfFirst { it.level == Level.INFO && it.formattedMessage == resolvedMessage }
                val downloadingIndex = appender.list.indexOfFirst { it.level == Level.DEBUG && it.formattedMessage == downloadingMessage }
                assertTrue("expected resolved archive INFO log", resolvedIndex >= 0)
                assertTrue("download DEBUG log must follow resolved archive INFO log", downloadingIndex > resolvedIndex)
            }
        }
    }

    @Test
    fun `downloadFile resumes interrupted temp file with Range request`() {
        val payload = payloadBytes(size = 5 * 1024 * 1024)
        val interruptAfterBytes = 1_234_567
        val dest = File(tmp.root, "idea.tar.gz")
        val tempFile = File(tmp.root, "idea.tar.gz.tmp")
        val requestRanges = CopyOnWriteArrayList<String?>()
        val requests = AtomicInteger()

        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                requestRanges.add(range)
                if (requests.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { output ->
                        output.write(payload, 0, interruptAfterBytes)
                        output.flush()
                    }
                } else {
                    val offset = parseRangeOffset(range)
                    exchange.responseHeaders.add("Content-Range", "bytes $offset-${payload.size - 1}/${payload.size}")
                    exchange.sendResponseHeaders(206, (payload.size - offset).toLong())
                    exchange.responseBody.use { output -> output.write(payload, offset, payload.size - offset) }
                }
            }
        }) { baseUrl ->
            val url = "$baseUrl/archive.tar.gz"
            expectError { downloadFile(url, dest) }

            assertTrue("interrupted download should leave a temp file", tempFile.isFile)
            assertEquals(interruptAfterBytes.toLong(), tempFile.length())

            downloadFile(url, dest)
        }

        assertFalse("temp file must be moved away after successful resume", tempFile.exists())
        assertArrayEquals(payload, dest.readBytes())
        assertEquals(listOf(null, "bytes=$interruptAfterBytes-"), requestRanges.toList())
    }

    @Test
    fun `downloadFile restarts when server ignores Range request`() {
        val payload = payloadBytes(size = 5 * 1024 * 1024)
        val resumeBytes = 777_777
        val dest = File(tmp.root, "idea.tar.gz")
        val tempFile = File(tmp.root, "idea.tar.gz.tmp")
        tempFile.writeBytes(payload.copyOfRange(0, resumeBytes))
        val requestRanges = CopyOnWriteArrayList<String?>()

        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange ->
                requestRanges.add(exchange.requestHeaders.getFirst("Range"))
                sendBytes(exchange, payload)
            }
        }) { baseUrl ->
            downloadFile("$baseUrl/archive.tar.gz", dest)
        }

        assertFalse("temp file must be moved away after fresh restart", tempFile.exists())
        assertArrayEquals(payload, dest.readBytes())
        assertEquals(listOf("bytes=$resumeBytes-"), requestRanges.toList())
    }

    private fun withArchiveServer(payload: ByteArray, block: (String) -> Unit) {
        withServer({ server ->
            server.createContext("/archive.tar.gz") { exchange -> sendBytes(exchange, payload) }
        }) { baseUrl ->
            block("$baseUrl/archive.tar.gz")
        }
    }

    private fun withServer(configure: (HttpServer) -> Unit, block: (String) -> Unit) {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        configure(server)
        server.start()
        try {
            val baseUrl = URI("http://127.0.0.1:${server.address.port}").toString()
            block(baseUrl)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun sendBytes(exchange: HttpExchange, payload: ByteArray) {
        exchange.sendResponseHeaders(200, payload.size.toLong())
        exchange.responseBody.use { output -> output.write(payload) }
    }

    private fun sendText(exchange: HttpExchange, text: String) {
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun parseRangeOffset(range: String?): Int {
        require(range != null) { "Range header is required" }
        require(range.startsWith("bytes=")) { "Unexpected Range header: $range" }
        return range.removePrefix("bytes=").substringBefore('-').toInt()
    }

    private fun payloadBytes(size: Int): ByteArray = ByteArray(size) { index -> (index * 31).toByte() }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private inline fun expectError(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("Expected an exception; none thrown")
        throw AssertionError("unreachable")
    }

    private fun <T> withChecksumTextReader(
        reader: (String, String) -> String,
        block: () -> T,
    ): T {
        val originalReader = checksumTextReader
        checksumTextReader = reader
        try {
            return block()
        } finally {
            checksumTextReader = originalReader
        }
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
