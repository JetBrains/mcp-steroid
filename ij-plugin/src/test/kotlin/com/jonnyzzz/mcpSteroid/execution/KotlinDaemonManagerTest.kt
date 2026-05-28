/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.script.ScriptException

/**
 * Tests for KotlinDaemonManager.
 *
 * These tests verify the daemon management utilities work correctly.
 * Note: Tests that interact with actual daemon files may affect
 * running Kotlin daemons on the system.
 */
@TestApplication
class KotlinDaemonManagerTest {
    private val manager: KotlinDaemonManager get() = kotlinDaemonManager

    @Test
    fun cleanupClientMarkersWithEmptyDir() {
        val tempDir = createTempDirectory("daemon-test")
        try {
            val cleaned = manager.cleanupClientMarkers(tempDir)
            assertEquals(0, cleaned, "Should return 0 for empty directory")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cleanupClientMarkersDeletesMarkerFiles() {
        val tempDir = createTempDirectory("daemon-test")
        try {
            // Create marker files
            File(tempDir, "client1-is-running").createNewFile()
            File(tempDir, "client2-is-running").createNewFile()
            // Create non-marker file (should not be deleted)
            File(tempDir, "daemon.run").createNewFile()

            val cleaned = manager.cleanupClientMarkers(tempDir)

            assertEquals(2, cleaned, "Should delete 2 marker files")
            assertFalse(File(tempDir, "client1-is-running").exists(), "Marker file 1 should be deleted")
            assertFalse(File(tempDir, "client2-is-running").exists(), "Marker file 2 should be deleted")
            assertTrue(File(tempDir, "daemon.run").exists(), "Non-marker file should still exist")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cleanupClientMarkersWithNullDir() {
        val cleaned = manager.cleanupClientMarkers(null)
        assertEquals(0, cleaned, "Should return 0 for null directory")
    }

    @Test
    fun cleanupClientMarkersWithNonExistentDir() {
        val nonExistentDir = File("/non/existent/path/daemon")
        val cleaned = manager.cleanupClientMarkers(nonExistentDir)
        assertEquals(0, cleaned, "Should return 0 for non-existent directory")
    }

    @Test
    fun getRunningDaemonCountNonNegative() {
        val count = manager.getRunningDaemonCount()
        assertTrue(count >= 0, "Running daemon count should be non-negative")
    }

    private fun createTempDirectory(prefix: String): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        return tempDir
    }
}
