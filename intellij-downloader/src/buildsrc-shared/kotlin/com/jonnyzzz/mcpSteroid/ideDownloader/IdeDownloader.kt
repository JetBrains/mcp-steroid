/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.slf4j.LoggerFactory
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val ideDownloaderLog = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloader")
private const val FSYNC_EVERY_BYTES = 8L * 1024L * 1024L
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private val archiveDownloadLocks = ConcurrentHashMap<String, Any>()

internal var checksumTextReader: (String, String) -> String = ::readUrlText

private data class ResolvedArchiveDownload(
    val url: String,
    val fileName: String,
    val checksumUrl: String?,
    val expectedChecksum: String?,
)

/**
 * Resolves the download URL and file name for an [IdeDistribution],
 * then downloads the archive (with caching) to the specified directory.
 *
 * @param downloadDir the directory to store downloaded archives
 * @param os the target operating system (default: auto-detected)
 * @return the local file containing the IDE archive
 */
fun IdeDistribution.resolveAndDownload(
    downloadDir: File,
    os: HostOs = resolveHostOs(),
): File {
    downloadDir.mkdirs()

    val resolved = resolveArchiveDownload(os)
    // Resolve the expected checksum BEFORE naming the file: the cache filename mixes the URL and the
    // checksum so two distinct binaries that share a URL/filename never collide on disk.
    val expectedChecksum = resolveExpectedChecksum(resolved)
    val destFile = File(downloadDir, hashedArchiveFileName(resolved.url, expectedChecksum, resolved.fileName))
    ideDownloaderLog.info(
        "[IDE-DOWNLOAD] Resolved archive: {} -> {}",
        resolved.url,
        destFile,
    )
    return synchronized(archiveDownloadLock(destFile)) {
        resolveAndDownloadLocked(resolved, expectedChecksum, destFile)
    }
}

private fun resolveAndDownloadLocked(
    resolved: ResolvedArchiveDownload,
    expectedChecksum: String?,
    destFile: File,
): File {
    if (expectedChecksum == null) {
        ideDownloaderLog.warn(
            "[IDE-DOWNLOAD] No SHA-256 checksum available for {}; archive will not be verified",
            resolved.url,
        )
    }

    if (destFile.exists()) {
        if (expectedChecksum == null) {
            ideDownloaderLog.debug("[IDE-DOWNLOAD] Using cached archive: {}", destFile)
            return destFile
        }

        val actualSha256 = sha256(destFile)
        if (actualSha256 == expectedChecksum) {
            ideDownloaderLog.debug("[IDE-DOWNLOAD] Using verified cached archive: {}", destFile)
            return destFile
        }

        Files.deleteIfExists(destFile.toPath())
        ideDownloaderLog.warn(
            "[IDE-DOWNLOAD] Cached archive checksum mismatch for {}; expected {}, actual {}; re-downloading",
            destFile,
            expectedChecksum,
            actualSha256,
        )
    }

    ideDownloaderLog.debug("[IDE-DOWNLOAD] Downloading {} -> {}", resolved.url, destFile)
    downloadFile(resolved.url, destFile)

    if (expectedChecksum != null) {
        val actualSha256 = sha256(destFile)
        if (actualSha256 != expectedChecksum) {
            Files.deleteIfExists(destFile.toPath())
            error(
                "SHA-256 mismatch for ${resolved.url}: expected $expectedChecksum, actual $actualSha256. " +
                    "Deleted corrupted archive $destFile"
            )
        }
        ideDownloaderLog.debug("[IDE-DOWNLOAD] Verified SHA-256 {} for {}", expectedChecksum, destFile)
    }

    return destFile
}

private fun archiveDownloadLock(destFile: File): Any {
    val key = destFile.toPath().toAbsolutePath().normalize().toString()
    return archiveDownloadLocks.computeIfAbsent(key) { Any() }
}

private fun IdeDistribution.resolveArchiveDownload(
    os: HostOs,
): ResolvedArchiveDownload {
    return when (this) {
        is IdeDistribution.FromUrl -> {
            check(checksumUrl == null || expectedSha256 == null) {
                "checksumUrl and expectedSha256 are mutually exclusive for $url"
            }
            val resolvedName = fileName ?: archiveFileNameFromUrl(url, "${product.id}.tar.gz")
            ResolvedArchiveDownload(url, resolvedName, checksumUrl, expectedSha256)
        }
        is IdeDistribution.Latest -> {
            val resolved = resolveArchive(product, channel, os, version = version)
            val arch = resolveHostArchitecture()
            val fallbackName = if (arch.isArmArch) "${product.id}-${channel.name.lowercase()}-arm.tar.gz"
                               else "${product.id}-${channel.name.lowercase()}-x86.tar.gz"
            val resolvedName = archiveFileNameFromUrl(resolved.url, fallbackName)
            ResolvedArchiveDownload(resolved.url, resolvedName, resolved.checksumUrl, resolved.expectedSha256)
        }
    }
}

private fun resolveExpectedChecksum(resolved: ResolvedArchiveDownload): String? {
    resolved.expectedChecksum?.let { return normalizeSha256(it, "inline checksum for ${resolved.url}") }
    resolved.checksumUrl?.let { checksumUrl ->
        val checksumText = fetchChecksumWithRetry(checksumUrl)
        return parseSha256Checksum(checksumText, checksumUrl)
    }
    return null
}

internal fun fetchChecksumWithRetry(
    checksumUrl: String,
    attempts: Int = 3,
    backoffMs: LongArray = longArrayOf(500L, 2_000L, 8_000L),
): String {
    require(attempts >= 1) { "attempts must be >= 1" }
    var lastError: IOException? = null
    for (attempt in 0 until attempts) {
        try {
            return checksumTextReader(checksumUrl, "text/plain,*/*")
        } catch (e: IOException) {
            lastError = e
            ideDownloaderLog.warn(
                "[IDE-DOWNLOAD] Checksum fetch attempt {}/{} failed for {}: {}",
                attempt + 1,
                attempts,
                checksumUrl,
                e.message,
            )
            if (attempt < attempts - 1) {
                val sleepMs = if (backoffMs.isEmpty()) 0L else backoffMs[attempt.coerceAtMost(backoffMs.lastIndex)]
                try {
                    Thread.sleep(sleepMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting to retry SHA-256 checksum fetch from $checksumUrl", interrupted)
                }
            }
        }
    }
    throw IOException("Failed to fetch SHA-256 checksum from $checksumUrl after $attempts attempts", lastError)
}

internal fun parseSha256Checksum(text: String, sourceUrl: String): String {
    val firstToken = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?: error("SHA-256 checksum response is empty: $sourceUrl")
    return normalizeSha256(firstToken, sourceUrl)
}

private fun normalizeSha256(value: String, source: String): String {
    val normalized = value.trim().lowercase()
    if (!Regex("[0-9a-f]{64}").matches(normalized)) {
        error("Invalid SHA-256 checksum from $source: '$value'")
    }
    return normalized
}

private const val ARCHIVE_HASH_BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

/**
 * Cache file name for an archive: `<16-char hash>-<fileName>`. The hash disambiguates editions that share
 * a download filename — notably IntelliJ **Community** (GitHub `idea-<version>.tar.gz`) vs **Ultimate**
 * (data.services `idea-<version>.tar.gz`), which are byte-different but identically named. Without the
 * prefix, the first one cached wins and the other unpacks the wrong product (IC vs IU).
 *
 * The hash mixes the download URL AND the expected SHA-256 of the binary (when known), so two distinct
 * binaries never collide even if both URL and filename matched. It's base62 (alphanumeric) of
 * SHA-256(url + checksum), first 16 chars — same convention as the project-name hash in the devrig tools.
 */
internal fun hashedArchiveFileName(url: String, expectedChecksum: String?, fileName: String): String =
    "${archiveHash16(url, expectedChecksum)}-$fileName"

internal fun archiveHash16(url: String, expectedChecksum: String?): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(url.toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update((expectedChecksum ?: "").toByteArray(Charsets.UTF_8))
    var value = java.math.BigInteger(1, md.digest())
    val base = java.math.BigInteger.valueOf(62L)
    val sb = StringBuilder(16)
    repeat(16) {
        val (q, r) = value.divideAndRemainder(base)
        sb.append(ARCHIVE_HASH_BASE62[r.toInt()])
        value = q
    }
    return sb.toString()
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun archiveFileNameFromUrl(url: String, fallbackFileName: String): String {
    val fileName = try {
        URI(url).path.substringAfterLast('/')
    } catch (e: Exception) {
        ideDownloaderLog.warn("[IDE-DOWNLOAD] Failed to parse archive file name from URL {}", url, e)
        null
    }
    return fileName?.takeIf { it.isNotBlank() } ?: fallbackFileName
}

internal fun downloadFile(url: String, dest: File) {
    val parentDir = dest.parentFile ?: File(".")
    val tempFile = File(parentDir, "${dest.name}.tmp")
    var resumeOffset = if (tempFile.isFile) tempFile.length() else 0L

    while (true) {
        val connection = openDownloadConnection(url, resumeOffset.takeIf { it > 0L })
        try {
            val statusCode = connection.responseCode
            if (resumeOffset > 0L && statusCode == HTTP_RANGE_NOT_SATISFIABLE) {
                ideDownloaderLog.debug(
                    "[IDE-DOWNLOAD] Server rejected resume offset {} for {}; restarting download",
                    resumeOffset,
                    url,
                )
                Files.deleteIfExists(tempFile.toPath())
                resumeOffset = 0L
                continue
            }
            if (statusCode !in 200..299) {
                error("Failed to download $url. HTTP $statusCode")
            }

            val append = resumeOffset > 0L && statusCode == HttpURLConnection.HTTP_PARTIAL
            if (resumeOffset > 0L && statusCode == HttpURLConnection.HTTP_OK) {
                ideDownloaderLog.debug(
                    "[IDE-DOWNLOAD] Server ignored Range for {}; discarding {} resume bytes",
                    url,
                    resumeOffset,
                )
                Files.deleteIfExists(tempFile.toPath())
                resumeOffset = 0L
            } else if (resumeOffset > 0L && !append) {
                error("Failed to resume $url from byte $resumeOffset. HTTP $statusCode")
            }

            val totalBytes = when {
                append && connection.contentLengthLong > 0L -> resumeOffset + connection.contentLengthLong
                else -> connection.contentLengthLong
            }
            val bytesReadThisResponse = writeDownloadResponse(
                connection = connection,
                tempFile = tempFile,
                append = append,
                existingBytes = if (append) resumeOffset else 0L,
                totalBytes = totalBytes,
                displayName = dest.name,
            )
            val expectedBytesThisResponse = connection.contentLengthLong
            if (expectedBytesThisResponse >= 0L && bytesReadThisResponse != expectedBytesThisResponse) {
                throw EOFException(
                    "Incomplete download from $url: read $bytesReadThisResponse of $expectedBytesThisResponse bytes"
                )
            }

            moveDownloadedFile(tempFile, dest)
            val downloadedBytes = if (append) resumeOffset + bytesReadThisResponse else bytesReadThisResponse
            ideDownloaderLog.debug("[IDE-DOWNLOAD] Downloaded {} MB to {}", downloadedBytes / 1024 / 1024, dest)
            return
        } finally {
            connection.disconnect()
        }
    }
}

private fun openDownloadConnection(url: String, resumeOffset: Long?): HttpURLConnection {
    return (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 30_000
        readTimeout = 15 * 60_000
        instanceFollowRedirects = true
        if (resumeOffset != null) {
            setRequestProperty("Range", "bytes=$resumeOffset-")
        }
    }
}

private fun writeDownloadResponse(
    connection: HttpURLConnection,
    tempFile: File,
    append: Boolean,
    existingBytes: Long,
    totalBytes: Long,
    displayName: String,
): Long {
    var bytesReadThisResponse = 0L
    var bytesSinceForce = 0L
    var lastPrinted = 0L

    connection.inputStream.use { input ->
        FileOutputStream(tempFile, append).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                bytesReadThisResponse += read
                bytesSinceForce += read
                if (bytesSinceForce >= FSYNC_EVERY_BYTES) {
                    output.flush()
                    output.fd.sync()
                    bytesSinceForce = 0L
                }

                val now = System.currentTimeMillis()
                if (now - lastPrinted >= 5_000) {
                    val downloaded = existingBytes + bytesReadThisResponse
                    val progress = if (totalBytes > 0) " (${downloaded * 100 / totalBytes}%)" else ""
                    val progressLine = "Downloading $displayName: ${downloaded / 1024 / 1024} MB$progress"
                    // User-facing download progress: a clean line — no logback severity/category and no
                    // "[IDE-DOWNLOAD]" prefix. MUST go to stderr, never stdout: this can run inside
                    // `devrig mpc`, where stdout is the JSON-RPC channel and a stray byte corrupts the MCP
                    // protocol.
                    System.err.println(progressLine)
                    // Also record it in the log file every ~5s, so a log monitor (e.g. a test tailing the
                    // devrig log) shows live download progress even when stderr is buffered by the caller
                    // (the agent's Bash tool only surfaces a command's output once it finishes).
                    ideDownloaderLog.info(progressLine)
                    lastPrinted = now
                }
            }
            output.flush()
            output.fd.sync()
        }
    }

    return bytesReadThisResponse
}

private fun moveDownloadedFile(tempFile: File, dest: File) {
    try {
        Files.move(
            tempFile.toPath(),
            dest.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (e: AtomicMoveNotSupportedException) {
        ideDownloaderLog.debug(
            "[IDE-DOWNLOAD] Atomic archive move is not supported for {} -> {}; falling back to replace",
            tempFile,
            dest,
            e,
        )
        Files.move(
            tempFile.toPath(),
            dest.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
