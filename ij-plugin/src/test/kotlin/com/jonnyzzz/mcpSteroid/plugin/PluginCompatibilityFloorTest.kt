/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.plugin

import com.jonnyzzz.mcpSteroid.ideDownloader.MANAGED_BACKEND_MIN_SUPPORTED_BUILD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginCompatibilityFloorTest {
    @Test
    fun `plugin sinceBuild matches the managed-backend resolver baseline`() {
        assertPluginCompatibilityFloor(
            sinceBuild = parseSinceBuildFromGradleScript(),
            resolverFloor = MANAGED_BACKEND_MIN_SUPPORTED_BUILD,
        )
    }

    @Test
    fun `gate fires when sinceBuild is HIGHER than the resolver baseline`() {
        val error = assertThrows(AssertionError::class.java) {
            assertPluginCompatibilityFloor(
                sinceBuild = "253",
                resolverFloor = "252",
            )
        }

        assertTrue(
            "Expected clear sync-drift message, got: ${error.message}",
            error.message!!.contains("must move together"),
        )
    }

    @Test
    fun `gate fires when sinceBuild is LOWER than the resolver baseline`() {
        val error = assertThrows(AssertionError::class.java) {
            assertPluginCompatibilityFloor(
                sinceBuild = "251",
                resolverFloor = "252",
            )
        }

        assertTrue(
            "Expected clear sync-drift message, got: ${error.message}",
            error.message!!.contains("must move together"),
        )
    }

    private fun parseSinceBuildFromGradleScript(): String {
        val scriptFile = listOf(
            File("ij-plugin/build.gradle.kts"),
            File("build.gradle.kts"),
            File("../ij-plugin/build.gradle.kts"),
        ).firstOrNull { candidate ->
            candidate.isFile && candidate.readText().contains("pluginConfiguration")
        } ?: error(
            "ij-plugin/build.gradle.kts not found from working directory " +
                File(".").absoluteFile.normalize(),
        )

        val script = scriptFile.readText()
        val match = Regex("(?m)^\\s*sinceBuild\\s*=\\s*\"([^\"]+)\"").find(script)
            ?: error("sinceBuild not found in ${scriptFile.path}")
        return match.groupValues[1]
    }
}

internal fun assertPluginCompatibilityFloor(
    sinceBuild: String,
    resolverFloor: String,
) {
    assertEquals(
        "ij-plugin/build.gradle.kts `sinceBuild` and " +
            "intellij-downloader's `MANAGED_BACKEND_MIN_SUPPORTED_BUILD` must move " +
            "together. Updating one without the other risks a managed backend that " +
            "installs the plugin but cannot load it.",
        resolverFloor,
        sinceBuild,
    )
}
