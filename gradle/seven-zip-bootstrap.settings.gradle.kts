// Settings-phase materialization of the bundled 7-Zip Windows binaries.
//
// Why settings-phase: LocalIdeProvisioner.unpackIdeArchive(.exe) runs inside IPGP's
// `local(provider)` callback, which IPGP resolves at Gradle CONFIG PHASE — before
// any task body executes. The daemon JVM loads SevenZipLocator via the buildSrc
// classloader, which does NOT carry the `7z/win-x64/*` resources packaged inside
// `:intellij-downloader`'s jar (those resources are produced by the
// `extractSevenZipResources` task which can't run that early). So the only place
// to materialize the bundle is before any project is configured.
//
// Idempotent: skip the entire chain when the cache target already exists. The
// per-user cache key includes a layout version so we can re-extract by bumping
// the version constant rather than asking users to wipe a directory.
//
// Only applied on Windows hosts (settings.gradle.kts gates the `apply(from = ...)`).
// Mac and Linux config-phase IDE unpacks go through .tar.gz / .dmg and never need
// 7z.exe, so they don't pay the bootstrap cost.

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

val sevenZipVersion = "2301"
val sevenZipBaseUrl = "https://www.7-zip.org/a"
val layoutVersion = "v1"

val cacheRoot: Path = Paths.get(gradle.gradleUserHomeDir.absolutePath)
    .resolve("caches").resolve("mcp-steroid").resolve("7z-bundle-$layoutVersion")
val winX64Dir: Path = cacheRoot.resolve("win-x64")
val sevenZExe: Path = winX64Dir.resolve("7z.exe")

fun downloadIfMissing(url: String, dest: Path) {
    if (Files.isRegularFile(dest) && Files.size(dest) > 0) return
    Files.createDirectories(dest.parent)
    val tmp = dest.resolveSibling("${dest.fileName}.tmp")
    println("[mcp-steroid:7z-bootstrap] GET $url")
    URI(url).toURL().openStream().use { input ->
        Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
    }
    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

fun runProcess(workingDir: Path, vararg cmd: String) {
    val process = ProcessBuilder(*cmd)
        .directory(workingDir.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    require(exit == 0) {
        "7z-bootstrap subprocess failed (exit $exit): ${cmd.joinToString(" ")}\n$output"
    }
}

if (!Files.isRegularFile(sevenZExe)) {
    println("[mcp-steroid:7z-bootstrap] materializing 7-Zip Windows binaries under $cacheRoot")
    Files.createDirectories(winX64Dir)

    val bootstrapDir = cacheRoot.resolve("bootstrap")
    Files.createDirectories(bootstrapDir)

    // Stage 1: 7zr.exe — reduced single-file .7z extractor (no NSIS support).
    val sevenZr = bootstrapDir.resolve("7zr.exe")
    downloadIfMissing("$sevenZipBaseUrl/7zr.exe", sevenZr)

    // Stage 2: 7z<ver>-extra.7z — ships the NSIS-capable 7za.exe.
    val extraArchive = bootstrapDir.resolve("7z$sevenZipVersion-extra.7z")
    downloadIfMissing("$sevenZipBaseUrl/7z$sevenZipVersion-extra.7z", extraArchive)

    // Stage 3: 7zr.exe extracts 7za.exe from the extra archive.
    val extraStaging = bootstrapDir.resolve("extra-staging")
    extraStaging.toFile().deleteRecursively()
    Files.createDirectories(extraStaging)
    runProcess(
        bootstrapDir,
        sevenZr.toAbsolutePath().toString(),
        "x", extraArchive.toAbsolutePath().toString(),
        "-y", "-o${extraStaging.toAbsolutePath()}",
    )
    val sevenZa = extraStaging.resolve("7za.exe")
    require(Files.isRegularFile(sevenZa)) {
        "7za.exe missing from $extraArchive — extra archive layout may have changed"
    }

    // Stage 4: 7z<ver>-x64.exe — the NSIS installer carrying the full 7z.exe + 7z.dll.
    val nsisInstaller = bootstrapDir.resolve("7z$sevenZipVersion-x64.exe")
    downloadIfMissing("$sevenZipBaseUrl/7z$sevenZipVersion-x64.exe", nsisInstaller)

    // Stage 5: 7za.exe extracts the NSIS installer.
    val nsisStaging = bootstrapDir.resolve("nsis-staging")
    nsisStaging.toFile().deleteRecursively()
    Files.createDirectories(nsisStaging)
    runProcess(
        bootstrapDir,
        sevenZa.toAbsolutePath().toString(),
        "x", nsisInstaller.toAbsolutePath().toString(),
        "-y", "-o${nsisStaging.toAbsolutePath()}",
    )

    // Stage 6: copy 7z.exe + 7z.dll + License.txt into the cache root.
    for (file in listOf("7z.exe", "7z.dll", "License.txt")) {
        val source = nsisStaging.resolve(file)
        require(Files.isRegularFile(source)) {
            "$file missing from NSIS installer staging — 7-Zip installer layout may have changed"
        }
        Files.copy(source, winX64Dir.resolve(file), StandardCopyOption.REPLACE_EXISTING)
    }

    extraStaging.toFile().deleteRecursively()
    nsisStaging.toFile().deleteRecursively()

    require(Files.isRegularFile(sevenZExe)) {
        "7z-bootstrap finished but $sevenZExe is missing — see staging logs above"
    }
}

// SevenZipLocator (loaded into buildSrc's classloader at config phase) consults this
// JVM system property FIRST, falling back to its classpath-resource extract path only
// when this is unset. Both paths converge on the same bundled tuple.
System.setProperty("mcp.intellij-downloader.sevenZipBundleDir", cacheRoot.toAbsolutePath().toString())
