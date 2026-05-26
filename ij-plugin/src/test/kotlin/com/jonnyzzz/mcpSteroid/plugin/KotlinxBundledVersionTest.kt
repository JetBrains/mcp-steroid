/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.plugin

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeTarget
import com.jonnyzzz.mcpSteroid.ideDownloader.McpSteroidIdeTargets
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveAndUnpackLocally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Binary-side counterpart to [com.jonnyzzz.mcpSteroid.gradle.VerifyBundledKotlinxRuntimeTask]
 * (which forks a JVM and exercises the kotlinx APIs at runtime). This one
 * reads the IDE-bundled kotlinx jar manifests directly and asserts the
 * versions match what the plugin is pinned to at compile-time.
 *
 * The plugin compiles against `kotlinx-coroutines-core` / `kotlinx-serialization-{core,json}`
 * but does NOT ship them — `ij-plugin/build.gradle.kts` excludes the
 * `org.jetbrains.kotlinx` group from the implementation configuration. At
 * runtime the IDE classloader serves the bundled `intellij.libraries.kotlinx.*.jar`
 * variants. A drift between our compile-time pin and the IDE-bundled
 * version surfaces as a `NoSuchMethodError` / `LinkageError` only at
 * production runtime — this test catches it at build-test time.
 *
 * Comparison rules:
 *
 *  - **kotlinx-coroutines-core**: IDE jar carries
 *    `META-INF/kotlinx_coroutines_core.version` with a JetBrains-forked
 *    value like `"1.10.2-intellij-1"`. The public Maven Central pin is
 *    `"1.10.2"`. We strip the `-intellij-N` suffix before equality, since
 *    the fork's public API surface is the same as the upstream release.
 *  - **kotlinx-serialization-core**: IDE jar's MANIFEST.MF carries
 *    `Implementation-Version: 1.9.0` verbatim (no JetBrains fork). Strict
 *    equality.
 *
 * Future drift detection: if the IDE bumps coroutines from `1.10.2-intellij-N`
 * to `1.10.3-intellij-N` (or similar), this test fails and forces a paired
 * update of the project pins in `mcp-core` / `mcp-stdio` / `mcp-http` /
 * `execution-storage` / `mcp-steroid-server` / `ocr-common`.
 *
 * The IDE under test is `McpSteroidIdeTargets.buildTarget` (the primary
 * build IDE) — resolved + unpacked via `LocalIdeProvisioner` exactly the
 * same way `ij-plugin/build.gradle.kts` resolves it, so this test sees
 * the same on-disk artifact.
 */
class KotlinxBundledVersionTest {

    @Test
    fun `coroutines-core matches the project-pinned base version on every verifier IDE`() {
        val expected = requireProperty("mcp.steroid.test.expected.kotlinxCoroutinesVersion")
        for (target in McpSteroidIdeTargets.verifierTargets) {
            val ideRoot = resolveLocally(target)
            val jar = File(ideRoot, "lib/intellij.libraries.kotlinx.coroutines.core.jar")
            assertTrue(
                "IDE-bundled coroutines jar missing at $jar (target=$target)",
                jar.isFile,
            )

            val ideVersionRaw = readVersionFile(jar, "META-INF/kotlinx_coroutines_core.version")
            assertNotNull(
                "Expected META-INF/kotlinx_coroutines_core.version in $jar (target=$target)",
                ideVersionRaw,
            )
            val ideBase = stripJetBrainsForkSuffix(ideVersionRaw!!)

            assertEquals(
                "kotlinx-coroutines-core IDE-bundled base version drifted from project pin " +
                    "for $target. Update the implementation pins in :mcp-core / :mcp-stdio / " +
                    ":mcp-http / :execution-storage / :mcp-steroid-server (and re-run " +
                    ":ij-plugin:test).",
                expected,
                ideBase,
            )
        }
    }

    @Test
    fun `serialization-core matches the project pin on every verifier IDE`() {
        val expected = requireProperty("mcp.steroid.test.expected.kotlinxSerializationVersion")
        for (target in McpSteroidIdeTargets.verifierTargets) {
            val ideRoot = resolveLocally(target)
            val jar = File(ideRoot, "lib/intellij.libraries.kotlinx.serialization.core.jar")
            assertTrue(
                "IDE-bundled serialization-core jar missing at $jar (target=$target)",
                jar.isFile,
            )

            val implVersion = JarFile(jar).use { jf ->
                jf.manifest?.mainAttributes?.getValue("Implementation-Version")
            }
            assertNotNull(
                "Expected Implementation-Version in MANIFEST.MF of $jar (target=$target)",
                implVersion,
            )

            assertEquals(
                "kotlinx-serialization-core IDE-bundled version drifted from project pin " +
                    "for $target. Update the implementation pins in :mcp-core / :mcp-stdio / " +
                    ":mcp-http / :execution-storage / :mcp-steroid-server / :ocr-common (and " +
                    "re-run :ij-plugin:test).",
                expected,
                implVersion,
            )
        }
    }

    private fun resolveLocally(target: IdeTarget): File {
        val projectHome = requireProperty("mcp.steroid.test.projectHome")
        return resolveAndUnpackLocally(
            target = target,
            downloadDir = File(projectHome, "build/ide-archives"),
            unpackBaseDir = File(projectHome, "build/local-ides"),
        )
    }

    private fun requireProperty(name: String): String =
        System.getProperty(name)
            ?: error(
                "$name system property is missing — set by ij-plugin/build.gradle.kts " +
                    "tasks.test { systemProperty(...) } / root allprojects block; " +
                    "running tests from outside Gradle?"
            )

    private fun readVersionFile(jar: File, entryPath: String): String? = JarFile(jar).use { jf ->
        val entry = jf.getJarEntry(entryPath) ?: return@use null
        jf.getInputStream(entry).bufferedReader().readText().trim().takeIf { it.isNotBlank() }
    }

    /**
     * Strips the JetBrains-fork suffix from a coroutines version string.
     * `"1.10.2-intellij-1"` -> `"1.10.2"`. Already-bare versions pass through
     * unchanged. The fork suffix is structurally `-intellij-<digits>` —
     * never seen on Maven Central artifacts.
     */
    private fun stripJetBrainsForkSuffix(version: String): String =
        Regex("-intellij-\\d+$").replace(version, "")

    companion object {
        /** JUnit-friendly assertTrue with descriptive message. */
        private fun assertTrue(message: String, condition: Boolean) {
            org.junit.Assert.assertTrue(message, condition)
        }
    }
}
