@file:Suppress(
    "GrazieInspection",
    "GrazieInspectionRunner",
    "HasPlatformType",
    "SpellCheckingInspection",
    "UnusedReceiverParameter",
)

import de.undercouch.gradle.tasks.download.Download
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.gradle.api.attributes.Usage
import org.gradle.internal.os.OperatingSystem
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream

buildscript {
    dependencies {
        // Used by the inline doLast block below to extract `7zz` from 7-zip.org tar.xz archives
        // at configuration/build time. Gradle's built-in `resources.{gzip,bzip2}` helpers don't
        // include xz, so we do the decoding ourselves.
        classpath("org.apache.commons:commons-compress:1.28.0")
        classpath("org.tukaani:xz:1.10")
    }
}

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    // Runtime xz support for IdeUnpacker fallback paths that might handle .tar.xz directly.
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.32")
}

tasks.test {
    useJUnit {
        excludeCategories("com.jonnyzzz.mcpSteroid.ideDownloader.LiveNetwork")
    }
}

tasks.register<Test>("liveNetworkTest") {
    description = "Runs intellij-downloader tests that intentionally hit public vendor feeds."
    group = "verification"
    useJUnit {
        includeCategories("com.jonnyzzz.mcpSteroid.ideDownloader.LiveNetwork")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}

// ────────────────────────────────────────────────────────────────────────────
// Bundled 7-Zip Windows payload — LGPL v2.1+, https://www.7-zip.org/license.txt
//
// We ship the official Windows x64 `7z.exe` + `7z.dll` + `License.txt` tuple.
// The pinned 7-Zip release keeps NSIS and LZMA2 support inside `7z.dll`; there are no
// `Formats/Nsis.dll` or `Codecs/Lzma2.dll` plugin files in this pinned payload.
//
// The upstream Windows installer is itself unpacked at build time with a Unix
// `7zz` bootstrap downloaded from 7-zip.org for the build host. The bootstrap
// lives under build/7z-bootstrap/ only and is never exposed through the outgoing
// sevenZipBinariesElements artifact or main resources.
//
// License attribution: see `THIRD_PARTY_NOTICES.md`.
// ────────────────────────────────────────────────────────────────────────────

val sevenZipVersion = "2301"
val sevenZipBaseUrl = "https://www.7-zip.org/a"

val sevenZipBootstrapDir = layout.buildDirectory.dir("7z-bootstrap")
val sevenZipBootstrapExtractedDir = sevenZipBootstrapDir.map { it.dir("extracted") }
val sevenZipDownloadDir = layout.buildDirectory.dir("7z-download")
val sevenZipWindowsStagingDir = layout.buildDirectory.dir("7z-windows-staging")
val sevenZipResourceDir = layout.buildDirectory.dir("7z-extracted")

data class BootstrapPlatform(val id: String, val archiveSuffix: String)

@Suppress("SpellCheckingInspection")
fun resolveSevenZipBootstrapPlatform(): BootstrapPlatform {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.isMacOsX -> BootstrapPlatform("mac", "mac.tar.xz")
        os.isLinux -> when (arch) {
            "aarch64", "arm64" -> BootstrapPlatform("linux-arm64", "linux-arm64.tar.xz")
            else -> BootstrapPlatform("linux-x64", "linux-x64.tar.xz")
        }
        os.isWindows -> error("7-Zip Windows payload extraction requires a Linux or macOS build host; Windows bootstrap is intentionally unsupported")
        else -> error("Unsupported build host for 7-Zip bootstrap: ${os.name} ($arch)")
    }
}

val sevenZipBootstrapPlatform = resolveSevenZipBootstrapPlatform()

val downloadConnectTimeoutMs = 30_000
val downloadReadTimeoutMs = 15 * 60_000
val downloadRetryCount = 5

fun Download.configureReliableDownload() {
    onlyIfModified(true)
    connectTimeout(downloadConnectTimeoutMs)
    readTimeout(downloadReadTimeoutMs)
    retries(downloadRetryCount)
    tempAndMove(true)
}

val downloadSevenZipBootstrap by tasks.registering(Download::class) {
    description = "Downloads the host 7-Zip bootstrap archive used to unpack Windows payloads."
    group = "build setup"
    val destFile = sevenZipBootstrapDir.get().asFile
        .resolve("7z${sevenZipVersion}-${sevenZipBootstrapPlatform.archiveSuffix}")
    src("$sevenZipBaseUrl/7z${sevenZipVersion}-${sevenZipBootstrapPlatform.archiveSuffix}")
    dest(destFile)
    configureReliableDownload()
    // 7-zip.org artifacts are immutable for a given version; skip if already on disk.
    onlyIf { !destFile.exists() }
}

val extractSevenZipBootstrap by tasks.registering {
    description = "Extracts the host 7-Zip bootstrap executable."
    group = "build setup"
    dependsOn(downloadSevenZipBootstrap)
    val archiveFile = sevenZipBootstrapDir.map { it.asFile.resolve("7z${sevenZipVersion}-${sevenZipBootstrapPlatform.archiveSuffix}") }
    inputs.file(archiveFile)
    outputs.dir(sevenZipBootstrapExtractedDir)

    doLast {
        val outDir = sevenZipBootstrapExtractedDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val tarXz = archiveFile.get()
        BufferedInputStream(tarXz.inputStream()).use { fileStream ->
            XZInputStream(fileStream).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val keepName = when (entry.name) {
                            "7zz", "./7zz" -> "7zz"
                            "License.txt", "./License.txt" -> "License.txt"
                            else -> null
                        } ?: continue
                        val outFile = outDir.resolve(keepName)
                        outFile.outputStream().use { sink -> tar.copyTo(sink) }
                        if (keepName == "7zz") outFile.setExecutable(true, false)
                    }
                }
            }
        }
        require(outDir.resolve("7zz").isFile) {
            "Did not find `7zz` inside ${tarXz.name} — 7-Zip bootstrap archive layout may have changed"
        }
        require(outDir.resolve("License.txt").isFile) {
            "Did not find `License.txt` inside ${tarXz.name} — 7-Zip bootstrap archive layout may have changed"
        }
    }
}

val downloadSevenZipWindowsInstaller by tasks.registering(Download::class) {
    description = "Downloads the pinned 7-Zip Windows installer."
    group = "build setup"
    val destFile = sevenZipDownloadDir.get().asFile.resolve("7z${sevenZipVersion}-x64.exe")
    src("$sevenZipBaseUrl/7z${sevenZipVersion}-x64.exe")
    dest(destFile)
    configureReliableDownload()
    // 7-zip.org artifacts are immutable for a given version; skip if already on disk.
    onlyIf { !destFile.exists() }
}

val extractSevenZipResources by tasks.registering {
    description = "Extracts the bundled 7-Zip Windows resources for consumers."
    group = "build setup"
    dependsOn(extractSevenZipBootstrap, downloadSevenZipWindowsInstaller)
    val bootstrapExecutable = sevenZipBootstrapExtractedDir.map { it.asFile.resolve("7zz") }
    val windowsInstaller = sevenZipDownloadDir.map { it.asFile.resolve("7z${sevenZipVersion}-x64.exe") }
    inputs.file(bootstrapExecutable)
    inputs.file(windowsInstaller)
    inputs.property("sevenZipPayloadLayout", "windows-x64-exe-dll-license-v1")
    outputs.dir(sevenZipResourceDir)

    doLast {
        val bootstrap = bootstrapExecutable.get()
        require(bootstrap.isFile && bootstrap.canExecute()) {
            "7-Zip bootstrap executable is missing or not executable: $bootstrap"
        }
        val installer = windowsInstaller.get()
        require(installer.isFile) {
            "7-Zip Windows installer is missing: $installer"
        }

        val stagingDir = sevenZipWindowsStagingDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        providers.exec {
            commandLine(bootstrap.absolutePath, "x", installer.absolutePath, "-y", "-o${stagingDir.absolutePath}")
        }.result.get().assertNormalExitValue()

        val root = sevenZipResourceDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val winDir = root.resolve("7z/win-x64").apply { mkdirs() }
        val payload = mapOf(
            "7z.exe" to "7z.exe",
            "7z.dll" to "7z.dll",
            "License.txt" to "License.txt",
        )
        payload.forEach { (sourceName, targetName) ->
            val source = stagingDir.resolve(sourceName)
            require(source.isFile) {
                "Did not find `$sourceName` inside ${installer.name} — 7-Zip Windows installer layout may have changed"
            }
            source.copyTo(winDir.resolve(targetName), overwrite = true)
        }

        winDir.resolve("License.txt").copyTo(root.resolve("7z/License.txt"), overwrite = true)
    }
}

// Outgoing: the unpacked 7-Zip Windows payload tree
// (`7z/win-x64/{7z.exe,7z.dll,License.txt}` plus shared `7z/License.txt`) for
// consumers that want to bundle the binaries in their own distribution instead
// of pulling them off the classpath. See SevenZipLocator's doc for the
// classpath-resource consumer path that already exists.
val sevenZipBinariesElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "seven-zip-binaries"))
    }
}

artifacts {
    add(sevenZipBinariesElements.name, sevenZipResourceDir.map { it.asFile }) {
        builtBy(extractSevenZipResources)
    }
}

sourceSets.named("main") {
    resources.srcDir(extractSevenZipResources)
}

tasks.named("processResources") {
    dependsOn(extractSevenZipResources)
    doFirst {
        delete(layout.buildDirectory.dir("resources/main/7z"))
    }
}
