/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import javax.script.ScriptException

/**
 * Tests for KotlinDaemonManager.
 *
 * These tests verify the daemon management utilities work correctly.
 * Note: Tests that interact with actual daemon files may affect
 * running Kotlin daemons on the system.
 */
class KotlinDaemonManagerTest : BasePlatformTestCase() {
    private val manager: KotlinDaemonManager get() = kotlinDaemonManager

    fun testCleanupClientMarkersWithEmptyDir() {
        val tempDir = createTempDirectory("daemon-test")
        try {
            val cleaned = manager.cleanupClientMarkers(tempDir)
            assertEquals("Should return 0 for empty directory", 0, cleaned)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testCleanupClientMarkersDeletesMarkerFiles() {
        val tempDir = createTempDirectory("daemon-test")
        try {
            // Create marker files
            File(tempDir, "client1-is-running").createNewFile()
            File(tempDir, "client2-is-running").createNewFile()
            // Create non-marker file (should not be deleted)
            File(tempDir, "daemon.run").createNewFile()

            val cleaned = manager.cleanupClientMarkers(tempDir)

            assertEquals("Should delete 2 marker files", 2, cleaned)
            assertFalse("Marker file 1 should be deleted", File(tempDir, "client1-is-running").exists())
            assertFalse("Marker file 2 should be deleted", File(tempDir, "client2-is-running").exists())
            assertTrue("Non-marker file should still exist", File(tempDir, "daemon.run").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testCleanupClientMarkersWithNullDir() {
        val cleaned = manager.cleanupClientMarkers(null)
        assertEquals("Should return 0 for null directory", 0, cleaned)
    }

    fun testCleanupClientMarkersWithNonExistentDir() {
        val nonExistentDir = File("/non/existent/path/daemon")
        val cleaned = manager.cleanupClientMarkers(nonExistentDir)
        assertEquals("Should return 0 for non-existent directory", 0, cleaned)
    }

    fun testGetRunningDaemonCountNonNegative() {
        val count = manager.getRunningDaemonCount()
        assertTrue("Running daemon count should be non-negative", count >= 0)
    }

    private fun createTempDirectory(prefix: String): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        return tempDir
    }
}
