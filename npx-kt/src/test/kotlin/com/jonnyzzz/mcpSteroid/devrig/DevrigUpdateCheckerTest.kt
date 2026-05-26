/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DevrigUpdateCheckerTest {
    @Test
    fun `checkForUpdates fetches`() = runTest {
        val info = fetchVersionInfo()

        assertNotNull(info, "It should have base version")
    }

}
