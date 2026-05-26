/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Resolves the `devrig` installDist root — the directory that contains `lib/` (the runtime classpath),
 * `bin/` (the launcher scripts), and the bundled package payloads (`ij-plugin.zip`, `7z/`).
 *
 * Hard requirement: this function MUST succeed when called from any production class in devrig.
 * If it cannot locate the root, throw an [IllegalStateException] with the diagnostic path it inspected;
 * there is no fallback. Callers may rely on [path].resolve("7z") and [path].resolve("ij-plugin.zip")
 * existing on disk.
 */
object DevrigRoot {
    private val lock = Any()

    @Volatile
    private var cachedPath: Path? = null

    /**
     * Test-only hook, intentionally private so production sources cannot
     * compile against it. `DevrigRootTestSupport` in the test source set mutates
     * this field reflectively and clears [cachedPath] between test cases.
     */
    @Volatile
    private var codeSourcePathForTests: Path? = null

    /** Lazy-evaluated; cached for the JVM lifetime. */
    val path: Path
        get() = cachedPath ?: synchronized(lock) {
            cachedPath ?: resolveOrFail().also { cachedPath = it }
        }

    fun sevenZipDir(): Path = path.resolve("7z")

    fun ijPluginZip(): Path = path.resolve("ij-plugin.zip")

    private fun resolveOrFail(): Path {
        val codeSourcePath = codeSourcePathForTests ?: realCodeSourcePath()
        val candidate = walkUpForInstallDistRoot(codeSourcePath)
            ?: error(
                "Cannot resolve devrig root from $codeSourcePath. " +
                    "Expected to find a parent dir containing both 'lib/' and at least " +
                    "one of 'ij-plugin.zip' or '7z/'. devrig must be launched via " +
                    "the installDist tree (`./npx-kt/build/install/devrig/bin/...`)."
            )

        check(candidate.resolve("lib").isDirectory()) {
            "devrig root inference produced $candidate, but $candidate/lib is missing"
        }
        return candidate
    }

    private fun realCodeSourcePath(): Path {
        val codeSourceUrl = DevrigRoot::class.java.protectionDomain
            ?.codeSource
            ?.location
            ?: error(
                "Cannot resolve devrig root: ProtectionDomain.codeSource.location is null. " +
                    "This usually means the class was loaded via a non-standard ClassLoader."
            )

        return try {
            Path.of(codeSourceUrl.toURI())
        } catch (e: Exception) {
            error("Cannot resolve devrig root: $codeSourceUrl is not a file URI: ${e.message}")
        }
    }

    /**
     * Walk parent dirs of [start] up to the filesystem root. Return the first parent that has `lib/`
     * and at least one bundled package payload. Return `null` if no such parent exists.
     */
    private fun walkUpForInstallDistRoot(start: Path): Path? {
        var p: Path? = start.parent
        while (p != null) {
            val hasLib = p.resolve("lib").isDirectory()
            val hasPackagePayload = p.resolve("ij-plugin.zip").toFile().isFile || p.resolve("7z").isDirectory()
            if (hasLib && hasPackagePayload) return p
            p = p.parent
        }
        return null
    }
}
