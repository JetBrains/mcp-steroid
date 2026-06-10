/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OpenProjectParamsTest {
    @Test
    fun `backendName defaults to null and decodes when absent`() {
        val decoded = McpJson.decodeFromString(
            OpenProjectParams.serializer(),
            """{"projectPath":"/tmp/p","trustProject":true}""",
        )
        assertNull(decoded.backendName)
    }

    @Test
    fun `backendName round-trips when present`() {
        val params = OpenProjectParams(projectPath = "/tmp/p", trustProject = false, backendName = "pid-1234")
        val json = McpJson.encodeToString(OpenProjectParams.serializer(), params)
        val decoded = McpJson.decodeFromString(OpenProjectParams.serializer(), json)
        assertEquals("pid-1234", decoded.backendName)
    }

    @Test
    fun `backendName null round-trips (key omitted by explicitNulls=false)`() {
        val params = OpenProjectParams(projectPath = "/tmp/p", trustProject = true, backendName = null)
        val json = McpJson.encodeToString(OpenProjectParams.serializer(), params)
        // McpJson has explicitNulls=false, so the key is omitted, keeping the wire additive.
        assertFalse(json.contains("backendName"))
        assertNull(McpJson.decodeFromString(OpenProjectParams.serializer(), json).backendName)
    }
}
