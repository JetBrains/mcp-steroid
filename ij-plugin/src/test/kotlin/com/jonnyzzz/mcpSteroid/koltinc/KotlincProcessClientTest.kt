/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@TestApplication
class KotlincProcessClientTest {

    @Test
    fun kotlincVersion(): Unit = timeoutRunBlocking(30.seconds) {
        val output = kotlincProcessClient.kotlinc(listOf("-version"))
        val text = (output.stdout + "\n" + output.stderr).trim()
        assertTrue(text.contains("kotlin", ignoreCase = true), "Expected kotlinc version output, got: $text")
    }
}
