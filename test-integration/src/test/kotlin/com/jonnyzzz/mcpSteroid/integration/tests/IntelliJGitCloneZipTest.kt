/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.ensureIntelliJGitCloneZipInCache
import com.jonnyzzz.mcpSteroid.integration.infra.intelliJGitCloneZipInCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class IntelliJGitCloneZipTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `configured checkout replaces stale cached archive`() {
        val cacheDir = tempDir.resolve("cache").toFile()
        val staleZip = intelliJGitCloneZipInCache(cacheDir)
        staleZip.parentFile.mkdirs()
        staleZip.writeText("stale-teamcity-zip")

        val checkout = createMinimalIntelliJCheckout(tempDir.resolve("checkout"))

        withSystemProperty("test.integration.intellij.checkout.dir", checkout.toString()) {
            val resolved = ensureIntelliJGitCloneZipInCache(
                cacheDir = cacheDir,
                zipUrl = "http://127.0.0.1:9/should-not-download.zip",
            )

            assertEquals(staleZip.absoluteFile, resolved.absoluteFile)
            ZipFile(resolved).use { zip ->
                assertNotNull(zip.getEntry("bazel.cmd"))
                assertNotNull(zip.getEntry("README.md"))
                val gitConfig = zip.getInputStream(zip.getEntry(".git/config")).bufferedReader().readText()
                assertTrue(gitConfig.contains("url = ssh://git@example.com/intellij/ultimate.git"), gitConfig)
            }
            assertTrue(resolved.length() > "stale-teamcity-zip".length)
        }
    }

    private fun createMinimalIntelliJCheckout(path: Path): Path {
        path.createDirectories()
        path.resolve("bazel.cmd").writeText("@echo off\n")
        path.resolve("README.md").writeText("local checkout\n")

        runGit(path, "init", "-b", "master")
        runGit(path, "add", "bazel.cmd", "README.md")
        runGit(
            path,
            "-c", "user.email=test@example.com",
            "-c", "user.name=Test User",
            "commit", "-m", "initial",
        )
        runGit(path, "remote", "add", "origin", "ssh://git@example.com/intellij/ultimate.git")

        return path
    }

    private fun runGit(workDir: Path, vararg args: String) {
        val command = listOf("git") + args
        val process = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Git command failed with exit code $exitCode: ${command.joinToString(" ")}\n$output"
        }
    }

    private inline fun withSystemProperty(name: String, value: String, action: () -> Unit) {
        val oldValue = System.getProperty(name)
        try {
            System.setProperty(name, value)
            action()
        } finally {
            if (oldValue == null) {
                System.clearProperty(name)
            } else {
                System.setProperty(name, oldValue)
            }
        }
    }
}
