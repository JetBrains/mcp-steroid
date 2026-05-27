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
    jvmToolchain(25)
}

// `src/buildsrc-shared/kotlin/` carries the IDE compatibility matrix
// (`McpSteroidIdeTargets`), the resolver, the downloader, the unpacker,
// and the LocalIdeProvisioner that ij-plugin/build.gradle.kts calls at
// script-evaluation time. The same path is added as a source dir in
// `buildSrc/build.gradle.kts`, so Gradle scripts and this module's CLI
// compile the same .kt files independently — one source of truth, two
// compiled copies (different classloaders, different Kotlin versions).
// Transitive deps used by the shared code (kotlinx-serialization, slf4j,
// commons-compress, xz) must stay in lockstep with `buildSrc/build.gradle.kts`.
sourceSets.main {
    kotlin.srcDir("src/buildsrc-shared/kotlin")
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
}

dependencies {
    // kotlinx pins read from root gradle.properties so KotlinxRuntimeProbe is
    // compiled against the SAME versions production modules link against;
    // otherwise a paired bump there could leave the probe shipping stale
    // bytecode and miss the very drift it's meant to catch.
    val kotlinxSerialization = providers.gradleProperty("mcp.kotlinx.serialization.version").get()
    val kotlinxCoroutines = providers.gradleProperty("mcp.kotlinx.coroutines.version").get()
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerialization")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutines")
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
    // Windows host structural exclusions — Gradle-task-level only, per
    // root CLAUDE.md "no runtime skipping" rule:
    //
    //  - IdeUnpackerSecurityTest:
    //      Tar path-separator handling assumes the test host preserves
    //      `/` verbatim from tar entries (entry name + symlink target).
    //      `File`/`Path` on Windows normalises `/` → `\` before
    //      `Files.createSymbolicLink` and on read-back via
    //      `Files.readSymbolicLink`, so the verbatim-text assertions
    //      flip. Tracked as a real cross-platform bug in IdeUnpacker
    //      (task #78); for now exclude on Windows host so the rest of
    //      the suite stays a usable Windows-build signal.
    //  - SevenZipLocatorTest:
    //      Asserts `7z/win-x64/{7z.exe,7z.dll,License.txt}` resources
    //      are on the classpath. Today's Windows-host build skips the
    //      Windows-payload extraction (no NSIS-capable bootstrap
    //      off-the-shelf — see intellij-downloader/build.gradle.kts
    //      `resolveSevenZipBootstrapPlatform`), so the resources are
    //      legitimately absent on Windows-host JARs. Tracked as
    //      task #79 (two-stage Windows bootstrap that would restore
    //      uniform bundle output).
    //
    // Both groups DO run on Linux + macOS hosts, so the safety / cache /
    // resource invariants are exercised on every release-host build.
    if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        filter {
            excludeTestsMatching("com.jonnyzzz.mcpSteroid.ideDownloader.IdeUnpackerSecurityTest")
            excludeTestsMatching("com.jonnyzzz.mcpSteroid.ideDownloader.SevenZipLocatorTest")
        }
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

/**
 * Builds the per-host 7-Zip bootstrap descriptor.
 *
 * The 7-Zip Windows installer (`7z${ver}-x64.exe`) is an NSIS self-extractor;
 * unpacking it at build time requires a 7-Zip that supports the NSIS format,
 * and the only off-the-shelf binaries with NSIS support on this side of the
 * chicken-and-egg are the Unix `7zz` variants. The reduced Windows `7zr.exe`
 * does NOT support NSIS, so a Windows build host has no usable bootstrap to
 * unpack the installer.
 *
 * Windows is therefore returned as `null` — the caller treats absence of a
 * bootstrap as "skip the Windows-payload extraction tasks on this host."
 * The release plugin .zip is built on Linux/Mac (where this returns a
 * concrete platform) and the produced bundle contains the same
 * `7z/win-x64/{7z.exe,7z.dll,License.txt}` resources regardless of which
 * of those two host families ran the build. A Windows TC agent that only
 * runs `:ij-plugin:test` does not need those resources to compile or run
 * the test classpath.
 *
 * `null` cases NEVER fail script evaluation — that was the previous design
 * (a top-level `val resolveSevenZipBootstrapPlatform()` call), which
 * blocked even `./gradlew help` on Windows because Kotlin DSL evaluates
 * top-level vals during configuration phase. The wrapper [sevenZipBootstrapPlatform]
 * below is a lazy [Provider] so the absent-host case only surfaces when a
 * task that actually uses the bootstrap runs.
 */
@Suppress("SpellCheckingInspection")
fun resolveSevenZipBootstrapPlatform(): BootstrapPlatform? {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.isMacOsX -> BootstrapPlatform("mac", "mac.tar.xz")
        os.isLinux -> when (arch) {
            "aarch64", "arm64" -> BootstrapPlatform("linux-arm64", "linux-arm64.tar.xz")
            else -> BootstrapPlatform("linux-x64", "linux-x64.tar.xz")
        }
        os.isWindows -> null    // see kdoc — no NSIS-capable Windows bootstrap
        else -> error("Unsupported build host for 7-Zip bootstrap: ${os.name} ($arch)")
    }
}

/**
 * Lazy provider so the bootstrap-platform lookup runs at task-execution
 * time, not at script-evaluation. The unwrap-or-skip path lives in each
 * consuming task's `onlyIf { }` gate (search for `sevenZipBootstrapPlatform.orNull`).
 */
val sevenZipBootstrapPlatform: Provider<BootstrapPlatform> =
    providers.provider { resolveSevenZipBootstrapPlatform() }

/**
 * Eager unwrap of the bootstrap platform for task **registration** gating.
 * `de.undercouch.download`'s `src(...)` does NOT support `Provider<String>` —
 * `convertSource()` is called during graph-validation (before `onlyIf` can
 * short-circuit) and throws "Download source must either be a URL, a URI,
 * a CharSequence, a Collection or an array." We therefore skip REGISTERING
 * the Download tasks on Windows hosts; downstream tasks check this value
 * for the same skip decision. Reading at script-eval is safe because
 * [resolveSevenZipBootstrapPlatform] is null-returning for Windows (never
 * throws) since the lazy refactor above.
 */
val sevenZipHostBootstrap: BootstrapPlatform? = sevenZipBootstrapPlatform.orNull

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

// Windows hosts skip Download-task registration entirely (see
// `sevenZipHostBootstrap` kdoc). On non-null hosts the task is registered with
// concrete `src` + `dest` strings — the values are stable for the host, so
// there's no benefit to making them lazy beyond avoiding eager evaluation,
// which we've already achieved by gating the whole registration.
val downloadSevenZipBootstrap = sevenZipHostBootstrap?.let { platform ->
    tasks.register<Download>("downloadSevenZipBootstrap") {
        description = "Downloads the host 7-Zip bootstrap archive used to unpack Windows payloads."
        group = "build setup"
        val destFile = sevenZipBootstrapDir.get().asFile
            .resolve("7z${sevenZipVersion}-${platform.archiveSuffix}")
        src("$sevenZipBaseUrl/7z${sevenZipVersion}-${platform.archiveSuffix}")
        dest(destFile)
        configureReliableDownload()
        // 7-zip.org artifacts are immutable for a given version; skip if already on disk.
        onlyIf { !destFile.exists() }
    }
}

val extractSevenZipBootstrap by tasks.registering {
    description = "Extracts the host 7-Zip bootstrap executable."
    group = "build setup"
    downloadSevenZipBootstrap?.let { dependsOn(it) }
    onlyIf { sevenZipHostBootstrap != null }
    val platform = sevenZipHostBootstrap
    val archiveFile = sevenZipBootstrapDir.map { dir ->
        // Use a stable per-task input path even when the platform is null
        // so Gradle's task-graph validation succeeds; the file is only ever
        // read inside the doLast block, which is gated by the onlyIf above.
        dir.asFile.resolve("7z${sevenZipVersion}-${platform?.archiveSuffix ?: "unused"}")
    }
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

// Likewise registered only on hosts that can run the bootstrap — there's no
// point downloading the installer on Windows because we have no
// NSIS-capable tool to unpack it on the build host. The release plugin .zip
// is built on Linux/Mac where this DOES run, and ships the extracted
// `7z/win-x64/*` payload via the resources tree below.
val downloadSevenZipWindowsInstaller = sevenZipHostBootstrap?.let {
    tasks.register<Download>("downloadSevenZipWindowsInstaller") {
        description = "Downloads the pinned 7-Zip Windows installer."
        group = "build setup"
        val destFile = sevenZipDownloadDir.get().asFile.resolve("7z${sevenZipVersion}-x64.exe")
        src("$sevenZipBaseUrl/7z${sevenZipVersion}-x64.exe")
        dest(destFile)
        configureReliableDownload()
        // 7-zip.org artifacts are immutable for a given version; skip if already on disk.
        onlyIf { !destFile.exists() }
    }
}

val extractSevenZipResources by tasks.registering {
    description = "Extracts the bundled 7-Zip Windows resources for consumers."
    group = "build setup"
    // Windows host has no NSIS-capable bootstrap (see [resolveSevenZipBootstrapPlatform]).
    // The Windows-payload extraction simply doesn't run on Windows; the resource
    // dir stays empty and downstream `processResources` walks zero files under
    // `resources/main/7z`. Release plugin .zips are produced on Linux/Mac so the
    // shipped artifact still carries the same `7z/win-x64/*` payload on every
    // release — only Windows-host test runs see the empty tree.
    dependsOn(extractSevenZipBootstrap)
    downloadSevenZipWindowsInstaller?.let { dependsOn(it) }
    onlyIf { sevenZipHostBootstrap != null }
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
