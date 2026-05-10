/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class McpSessionManagerTest {
    @Test
    fun `test forgetAllSessionsForTest closes and removes all sessions`() = runBlocking {
        withTimeout(10.seconds) {
            val manager = McpSessionManager()
            val session = manager.createSession()
            manager.createSession()

            val forgotten = manager.forgetAllSessionsForTest()

            assertEquals(2, forgotten, "Should forget all sessions")
            assertEquals(0, manager.getSessionCount(), "Manager should be empty after forget")

            // Session notification channel should be closed after forgetAllSessionsForTest.
            // Sending a notification to a closed session and trying to receive should
            // yield null (flow completes immediately because the channel is closed).
            session.sendNotification(JsonRpcNotification(method = "notifications/test"))
            val received = withTimeoutOrNull(1.seconds) { session.notifications().firstOrNull() }
            assertNull(received, "Session should be closed after forgetAllSessionsForTest")
        }
    }
}
