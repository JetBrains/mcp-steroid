/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HomePathsTest {

    @Test
    fun `DEVRIG_HOME resolves to an absolute normalized path and reports override to stderr`(
        @TempDir tempDir: Path,
    ) {
        val envHome = tempDir.resolve("env")
        Files.createDirectories(envHome)
        val errBytes = ByteArrayOutputStream()

        val paths = resolveHomePathsFromEnvironment(
            env = mapOf(DEVRIG_HOME_ENV to envHome.toString()),
            err = PrintStream(errBytes, true, Charsets.UTF_8),
        )

        assertEquals(envHome.toRealPath(), paths.home)
        assertTrue(errBytes.toString(Charsets.UTF_8).contains(DEVRIG_HOME_ENV))
    }

    @Test
    fun `DEVRIG_HOME rejects bare tilde`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveHomePathsFromEnvironment(env = mapOf(DEVRIG_HOME_ENV to "~"), err = null)
        }

        assertTrue(ex.message.orEmpty().contains("absolute"), "Expected absolute path hint, got: ${ex.message}")
    }

    @Test
    fun `DEVRIG_HOME rejects tilde slash prefix`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveHomePathsFromEnvironment(env = mapOf(DEVRIG_HOME_ENV to "~/foo"), err = null)
        }

        assertTrue(ex.message.orEmpty().contains("absolute"), "Expected absolute path hint, got: ${ex.message}")
    }

    @Test
    fun `DEVRIG_HOME rejects unsupported other user tilde form`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveHomePathsFromEnvironment(env = mapOf(DEVRIG_HOME_ENV to "~root"), err = null)
        }

        assertTrue(ex.message.orEmpty().contains("absolute"), "Expected absolute path hint, got: ${ex.message}")
    }

    @Test
    fun `DEVRIG_HOME rejects relative path`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveHomePathsFromEnvironment(env = mapOf(DEVRIG_HOME_ENV to "relative/path"), err = null)
        }

        assertTrue(ex.message.orEmpty().contains("absolute"), "Expected absolute path hint, got: ${ex.message}")
    }

    @Test
    fun `DEVRIG_HOME rejects missing absolute path`(
        @TempDir tempDir: Path,
    ) {
        val missing = tempDir.resolve("missing")
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveHomePathsFromEnvironment(env = mapOf(DEVRIG_HOME_ENV to missing.toString()), err = null)
        }

        assertTrue(ex.message.orEmpty().contains("canonical"), "Expected canonical path hint, got: ${ex.message}")
    }

    @Test
    fun `MCP_STEROID_HOME no longer overrides devrig home`(
        @TempDir tempDir: Path,
    ) {
        val expected = Path.of(System.getProperty("user.home"), ".mcp-steroid")
            .toAbsolutePath()
            .normalize()

        val paths = resolveHomePathsFromEnvironment(env = mapOf("MCP_STEROID_HOME" to tempDir.resolve("ignored").toString()), err = null)

        assertEquals(expected, paths.home)
    }

    @Test
    fun `blank environment falls back to user home default`() {
        val expected = Path.of(System.getProperty("user.home"), ".mcp-steroid")
            .toAbsolutePath()
            .normalize()

        val paths = resolveHomePathsFromEnvironment(env = mapOf(DEVRIG_HOME_ENV to "   "), err = null)

        assertEquals(expected, paths.home)
    }

    @Test
    fun `derived paths use the required managed backend layout`(
        @TempDir tempDir: Path,
    ) {
        val paths = HomePaths(tempDir)

        assertEquals(tempDir.resolve("logs"), paths.logsDir)
        assertEquals(tempDir.resolve("backends"), paths.backendsDir)
        assertEquals(tempDir.resolve("caches"), paths.cachesDir)
        assertEquals(tempDir.resolve("downloads"), paths.downloadsDir)
        assertEquals(tempDir.resolve("state"), paths.stateDir)
        assertEquals(tempDir.resolve("execution-storage"), paths.executionStorageDir)
        assertEquals(tempDir.resolve("backends/idea-community-2025.3.3"), paths.backendDir("idea-community-2025.3.3"))
        assertEquals(tempDir.resolve("caches/idea-community-2025.3.3"), paths.cacheDir("idea-community-2025.3.3"))
        assertEquals(tempDir.resolve("state/idea-community-2025.3.3.pid"), paths.pidFile("idea-community-2025.3.3"))
    }

    @Test
    fun `mkdirsAll creates the writable roots and is idempotent`(
        @TempDir tempDir: Path,
    ) {
        val paths = HomePaths(tempDir.resolve("home"))

        paths.mkdirsAll()
        paths.mkdirsAll()

        listOf(paths.logsDir, paths.backendsDir, paths.cachesDir, paths.downloadsDir, paths.stateDir).forEach { dir ->
            assertTrue(dir.isDirectory(), "$dir should be a directory")
        }
        assertTrue(!Files.exists(paths.executionStorageDir), "execution-storage is reserved and not created yet")
    }

    @Test
    fun `migrateLegacyArchives moves old archive files into downloads and is idempotent`(
        @TempDir tempDir: Path,
    ) {
        val paths = HomePaths(tempDir.resolve("home"))
        val legacyDir = paths.cachesDir.resolve("_archives")
        val archiveName = "ideaIC-2025.3.3.tar.gz"
        Files.createDirectories(legacyDir)
        Files.writeString(legacyDir.resolve(archiveName), "archive bytes")

        migrateLegacyArchives(paths)
        migrateLegacyArchives(paths)

        val migratedArchive = paths.downloadsDir.resolve(archiveName)
        assertTrue(Files.isRegularFile(migratedArchive), "archive should move into downloads/")
        assertEquals("archive bytes", Files.readString(migratedArchive))
        assertTrue(!Files.exists(legacyDir), "empty legacy archive directory should be deleted")
    }
}
