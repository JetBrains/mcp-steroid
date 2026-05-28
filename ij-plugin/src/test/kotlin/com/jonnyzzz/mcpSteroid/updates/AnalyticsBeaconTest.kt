/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@TestApplication
class AnalyticsBeaconTest {
    @BeforeEach
    fun whitelistPosthogThreads() {
        // PostHog spawns a Timer and PostHogQueueThread on first init. These are
        // long-running app-level threads owned by the application-scoped
        // AnalyticsBeacon service, not the test — register them as known so
        // ThreadLeakTracker (strict under @TestApplication) does not fail the test.
        // Tied to the Application disposable so the whitelist outlives the test
        // method's afterEach (where the ThreadLeakTracker extension runs).
        ThreadLeakTracker.longRunningThreadCreated(
            ApplicationManager.getApplication(),
            "Timer-",
            "PostHogQueueThread",
            "PostHogSendThread",
        )
    }

    @Test
    fun captureDoesNotThrow() = timeoutRunBlocking(10.seconds) {
        repeat(30) {
            analyticsBeacon.sendEventInternal("test_event_$it", mapOf("key" to "value"))
        }
        analyticsBeacon.flush()
    }
}
