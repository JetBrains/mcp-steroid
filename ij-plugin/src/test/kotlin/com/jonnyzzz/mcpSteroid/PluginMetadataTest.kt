/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginMetadataTest : BasePlatformTestCase() {
    fun testVersion() {
        val version = getPluginVersion()
        println("Version: $version")
    }
}