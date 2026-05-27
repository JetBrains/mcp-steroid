/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevrigRootTest {
    @AfterEach
    fun resetRootOverride() {
        DevrigRootTestSupport.reset()
    }

    @Test
    fun `resolves installDist root from jar under lib`(
        @TempDir tempDir: Path,
    ) {
        val root = tempDir.resolve("devrig")
        val jar = root.resolve("lib/devrig-1.2.3.jar")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fake jar")
        Files.writeString(root.resolve("ij-plugin.zip"), "fake plugin zip")

        DevrigRootTestSupport.overrideCodeSource(jar)

        assertEquals(root, DevrigRoot.path)
        assertEquals(root.resolve("ij-plugin.zip"), DevrigRoot.ijPluginZip())
        assertEquals(root.resolve("7z").resolve("7z.exe"), DevrigRoot.sevenZipBinary())
    }

    @Test
    fun `throws when no package payload exists next to lib`(
        @TempDir tempDir: Path,
    ) {
        val root = tempDir.resolve("devrig")
        val jar = root.resolve("lib/devrig-1.2.3.jar")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fake jar")

        DevrigRootTestSupport.overrideCodeSource(jar)

        val ex = assertFailsWith<IllegalStateException> {
            DevrigRoot.path
        }
        val message = ex.message.orEmpty()
        assertTrue(message.contains("devrig root"), "Expected root diagnostic, got: $message")
        assertTrue(message.contains(jar.toString()), "Expected inspected path in message, got: $message")
    }
}
