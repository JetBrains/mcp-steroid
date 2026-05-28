/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.time.Duration.Companion.seconds

class AnalyticsBeaconTest : BasePlatformTestCase() {
    fun testCaptureDoesNotThrow() = timeoutRunBlocking(10.seconds) {
        repeat(30) {
            analyticsBeacon.sendEventInternal("test_event_$it", mapOf("key" to "value"))
        }
        analyticsBeacon.flush()
    }
}
