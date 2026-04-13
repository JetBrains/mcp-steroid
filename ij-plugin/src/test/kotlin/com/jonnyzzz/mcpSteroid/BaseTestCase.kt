/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

fun BasePlatformTestCase.setSystemPropertyForTest(name: String, value: String) {
    val oldValue = System.setProperty(name, value)
    Disposer.register(testRootDisposable, Disposable {
        if (oldValue != null) {
            System.setProperty(name, oldValue)
        } else {
            System.clearProperty(name)
        }
    })
}

fun BasePlatformTestCase.setServerPortProperties() {
    // Bind MCP server to 0.0.0.0 so Docker containers can reach it via host.docker.internal
    setSystemPropertyForTest("mcp.steroid.server.host", "0.0.0.0")
    // Allow CI/release-builder to override the test port to avoid host port conflicts.
    val testPort = System.getenv("MCP_STEROID_TEST_PORT")
        ?.takeIf { it.isNotBlank() }
        ?: "17820"
    setSystemPropertyForTest("mcp.steroid.server.port", testPort)
    // Disable review mode for tests
    setSystemPropertyForTest("mcp.steroid.review.mode", "NEVER")
}
