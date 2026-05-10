/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

fun ToolCallResult.Companion.builder() = ToolCallBuilder()

class ToolCallBuilder {
    private val contents = mutableListOf<ContentItem>()
    private var isError = false

    fun addTextContent(content: String): ToolCallBuilder = addContent(ContentItem.Text(content))
    fun addContent(content: ContentItem): ToolCallBuilder = apply {
        contents += content
    }

    fun markAsError(): ToolCallBuilder = apply {
        isError = true
    }

    fun build(): ToolCallResult {
        // Always merge all text items into a single ContentItem.Text.
        // Non-text items (images, resources) are kept as separate content items.
        val textParts = contents.filterIsInstance<ContentItem.Text>()
        val nonTextParts = contents.filter { it !is ContentItem.Text }

        val merged = mutableListOf<ContentItem>()
        if (textParts.isNotEmpty()) {
            merged += ContentItem.Text(textParts.joinToString("\n") { it.text })
        }
        merged += nonTextParts

        return ToolCallResult(
            content = merged,
            isError = isError,
        )
    }
}
