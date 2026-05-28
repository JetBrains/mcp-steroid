/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@TestApplication
class KotlincRegistryDefaultsTest {
    @Test
    fun defaultKotlincParametersTargetKotlin22() {
        assertEquals(
            "-language-version 2.2 -api-version 2.2",
            Registry.stringValue("mcp.steroid.kotlinc.parameters")
        )
    }
}
