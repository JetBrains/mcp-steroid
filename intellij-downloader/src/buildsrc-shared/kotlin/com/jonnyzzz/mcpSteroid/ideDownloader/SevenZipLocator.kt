/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Locates the bundled 7-Zip executable on Windows.
 *
 * **Bundled-only, no host fallback.** The bundled `7z/win-x64/{7z.exe,7z.dll,License.txt}`
 * tuple is shipped inside the classpath JAR and extracted to a per-user cache directory
 * on first use. Any other platform — or a Windows host where the bundle is missing from
 * the classpath — returns `null`; callers surface a clear error.
 *
 * NSIS unpacking is only ever needed at runtime on Windows targets (Linux/macOS IDE
 * distributions are handled in pure Java by IdeUnpacker), so the locator is intentionally
 * Windows-only. The host's PATH is never consulted.
 */
/**
 * JVM system property set by `gradle/seven-zip-bootstrap.settings.gradle.kts`
 * (Windows hosts only). Points at a directory containing `win-x64/7z.exe`
 * + `win-x64/7z.dll` + `win-x64/License.txt`. Read at config phase before
 * the classpath-resource fallback.
 */
private const val SEVEN_ZIP_BUNDLE_DIR_PROPERTY: String = "mcp.intellij-downloader.sevenZipBundleDir"

object SevenZipLocator {
    private val payload = BundledPayload(
        primaryName = "7z.exe",
        fileNames = listOf("7z.exe", "7z.dll", "License.txt"),
        executableNames = setOf("7z.exe"),
    )

    @Volatile
    internal var cacheRootOverride: File? = null

    internal val cacheRoot: File
        get() = cacheRootOverride ?: defaultCacheRoot

    private val defaultCacheRoot: File by lazy {
        val home = System.getProperty("user.home") ?: error("user.home is not set")
        File(home, ".cache/mcp-steroid/7z").apply { mkdirs() }
    }

    /**
     * Returns an absolute path to the cached `7z.exe`, or `null` on non-Windows hosts
     * or when the bundled resources are absent from the classpath.
     */
    fun locate(
        os: HostOs = resolveHostOs(),
        @Suppress("UNUSED_PARAMETER") architecture: HostArchitecture = resolveHostArchitecture(),
    ): String? {
        if (os != HostOs.WINDOWS) return null
        return locateFromSystemProperty() ?: extractBundledResource()?.absolutePath
    }

    // Settings-phase bootstrap (gradle/seven-zip-bootstrap.settings.gradle.kts on
    // Windows hosts) sets the system property to a directory containing the
    // win-x64 payload. Read at Gradle CONFIG PHASE — earlier than the
    // classpath-resource fallback, which sees nothing on the buildSrc classloader.
    private fun locateFromSystemProperty(): String? {
        val bundleDir = System.getProperty(SEVEN_ZIP_BUNDLE_DIR_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
        val binary = File(bundleDir, "win-x64/${payload.primaryName}")
        return if (binary.isFile) binary.absolutePath else null
    }

    private fun extractBundledResource(): File? {
        val classLoader = SevenZipLocator::class.java.classLoader
        val resourceBytes = payload.fileNames.associateWith { fileName ->
            classLoader.getResourceAsStream("7z/win-x64/$fileName")?.use { it.readBytes() } ?: return null
        }

        val primaryBytes = resourceBytes.getValue(payload.primaryName)
        val target = cacheTarget(primaryBytes)
        if (target.isComplete) return target.binary

        target.dir.mkdirs()
        resourceBytes.forEach { (fileName, bytes) ->
            val targetFile = target.dir.toPath().resolve(fileName)
            val tmpFile = Files.createTempFile(target.dir.toPath(), fileName, ".tmp")
            try {
                Files.write(tmpFile, bytes)
                Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                if (fileName in payload.executableNames) {
                    targetFile.toFile().setExecutable(true, false)
                }
            } catch (e: Exception) {
                deleteTempFileOnFailure(tmpFile, e)
                throw e
            }
        }

        return target.binary
    }

    private data class BundledPayload(
        val primaryName: String,
        val fileNames: List<String>,
        val executableNames: Set<String>,
    )

    private data class CacheTarget(val dir: File, val binary: File, val isComplete: Boolean)

    private fun cacheTarget(primaryBytes: ByteArray): CacheTarget {
        val digest = sha256(primaryBytes).take(16)
        val targetDir = File(cacheRoot, digest)
        return CacheTarget(
            dir = targetDir,
            binary = File(targetDir, payload.primaryName),
            isComplete = isCompleteCachedPayload(targetDir, primaryBytes.size),
        )
    }

    private fun isCompleteCachedPayload(targetDir: File, primarySize: Int): Boolean {
        val targetBinary = File(targetDir, payload.primaryName)
        if (!targetBinary.isFile || targetBinary.length() != primarySize.toLong()) return false
        if (!targetBinary.canExecute()) return false
        return payload.fileNames.all { fileName -> File(targetDir, fileName).isFile }
    }

    private fun deleteTempFileOnFailure(tmpFile: Path, cause: Exception) {
        try {
            Files.deleteIfExists(tmpFile)
        } catch (deleteError: Exception) {
            cause.addSuppressed(deleteError)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
