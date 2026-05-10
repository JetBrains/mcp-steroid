/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the MCP Roots capability (spec 2025-11-25).
 *
 * Roots logic is transport-agnostic — these tests live in `:mcp-core` so they exercise
 * the dispatcher and session layer directly without spinning up an HTTP/stdio harness.
 *
 * Ported from `ij-plugin/.../McpRootsServiceTest.kt` so that it does not require the
 * IntelliJ Platform test framework (`UsefulTestCase`, `BasePlatformTestCase`) to run.
 */
class McpRootsServiceTest {

    @Test
    fun `supportsRoots is false when client did not declare roots`() {
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities() // no roots
        )

        assertFalse(session.supportsRoots())
        assertFalse(session.supportsRootsListChanged())
    }

    @Test
    fun `supportsRoots is true when client declared roots without listChanged`() {
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities(
                roots = RootsCapability(listChanged = false)
            )
        )

        assertTrue(session.supportsRoots())
        assertFalse(session.supportsRootsListChanged())
    }

    @Test
    fun `supportsRootsListChanged is true when client declared listChanged=true`() {
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities(
                roots = RootsCapability(listChanged = true)
            )
        )

        assertTrue(session.supportsRoots())
        assertTrue(session.supportsRootsListChanged())
    }

    @Test
    fun `getRoots returns null when client does not support roots`() = runBlocking {
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities() // no roots
        )

        val service = McpRootsService()
        val roots = service.getRoots(session)

        assertNull(roots)
        assertEquals(0, service.getCacheSize())
    }

    @Test
    fun `handleRootsListChanged clears cache for the session`() {
        val service = McpRootsService()
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities(
                roots = RootsCapability(listChanged = true)
            )
        )

        // Clear cache (which should be empty anyway).
        service.handleRootsListChanged(session)
        assertFalse(service.hasCachedRoots(session.id))
    }

    @Test
    fun `clearCache for unknown session id is a no-op`() {
        val service = McpRootsService()
        val session = McpSession()

        service.clearCache(session.id)
        assertFalse(service.hasCachedRoots(session.id))
    }

    @Test
    fun `clearAllCaches removes all cached roots`() {
        val service = McpRootsService()

        service.clearAllCaches()
        assertEquals(0, service.getCacheSize())
    }

    @Test
    fun `Root data class round-trips through McpJson`() {
        val root = Root(
            uri = "file:///home/user/project",
            name = "My Project"
        )

        val json = McpJson.encodeToJsonElement(Root.serializer(), root)
        val deserialized = McpJson.decodeFromJsonElement(Root.serializer(), json)

        assertEquals(root, deserialized)
    }

    @Test
    fun `Root with null name round-trips`() {
        val root = Root(
            uri = "file:///home/user/project",
            name = null
        )

        val json = McpJson.encodeToJsonElement(Root.serializer(), root)
        val deserialized = McpJson.decodeFromJsonElement(Root.serializer(), json)

        assertEquals(root, deserialized)
    }

    @Test
    fun `RootsListResult round-trips`() {
        val result = RootsListResult(
            roots = listOf(
                Root("file:///project1", "Project 1"),
                Root("file:///project2", "Project 2")
            )
        )

        val json = McpJson.encodeToJsonElement(RootsListResult.serializer(), result)
        val deserialized = McpJson.decodeFromJsonElement(RootsListResult.serializer(), json)

        assertEquals(result, deserialized)
        assertEquals(2, deserialized.roots.size)
    }

    @Test
    fun `RootsListResult with empty list round-trips`() {
        val result = RootsListResult(roots = emptyList())

        val json = McpJson.encodeToJsonElement(RootsListResult.serializer(), result)
        val deserialized = McpJson.decodeFromJsonElement(RootsListResult.serializer(), json)

        assertEquals(result, deserialized)
        assertTrue(deserialized.roots.isEmpty())
    }
}
