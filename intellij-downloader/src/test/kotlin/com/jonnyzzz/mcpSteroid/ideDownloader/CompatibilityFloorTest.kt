/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityFloorTest {
    @Test
    fun `managed backend minimum supported build is a baseline number`() {
        assertTrue(
            "MANAGED_BACKEND_MIN_SUPPORTED_BUILD must be a baseline build like 261, not a full build string",
            Regex("\\d{3}").matches(MANAGED_BACKEND_MIN_SUPPORTED_BUILD),
        )
    }
}
