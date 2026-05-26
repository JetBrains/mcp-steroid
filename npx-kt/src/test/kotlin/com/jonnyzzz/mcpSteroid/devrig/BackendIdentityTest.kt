/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BackendIdentityTest {
    @Test
    fun `backend stable id uses the natural source identifier`() {
        assertEquals("pid-4242", backendStableId(BackendRow.FromMarker(markerIde(pid = 4242L), emptyList())))
        assertEquals("port-65432", backendStableId(BackendRow.FromPort(portIde(port = 65432))))
        assertEquals(
            "idea-community-2025.2.6.2",
            backendStableId(BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2"))),
        )
    }

    private fun markerIde(pid: Long): DiscoveredIde {
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://127.0.0.1:6315/mcp",
                port = 6315,
                headers = emptyMap(),
            ),
            ide = IdeInfo(name = "IntelliJ IDEA", version = "2025.3.3", build = "IC-253.1"),
            plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0"),
            createdAt = "2026-05-14T21:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        return DiscoveredIde(pid, marker.mcpSteroidServer.mcpUrl, "/tmp/$pid.mcp-steroid", marker)
    }

    private fun portIde(port: Int) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = "IDEA",
        productFullName = "IntelliJ IDEA",
        edition = "Community",
        baselineVersion = 253,
        buildNumber = "IC-253.1",
    )

    private fun managedInfo(id: String) = ManagedBackendInfo(
        id = id,
        productKey = "idea-community",
        productCode = "IC",
        version = "2025.2.6.2",
        buildNumber = "IC-252.1",
        installPath = Path.of("/managed/$id"),
        cachePath = Path.of("/caches/$id"),
        runningPid = null,
        state = ManagedBackendState.INSTALLED,
    )
}
