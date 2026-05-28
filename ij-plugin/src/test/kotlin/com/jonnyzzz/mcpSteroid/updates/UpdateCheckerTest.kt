/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for UpdateChecker version comparison logic.
 */
class UpdateCheckerTest : BasePlatformTestCase() {

    /**
     * Test that base version extraction works correctly.
     */
    fun testExtractBaseVersion() {
        // Simple version
        assertEquals("0.86.0", extractBaseVersion("0.86.0"))

        // SNAPSHOT version
        assertEquals("0.86.0", extractBaseVersion("0.86.0-SNAPSHOT"))

        // Full SNAPSHOT with timestamp and git hash suffix
        assertEquals("0.86.0", extractBaseVersion("0.86.0-SNAPSHOT-20260212-193000-a1b2c3d"))

        // Version with other suffix
        assertEquals("1.2.3", extractBaseVersion("1.2.3-beta1"))
    }

    /**
     * Test version comparison logic using StringUtil.compareVersionNumbers.
     */
    fun testVersionComparison() {
        // Remote newer
        assertTrue(StringUtil.compareVersionNumbers("0.87.0", "0.86.0") > 0)
        assertTrue(StringUtil.compareVersionNumbers("1.0.0", "0.99.99") > 0)
        assertTrue(StringUtil.compareVersionNumbers("0.86.1", "0.86.0") > 0)

        // Same version
        assertEquals(0, StringUtil.compareVersionNumbers("0.86.0", "0.86.0"))

        // Remote older
        assertTrue(StringUtil.compareVersionNumbers("0.85.0", "0.86.0") < 0)
        assertTrue(StringUtil.compareVersionNumbers("0.86.0", "0.87.0") < 0)
    }

    /**
     * Test comparing extracted base versions.
     */
    fun testExtractedVersionComparison() {
        // Current has SNAPSHOT suffix, remote is plain - should detect update when remote is higher
        val current = extractBaseVersion("0.86.0-SNAPSHOT-20260212-193000-a1b2c3d")
        val remoteNewer = "0.87.0"
        val remoteSame = "0.86.0"
        val remoteOlder = "0.85.0"

        assertTrue(StringUtil.compareVersionNumbers(remoteNewer, current) > 0)
        assertEquals(0, StringUtil.compareVersionNumbers(remoteSame, current))
        assertTrue(StringUtil.compareVersionNumbers(remoteOlder, current) < 0)
    }

    /**
     * Test user agent format.
     */
    fun testUserAgentFormat() {
        val userAgent = buildUserAgent("0.86.0-SNAPSHOT", "IU-253.12345")
        assertEquals("MCP-Steroid/0.86.0-SNAPSHOT (IntelliJ/IU-253.12345)", userAgent)
    }

    // Helper methods mirroring UpdateChecker logic for testing

    private fun extractBaseVersion(fullVersion: String): String {
        val snapshotIndex = fullVersion.indexOf("-SNAPSHOT")
        if (snapshotIndex > 0) {
            return fullVersion.substring(0, snapshotIndex)
        }
        val dashIndex = fullVersion.indexOf('-')
        if (dashIndex > 0) {
            return fullVersion.substring(0, dashIndex)
        }
        return fullVersion
    }

    private fun buildUserAgent(pluginVersion: String, ijBuild: String): String {
        return "MCP-Steroid/$pluginVersion (IntelliJ/$ijBuild)"
    }
}
