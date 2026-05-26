/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Bundled Windows 7-Zip files are preferred when present. PATH lookup covers
// Linux, macOS, and the Windows fallback. The bundled 7-Zip 23.01 `7z.dll`
// contains NSIS and LZMA2 support; no separate plugin files are shipped.
object SevenZipLocator {
    private data class BundledPayload(
        val primaryName: String,
        val fileNames: List<String>,
        val executableNames: Set<String>,
    )

    private data class CacheTarget(
        val dir: File,
        val binary: File,
        val isComplete: Boolean,
    )

    private val windowsPayload = BundledPayload(
        primaryName = "7z.exe",
        fileNames = listOf("7z.exe", "7z.dll", "License.txt"),
        executableNames = setOf("7z.exe"),
    )

    // For compatibility with unpacked devrig trees and existing tests, current builds
    // no longer ship this payload; Linux/macOS hosts use PATH unless such a legacy tree
    // is supplied explicitly by the embedding distribution.
    private val legacyUnixPayload = BundledPayload(
        primaryName = "7zz",
        fileNames = listOf("7zz", "License.txt"),
        executableNames = setOf("7zz"),
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
     * Returns an absolute path to a runnable `7z` executable that supports NSIS, or `null`
     * when no candidate is available on this host (the caller surfaces a clear error).
     */
    fun locate(
        os: HostOs = resolveHostOs(),
        architecture: HostArchitecture = resolveHostArchitecture(),
    ): String? {
        // 1) Bundled binaries are preferred when present.
        extractBundled(os, architecture)?.let { return it.absolutePath }

        // 2) PATH lookup — primary on Linux/macOS and fallback on Windows.
        return locateOnPath(os)
    }

    private fun extractBundled(os: HostOs, architecture: HostArchitecture): File? {
        val sevenZipDir = devrigSevenZipDirOrNull()
        if (sevenZipDir != null) {
            val source = bundledFilePath(sevenZipDir, os, architecture)
            if (source != null) {
                copyBundledFileToCache(source, payloadForPrimary(source.fileName.toString()))?.let { return it }
            }
        }

        return extractBundledResource(os, architecture)
    }

    private fun copyBundledFileToCache(source: Path, payload: BundledPayload): File? {
        if (!Files.isRegularFile(source)) return null
        val sourceDir = source.parent ?: return null
        val sourceFiles = payload.fileNames.associateWith { fileName ->
            sourceDir.resolve(fileName).takeIf { Files.isRegularFile(it) } ?: return null
        }
        val primaryBytes = Files.readAllBytes(sourceFiles.getValue(payload.primaryName))
        val target = cacheTarget(payload, primaryBytes)
        if (target.isComplete) {
            return target.binary
        }

        target.dir.mkdirs()
        sourceFiles.forEach { (fileName, sourceFile) ->
            val targetFile = target.dir.toPath().resolve(fileName)
            val tmpFile = Files.createTempFile(target.dir.toPath(), fileName, ".tmp")
            try {
                Files.copy(sourceFile, tmpFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
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

    private fun extractBundledResource(os: HostOs, architecture: HostArchitecture): File? {
        val resourcePath = bundledResourcePath(os, architecture) ?: return null
        val resourceDir = resourcePath.substringBeforeLast('/')
        val classLoader = SevenZipLocator::class.java.classLoader
        val resourceBytes = windowsPayload.fileNames.associateWith { fileName ->
            classLoader.getResourceAsStream("$resourceDir/$fileName")?.use { it.readBytes() } ?: return null
        }

        val primaryBytes = resourceBytes.getValue(windowsPayload.primaryName)
        val target = cacheTarget(windowsPayload, primaryBytes)
        if (target.isComplete) {
            return target.binary
        }

        target.dir.mkdirs()
        resourceBytes.forEach { (fileName, bytes) ->
            val targetFile = target.dir.toPath().resolve(fileName)
            val tmpFile = Files.createTempFile(target.dir.toPath(), fileName, ".tmp")
            try {
                Files.write(tmpFile, bytes)
                Files.move(
                    tmpFile,
                    targetFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                if (fileName in windowsPayload.executableNames) {
                    targetFile.toFile().setExecutable(true, false)
                }
            } catch (e: Exception) {
                deleteTempFileOnFailure(tmpFile, e)
                throw e
            }
        }

        return target.binary
    }

    private fun cacheTarget(payload: BundledPayload, primaryBytes: ByteArray): CacheTarget {
        val digest = sha256(primaryBytes).take(16) // 16 hex chars is enough to namespace cached copies
        val targetDir = File(cacheRoot, digest)
        return CacheTarget(
            dir = targetDir,
            binary = File(targetDir, payload.primaryName),
            isComplete = isCompleteCachedPayload(targetDir, payload, primaryBytes.size),
        )
    }

    private fun deleteTempFileOnFailure(tmpFile: Path, cause: Exception) {
        try {
            Files.deleteIfExists(tmpFile)
        } catch (deleteError: Exception) {
            cause.addSuppressed(deleteError)
        }
    }

    private fun isCompleteCachedPayload(targetDir: File, payload: BundledPayload, primarySize: Int): Boolean {
        val targetBinary = File(targetDir, payload.primaryName)
        if (!targetBinary.isFile || targetBinary.length() != primarySize.toLong()) return false
        if (payload.primaryName in payload.executableNames && !targetBinary.canExecute()) return false
        return payload.fileNames.all { fileName -> File(targetDir, fileName).isFile }
    }

    private fun bundledFilePath(sevenZipDir: Path, os: HostOs, architecture: HostArchitecture): Path? = when (os) {
        HostOs.LINUX -> sevenZipDir.resolve(if (architecture.isArmArch) "linux-arm64/7zz" else "linux-x64/7zz")
        HostOs.MAC -> sevenZipDir.resolve("mac/7zz")
        HostOs.WINDOWS -> sevenZipDir.resolve("win-x64/7z.exe")
    }

    private fun payloadForPrimary(primaryName: String): BundledPayload = when (primaryName) {
        windowsPayload.primaryName -> windowsPayload
        legacyUnixPayload.primaryName -> legacyUnixPayload
        else -> BundledPayload(primaryName, listOf(primaryName), setOf(primaryName))
    }

    private fun bundledResourcePath(os: HostOs, architecture: HostArchitecture): String? = when (os) {
        HostOs.WINDOWS -> "7z/win-x64/7z.exe"
        HostOs.LINUX,
        HostOs.MAC -> null
    }

    private fun devrigSevenZipDirOrNull(): Path? {
        val rootClass = try {
            Class.forName("com.jonnyzzz.mcpSteroid.devrig.DevrigRoot")
        } catch (e: ClassNotFoundException) {
            return null
        }

        val instance = rootClass.getField("INSTANCE").get(null)
        return try {
            rootClass.getMethod("sevenZipDir").invoke(instance) as Path
        } catch (e: InvocationTargetException) {
            when (val cause = e.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Cannot resolve devrig 7z directory", cause)
            }
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("Cannot invoke com.jonnyzzz.mcpSteroid.devrig.DevrigRoot.sevenZipDir()", e)
        }
    }

    private fun locateOnPath(os: HostOs): String? {
        val path = System.getenv("PATH") ?: return null
        val sep = if (os == HostOs.WINDOWS) ';' else ':'
        val candidates = if (os == HostOs.WINDOWS) listOf("7z.exe", "7za.exe", "7zz.exe") else listOf("7zz", "7z", "7za")
        for (dir in path.split(sep)) {
            for (name in candidates) {
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
