/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private val ideUnpackerLog = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeUnpacker")

/**
 * Dispatches the IDE archive at [archiveFile] to the appropriate unpacker based on
 * its file extension. Idempotent: if [unpackDir] already contains a child entry,
 * unpacking is skipped (cached install).
 *
 * Supported formats:
 *
 * | Extension | Strategy | Host requirement |
 * |---|---|---|
 * | `.tar.gz`, `.tgz` | streaming Apache Commons | any |
 * | `.zip` | streaming Apache Commons | any |
 * | `.dmg` | `hdiutil attach -readonly` + recursive copy | macOS only — produces a runnable IDE |
 * | `.exe` | `7z` from `PATH` | any — see [unpackExeWith7z] for the runtime requirement |
 *
 * Pure-Java DMG extraction (catacombae) is intentionally NOT used here because the
 * resulting `.app` loses symlinks / xattrs / code-sign metadata and cannot launch.
 * For introspection without launching, see [DmgIntrospection] (TODO).
 */
fun unpackIdeArchive(archiveFile: File, unpackDir: File) {
    require(archiveFile.exists()) { "Archive file does not exist: $archiveFile" }

    // Concurrency/partial-unpack guard: use an interprocess file lock and a
    // completion marker. Parallel Gradle invocations (e.g. `:ij-plugin:test`
    // and `:intellij-downloader:test`) targeting the same matrix entry will
    // serialize at the lock; a killed process that left a half-populated
    // tree never sees the marker on the next attempt, so we wipe + redo.
    withUnpackLock(unpackDir) {
        if (isUnpackCompleteFor(unpackDir, archiveFile)) {
            ideUnpackerLog.debug("[IDE-DOWNLOAD] Already unpacked (marker matches): {}", unpackDir)
            return@withUnpackLock
        }
        if (unpackDir.exists()) {
            // Either pristine empty dir, a partial unpack from a prior crash,
            // or a stale unpack from a re-published archive. The marker is
            // the only evidence we trust; wipe the rest.
            unpackDir.deleteRecursively()
        }
        unpackDir.mkdirs()

        warnIfLowFreeDiskSpace(unpackDir, archiveFile)

        val name = archiveFile.name.lowercase()
        when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> unpackTarGz(archiveFile, unpackDir)
            name.endsWith(".zip") -> unpackZip(archiveFile, unpackDir)
            name.endsWith(".dmg") -> unpackDmgViaMount(archiveFile, unpackDir)
            name.endsWith(".exe") -> unpackExeWith7z(archiveFile, unpackDir)
            else -> error(
                "Unsupported archive format: ${archiveFile.name}. " +
                    "Expected one of: .tar.gz, .tgz, .zip, .dmg, .exe"
            )
        }

        writeUnpackCompleteMarker(unpackDir, archiveFile)
    }
}

/**
 * Soft-fail early with a clear diagnostic when the filesystem hosting
 * [unpackDir] is close to running out of space. An IntelliJ IDE archive
 * unpacks to roughly 3 × its compressed size on disk (1.5 GB .tar.gz →
 * ~3 GB unpacked, plus the build/local-ides retention of every matrix
 * entry). When CI agents accumulate several majors + OS+arch entries
 * the disk fills silently — the unpack then aborts mid-stream with
 * `IOException: No space left on device`, which surfaces as a confusing
 * extract failure deep inside Apache Commons Compress.
 *
 * This is a WARN rather than a hard error: the conservative `4 × archiveSize`
 * estimate is slightly over-budget on tar.gz (compression ratio is closer
 * to 3 × on IntelliJ archives), so a borderline case still completes if
 * we let it try. The agent-side fix (clean `build/local-ides/`) is the same
 * either way; surfacing the warning earlier and louder beats a half-unpack
 * + cryptic IOException at extraction step 90 %.
 *
 * Closes audit #16 ("IDE archive disk usage. `build/local-ides/` now holds
 * full unpacked 261 + 262 IDEs (~3 GB each). CI runners may need a
 * free-disk gate").
 */
private fun warnIfLowFreeDiskSpace(unpackDir: File, archiveFile: File) {
    val targetVolume = unpackDir.parentFile ?: unpackDir
    val freeBytes = targetVolume.usableSpace
    if (freeBytes <= 0L) {
        // FileStore unavailable (rare on macOS / Linux for a real dir);
        // skip the check rather than guess.
        return
    }
    val needBytes = archiveFile.length() * 4L
    if (freeBytes < needBytes) {
        ideUnpackerLog.warn(
            "[IDE-DOWNLOAD] Low free disk on {}: {} GB free, ~{} GB likely needed to unpack {}. " +
                "Consider cleaning `build/local-ides/` if extraction fails — partial unpack will be " +
                "wiped on retry and re-attempted from scratch.",
            targetVolume,
            "%.2f".format(freeBytes / 1_000_000_000.0),
            "%.2f".format(needBytes / 1_000_000_000.0),
            archiveFile.name,
        )
    }
}

private const val UNPACK_COMPLETE_MARKER_NAME = ".mcp-steroid-unpack-complete"

/**
 * The marker records the archive identity (filename + size + last-modified)
 * the unpack came from. Successful re-entry requires the live archive on
 * disk to still match — if JetBrains re-publishes a build number with new
 * content, the archive size/mtime changes, the marker no longer matches,
 * and we re-unpack.
 *
 * Hashing the 1 GB archive on every script-eval would be too slow; size+mtime
 * is the same identity check `make` / `gradle` use for incremental builds.
 */
private fun isUnpackCompleteFor(unpackDir: File, archiveFile: File): Boolean {
    val marker = File(unpackDir, UNPACK_COMPLETE_MARKER_NAME)
    if (!marker.isFile) return false
    val recorded = marker.readText().lines()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()
    val live = archiveIdentity(archiveFile)
    return recorded["archive"] == live["archive"] &&
        recorded["archiveSize"] == live["archiveSize"] &&
        recorded["archiveModified"] == live["archiveModified"]
}

private fun writeUnpackCompleteMarker(unpackDir: File, archiveFile: File) {
    val identity = archiveIdentity(archiveFile)
    File(unpackDir, UNPACK_COMPLETE_MARKER_NAME).writeText(
        buildString {
            for ((k, v) in identity) appendLine("$k=$v")
            appendLine("completedAt=${java.time.Instant.now()}")
        }
    )
}

private fun archiveIdentity(archiveFile: File): Map<String, String> = linkedMapOf(
    "archive" to archiveFile.name,
    "archiveSize" to archiveFile.length().toString(),
    "archiveModified" to archiveFile.lastModified().toString(),
)

/**
 * Inter-JVM lock on [unpackDir]. Two parallel Gradle invocations against the
 * same IDE target will serialize here; one unpacks while the other waits,
 * and the second observes the completion marker and returns immediately.
 */
private inline fun <T> withUnpackLock(unpackDir: File, block: () -> T): T {
    val lockFile = File(unpackDir.parentFile, "${unpackDir.name}.lock")
    lockFile.parentFile?.mkdirs()
    java.io.RandomAccessFile(lockFile, "rw").use { raf ->
        raf.channel.use { channel ->
            val lock = channel.lock()
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    }
}

/**
 * Unpacks a `.tar.gz` archive into [unpackDir] using Apache Commons Compress.
 *
 * Idempotent: if [unpackDir] already contains a child directory, unpacking is skipped.
 */
fun unpackTarGz(archiveFile: File, unpackDir: File) {
    require(archiveFile.exists()) { "Archive file does not exist: $archiveFile" }

    val name = archiveFile.name.lowercase()
    require(name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
        "unpackTarGz called with non-tar.gz archive: ${archiveFile.name}"
    }

    if (unpackDirAlreadyPopulated(unpackDir)) return

    unpackDir.mkdirs()
    ideUnpackerLog.debug("[IDE-DOWNLOAD] Unpacking {} -> {}", archiveFile.name, unpackDir)

    var entryCount = 0
    var lastPrinted = System.currentTimeMillis()

    TarArchiveInputStream(
        GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile)))
    ).use { tar ->
        var entry = tar.nextEntry
        while (entry != null) {
            val outputFile = File(unpackDir, entry.name)
            requireUnderTarget(outputFile, unpackDir, entry.name)

            when {
                entry.isDirectory -> outputFile.mkdirs()

                entry.isSymbolicLink -> {
                    requireSymlinkTargetUnderTarget(outputFile, entry.linkName, unpackDir, entry.name)
                    outputFile.parentFile?.mkdirs()
                    outputFile.delete()
                    Files.createSymbolicLink(outputFile.toPath(), File(entry.linkName).toPath())
                }

                else -> {
                    outputFile.parentFile?.mkdirs()
                    outputFile.outputStream().use { out -> tar.copyTo(out) }
                    if (entry.mode and 0b001_000_000 != 0) {
                        outputFile.setExecutable(true, false)
                    }
                }
            }

            entryCount++
            val now = System.currentTimeMillis()
            if (now - lastPrinted >= 5_000) {
                ideUnpackerLog.debug("[IDE-DOWNLOAD] Unpacking: {} entries extracted...", entryCount)
                lastPrinted = now
            }

            entry = tar.nextEntry
        }
    }

    ideUnpackerLog.debug("[IDE-DOWNLOAD] Unpacked {} entries to {}", entryCount, unpackDir)
}

/**
 * Unpacks a `.zip` archive (e.g. `windowsZip` variant) into [unpackDir].
 *
 * Idempotent: if [unpackDir] already contains a child entry, unpacking is skipped.
 * Preserves the executable bit when the entry's Unix mode carries it (Windows zips
 * rarely set the mode, but Linux-generated zips do).
 */
fun unpackZip(archiveFile: File, unpackDir: File) {
    require(archiveFile.exists()) { "Archive file does not exist: $archiveFile" }
    require(archiveFile.name.lowercase().endsWith(".zip")) {
        "unpackZip called with non-zip archive: ${archiveFile.name}"
    }

    if (unpackDirAlreadyPopulated(unpackDir)) return

    unpackDir.mkdirs()
    ideUnpackerLog.debug("[IDE-DOWNLOAD] Unpacking {} -> {}", archiveFile.name, unpackDir)

    var entryCount = 0
    var lastPrinted = System.currentTimeMillis()

    ZipArchiveInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val outputFile = File(unpackDir, entry.name)
            requireUnderTarget(outputFile, unpackDir, entry.name)

            if (entry.isDirectory) {
                outputFile.mkdirs()
            } else {
                outputFile.parentFile?.mkdirs()
                outputFile.outputStream().use { out -> zip.copyTo(out) }
                if (entry.unixMode and 0b001_000_000 != 0) {
                    outputFile.setExecutable(true, false)
                }
            }

            entryCount++
            val now = System.currentTimeMillis()
            if (now - lastPrinted >= 5_000) {
                ideUnpackerLog.debug("[IDE-DOWNLOAD] Unpacking: {} entries extracted...", entryCount)
                lastPrinted = now
            }

            entry = zip.nextEntry
        }
    }

    ideUnpackerLog.debug("[IDE-DOWNLOAD] Unpacked {} entries to {}", entryCount, unpackDir)
}

/**
 * Mounts a `.dmg` read-only via macOS `hdiutil`, copies the `.app` (or `Contents/`)
 * into [unpackDir], then detaches the image. Mount-and-copy is the only path that
 * yields a *runnable* IDE — pure-Java DMG extractors lose code signatures / xattrs.
 *
 * Requires the host to be macOS (`hdiutil` is part of the OS). On Linux / Windows
 * hosts, fail clearly so the caller switches strategies (download `.tar.gz` /
 * `.win.zip` instead, or use a library-based introspection helper).
 *
 * Ported from `~/Work/intellij/toolbox/feature/package-extractors/MacOsDmgExtractor.kt`.
 */
fun unpackDmgViaMount(archiveFile: File, unpackDir: File) {
    require(archiveFile.exists()) { "Archive file does not exist: $archiveFile" }
    require(archiveFile.name.lowercase().endsWith(".dmg")) {
        "unpackDmgViaMount called with non-dmg archive: ${archiveFile.name}"
    }

    val hostOs = resolveHostOs()
    require(hostOs == HostOs.MAC) {
        "DMG mount-and-copy requires a macOS host (got $hostOs). " +
            "On other hosts download the .tar.gz / .win.zip variant instead, " +
            "or use a library-based introspection helper if you only need to read metadata."
    }

    if (unpackDirAlreadyPopulated(unpackDir)) return

    unpackDir.mkdirs()

    val hdiutil = File("/usr/bin/hdiutil").takeIf { it.canExecute() }?.absolutePath ?: "hdiutil"
    val mountPoint = Files.createTempDirectory("ide-downloader-dmg-${archiveFile.nameWithoutExtension}-").toFile()

    ideUnpackerLog.debug("[IDE-DOWNLOAD] Mounting {} at {}", archiveFile.name, mountPoint)
    try {
        runOrThrow(
            listOf(
                hdiutil, "attach",
                "-readonly", "-noautoopen", "-noautofsck", "-noverify", "-nobrowse",
                "-mountpoint", mountPoint.absolutePath,
                archiveFile.absolutePath,
            ),
            timeoutMinutes = 5,
        )

        val sourceDir = resolveDmgPayloadDir(mountPoint)
        ideUnpackerLog.debug("[IDE-DOWNLOAD] Copying {} -> {}", sourceDir, unpackDir)
        // Use `cp -R` (or ditto) so symlinks / extended attributes survive — Java's
        // Files.copy doesn't preserve xattrs which matters for code-signed .app bundles.
        val copySource = if (sourceDir.name.endsWith(".app")) sourceDir.absolutePath else "${sourceDir.absolutePath}/."
        runOrThrow(
            listOf("/bin/cp", "-R", copySource, unpackDir.absolutePath),
            timeoutMinutes = 10,
        )
        ideUnpackerLog.debug("[IDE-DOWNLOAD] Unpacked DMG into {}", unpackDir)
    } finally {
        try {
            runOrThrow(
                listOf(hdiutil, "detach", "-force", mountPoint.absolutePath),
                timeoutMinutes = 2,
                allowedExitCodes = setOf(0, 1), // detach can race; -force returns 1 when already gone
            )
        } catch (e: Exception) {
            ideUnpackerLog.debug("[IDE-DOWNLOAD] WARN: failed to detach {}: {}", mountPoint, e.message)
        }
        // Never recursively delete a still-mounted dir. After detach the temp dir is empty.
        mountPoint.delete()
    }
}

/**
 * Picks the directory inside a mounted DMG that should be copied to the install dir.
 * Mirrors the logic in toolbox's `MacOsDmgExtractor.resolveDirToCopy`:
 *  - one entry named `Contents` → copy its parent's contents
 *  - one entry ending in `.app` → that's our payload
 *  - otherwise → fall back to the whole mount root
 */
private fun resolveDmgPayloadDir(mountPoint: File): File {
    val entries = mountPoint.listFiles()?.toList().orEmpty()
    if (entries.isEmpty()) return mountPoint
    if (entries.size == 1 && entries.single().name == "Contents" && entries.single().isDirectory) {
        return entries.single()
    }
    return entries.firstOrNull { it.name.endsWith(".app") } ?: mountPoint
}

/**
 * Extracts a Windows `.exe` IDE installer (NSIS-packaged) using `7zz` / `7z` located
 * via [SevenZipLocator]. The resulting directory is a runnable Windows IDE install
 * (NSIS bundles flat files; 7zip extracts them verbatim).
 *
 * The bundled `7z` binary is used automatically when the devrig distribution ships one
 * for the host. Otherwise the locator falls back to `7z` / `7za` on `PATH`; if neither
 * is available a clear error is raised.
 */
fun unpackExeWith7z(archiveFile: File, unpackDir: File) {
    require(archiveFile.exists()) { "Archive file does not exist: $archiveFile" }
    require(archiveFile.name.lowercase().endsWith(".exe")) {
        "unpackExeWith7z called with non-exe archive: ${archiveFile.name}"
    }

    if (unpackDirAlreadyPopulated(unpackDir)) return

    val sevenZip = SevenZipLocator.locate() ?: error(
        "No 7z binary available to extract Windows installer ${archiveFile.name}. " +
            "On Linux/Mac the bundled 7zz binary is expected; " +
            "on Windows install 7-Zip or use the devrig distribution with bundled 7z."
    )

    // NSIS extracts files flat — wrap them in a per-archive subdirectory so the
    // installed layout matches Linux (tar.gz) and macOS (.app from .dmg), both of
    // which produce a single wrapping directory under the backend dir.
    val bundleDir = File(unpackDir, archiveFile.nameWithoutExtension)
    bundleDir.mkdirs()
    ideUnpackerLog.debug("[IDE-DOWNLOAD] Extracting {} with {} -> {}", archiveFile.name, sevenZip, bundleDir)
    runOrThrow(
        listOf(sevenZip, "x", "-y", "-o${bundleDir.absolutePath}", archiveFile.absolutePath),
        timeoutMinutes = 10,
    )

    // NSIS stub leaves a $PLUGINSDIR with installer scratch; remove it to match toolbox's
    // Windows7zExtractor.kt — it contains nothing needed at runtime.
    val nsisScratch = File(bundleDir, "\$PLUGINSDIR")
    if (nsisScratch.isDirectory) nsisScratch.deleteRecursively()

    ideUnpackerLog.debug("[IDE-DOWNLOAD] Unpacked .exe into {}", bundleDir)
}

private fun unpackDirAlreadyPopulated(unpackDir: File): Boolean {
    val existing = unpackDir.listFiles()?.firstOrNull { it.isDirectory || it.length() > 0 }
    if (existing != null) {
        ideUnpackerLog.debug("[IDE-DOWNLOAD] Already unpacked: {}", existing)
        return true
    }
    return false
}

private fun requireUnderTarget(outputFile: File, unpackDir: File, entryName: String) {
    require(!File(entryName).isAbsolute) {
        "Archive entry escapes target directory: $entryName"
    }
    val outputCanonical = outputFile.canonicalPath
    val unpackCanonical = unpackDir.canonicalPath
    val unpackPrefix = if (unpackCanonical.endsWith(File.separator)) {
        unpackCanonical
    } else {
        unpackCanonical + File.separator
    }
    require(outputCanonical == unpackCanonical || outputCanonical.startsWith(unpackPrefix)) {
        "Archive entry escapes target directory: $entryName"
    }
}

private fun requireSymlinkTargetUnderTarget(
    linkLocation: File,
    linkName: String,
    unpackDir: File,
    entryName: String,
) {
    val unpackRoot = unpackDir.toPath().toRealPath()
    val unpackAbsolute = unpackDir.toPath().toAbsolutePath().normalize()
    val linkParent = linkLocation.parentFile.toPath().toAbsolutePath().normalize()
    require(linkParent == unpackAbsolute || linkParent.startsWith(unpackAbsolute)) {
        "Archive symlink location escapes target directory: $entryName"
    }
    val resolved = unpackRoot
        .resolve(unpackAbsolute.relativize(linkParent))
        .resolve(linkName)
        .normalize()
    require(resolved.startsWith(unpackRoot)) {
        "Archive symlink escapes target directory: $entryName -> $linkName (resolves to $resolved)"
    }
}

private fun runOrThrow(
    command: List<String>,
    timeoutMinutes: Long,
    allowedExitCodes: Set<Int> = setOf(0),
) {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    process.outputStream.close()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
    if (!finished) {
        process.destroyForcibly()
        error("Command timed out after $timeoutMinutes min: ${command.joinToString(" ")}\nOutput so far:\n$output")
    }
    if (process.exitValue() !in allowedExitCodes) {
        error("Command ${command.joinToString(" ")} exited ${process.exitValue()}\nOutput:\n$output")
    }
}
