/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asserts the generated [DevrigVersionMetadata] class carries the Gradle project
 * version that runtime banners, MCP handshakes, and update checks report.
 */
class DevrigVersionMetadataTest {

    @Test
    fun `getDevrigVersion returns the generated metadata value`() {
        val version = DevrigVersionMetadata.getDevrigVersion()
        assertTrue(version.isNotBlank(), "version must be non-blank, got: '$version'")
        assertEquals(DevrigVersionMetadata.getDevrigVersion(), version)
    }

    @Test
    fun `version matches project_version system property when provided by the build`() {
        val expected = System.getProperty("devrig.expected.version") ?: return
        assertEquals(expected, DevrigVersionMetadata.getDevrigVersion())
    }
}
