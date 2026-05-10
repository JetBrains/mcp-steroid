/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger


/**
 * Registry for MCP resources.
 */
class McpResourceRegistry : McpResourceRegistrar, McpResourceReader {
    private val log = thisLogger()

    private val resources = mutableMapOf<String, McpResourceDefinition>()

    /**
     * Register a resource with its content provider (single content item).
     */
    override fun registerResource(
        uri: String,
        name: String,
        description: String?,
        mimeType: String,
        contentProvider: () -> String
    ) {
        resources[uri] = McpResourceDefinition(
            resource = Resource(
                uri = uri,
                name = name,
                description = description,
                mimeType = mimeType
            ),
            contentsProvider = {
                listOf(
                    ResourceContent(
                        uri = uri,
                        mimeType = mimeType,
                        text = contentProvider()
                    )
                )
            }
        )
        log.info("Registered MCP resource: $uri ($name)")
    }

    /**
     * Register a resource with a multi-content provider (e.g., payload + see-also).
     */
    fun registerResourceMultiContent(
        uri: String,
        name: String,
        description: String?,
        mimeType: String = "text/plain",
        contentsProvider: () -> List<ResourceContent>
    ) {
        resources[uri] = McpResourceDefinition(
            resource = Resource(
                uri = uri,
                name = name,
                description = description,
                mimeType = mimeType
            ),
            contentsProvider = contentsProvider
        )
        log.info("Registered MCP resource: $uri ($name)")
    }

    /**
     * Get all registered resources.
     */
    fun listResources(): List<Resource> = resources.values.map { it.resource }

    /**
     * Read a resource by URI.
     */
    override fun readResource(uri: String): ResourceReadResult? {
        val definition = resources[uri] ?: return null

        val contents = try {
            definition.contentsProvider()
        } catch (e: Exception) {
            log.warn("Failed to read resource $uri: ${e.message}", e)
            return null
        }

        return ResourceReadResult(contents = contents)
    }
}

/**
 * Internal representation of a registered resource with its contents provider.
 */
private data class McpResourceDefinition(
    val resource: Resource,
    val contentsProvider: () -> List<ResourceContent>
)
