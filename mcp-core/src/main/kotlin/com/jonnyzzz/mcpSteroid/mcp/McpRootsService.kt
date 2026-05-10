/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Service for managing MCP Roots per session.
 *
 * Roots define filesystem boundaries that clients expose to servers.
 * Per MCP 2025-11-25 specification: https://modelcontextprotocol.io/specification/2025-11-25/client/roots
 *
 * This service:
 * - Requests roots from clients via roots/list
 * - Caches roots per session
 * - Handles roots/list_changed notifications
 * - Refreshes cached roots when notified of changes
 */
class McpRootsService {
    private val log = thisLogger()
    private val rootsCache = ConcurrentHashMap<String, List<Root>>()

    /**
     * Get roots for a session, requesting from client if not cached.
     * Returns null if client doesn't support roots or request fails.
     */
    suspend fun getRoots(session: McpSession, forceRefresh: Boolean = false): List<Root>? {
        if (!session.supportsRoots()) {
            log.debug("[Roots] Session ${session.id} does not support roots capability")
            return null
        }

        // Return cached if available and not forcing refresh
        if (!forceRefresh) {
            rootsCache[session.id]?.let { cached ->
                log.debug("[Roots] Returning ${cached.size} cached roots for session ${session.id}")
                return cached
            }
        }

        // Request roots from client
        log.info("[Roots] Requesting roots from client for session ${session.id}")

        val result = try {
            session.sendRequest(
                method = McpMethods.ROOTS_LIST,
                params = buildJsonObject { }, // roots/list takes no params
                timeout = 30.seconds
            )
        } catch (e: Exception) {
            log.warn("[Roots] Failed to request roots for session ${session.id}", e)
            return null
        }

        if (result == null) {
            log.warn("[Roots] Client did not respond to roots/list for session ${session.id}")
            return null
        }

        // Parse response
        val rootsResult = try {
            McpJson.decodeFromJsonElement(RootsListResult.serializer(), result)
        } catch (e: Exception) {
            log.error("[Roots] Failed to parse roots/list response for session ${session.id}", e)
            return null
        }

        log.info("[Roots] Received ${rootsResult.roots.size} roots for session ${session.id}")
        rootsResult.roots.forEachIndexed { i, root ->
            log.debug("[Roots]   [$i] ${root.name ?: "Unnamed"}: ${root.uri}")
        }

        // Cache the result
        rootsCache[session.id] = rootsResult.roots
        return rootsResult.roots
    }

    /**
     * Handle roots/list_changed notification from client.
     * Clears cached roots for the session to force refresh on next getRoots().
     */
    fun handleRootsListChanged(session: McpSession) {
        if (!session.supportsRootsListChanged()) {
            log.warn("[Roots] Session ${session.id} sent roots/list_changed but didn't declare listChanged capability")
        }

        log.info("[Roots] Roots list changed for session ${session.id}, clearing cache")
        rootsCache.remove(session.id)
    }

    /**
     * Clear cached roots for a session (e.g., when session closes).
     */
    fun clearCache(sessionId: String) {
        rootsCache.remove(sessionId)
        log.debug("[Roots] Cleared roots cache for session $sessionId")
    }

    /**
     * Clear all cached roots.
     */
    fun clearAllCaches() {
        val count = rootsCache.size
        rootsCache.clear()
        log.info("[Roots] Cleared all roots caches ($count sessions)")
    }

    /**
     * Get cached roots without requesting from client.
     * Returns null if not cached.
     */
    fun getCachedRoots(sessionId: String): List<Root>? {
        return rootsCache[sessionId]
    }

    /**
     * Check if roots are cached for a session.
     */
    fun hasCachedRoots(sessionId: String): Boolean {
        return rootsCache.containsKey(sessionId)
    }

    /**
     * Get number of sessions with cached roots.
     */
    fun getCacheSize(): Int {
        return rootsCache.size
    }
}
