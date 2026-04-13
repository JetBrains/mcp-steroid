/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase

@Suppress("UnusedReceiverParameter")
fun BasePlatformTestCase.resolveDockerUrl(): String {
    // Docker on macOS runs in a VM, so localhost inside container != host's localhost
    // Use host.docker.internal to access the host from inside Docker
    val mcpUrl = McpTestUtil.getSseUrlIfRunning()
    val dockerUrl = mcpUrl.replace("localhost", "host.docker.internal")
        .replace("127.0.0.1", "host.docker.internal")
    println("[TEST] MCP URL: $mcpUrl -> Docker URL: $dockerUrl")
    return dockerUrl
}
