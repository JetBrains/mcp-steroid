/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the predicate used by [resolveTargetArchive] to decide whether a
 * channel-mismatch lookup failure is eligible for the auto-retry on the
 * other channel. Only exact `NNN.X.Y(.Z)?` build numbers qualify — version
 * strings like `"2026.1"` and matrix tags like `"262-EAP-SNAPSHOT"` use
 * their own resolution paths.
 */
class IsExactBuildNumberTest {

    @Test fun `accepts standard 3-segment build`() {
        assertTrue(isExactBuildNumber("262.6228.19"))
        assertTrue(isExactBuildNumber("261.24374.151"))
    }

    @Test fun `accepts 4-segment patch build`() {
        assertTrue(isExactBuildNumber("261.24374.151.42"))
    }

    @Test fun `rejects public version strings`() {
        assertFalse(isExactBuildNumber("2026.1"))
        assertFalse(isExactBuildNumber("2026.1.2"))
    }

    @Test fun `rejects matrix EAP tag`() {
        assertFalse(isExactBuildNumber("262-EAP-SNAPSHOT"))
    }

    @Test fun `rejects bare per-major snapshot`() {
        assertFalse(isExactBuildNumber("262-SNAPSHOT"))
    }

    @Test fun `rejects empty and blank`() {
        assertFalse(isExactBuildNumber(""))
        assertFalse(isExactBuildNumber("   "))
    }
}
