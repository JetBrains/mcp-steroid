/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that the MCP Sampling protocol DTOs (spec 2025-11-25) round-trip through
 * [McpJson] without losing fields, regardless of the transport.
 *
 * Ported from `ij-plugin/.../McpSamplingIntegrationTest.testSamplingProtocolTypes`
 * to remove the IntelliJ Platform / Ktor dependency from a pure-protocol assertion.
 */
class McpSamplingProtocolTypesTest {

    @Test
    fun `CreateMessageParams encodes all fields and round-trips`() {
        val params = CreateMessageParams(
            messages = listOf(
                SamplingMessage(
                    role = "user",
                    content = SamplingContent.Text(text = "Hello, world!")
                )
            ),
            systemPrompt = "You are a helpful assistant.",
            maxTokens = 100,
            modelPreferences = ModelPreferences(
                hints = listOf(ModelHint(name = "claude-sonnet-4-6")),
                intelligencePriority = 0.8,
                speedPriority = 0.5,
                costPriority = 0.3
            )
        )

        val json = McpJson.encodeToJsonElement(params)
        val obj = json.jsonObject

        assertTrue(obj.containsKey("messages"))
        assertTrue(obj.containsKey("systemPrompt"))
        assertTrue(obj.containsKey("maxTokens"))
        assertTrue(obj.containsKey("modelPreferences"))

        val decoded = McpJson.decodeFromJsonElement<CreateMessageParams>(json)
        assertEquals(1, decoded.messages.size)
        assertEquals("user", decoded.messages[0].role)
        assertTrue(decoded.messages[0].content is SamplingContent.Text)
        assertEquals(
            "Hello, world!",
            (decoded.messages[0].content as SamplingContent.Text).text
        )
        assertEquals("You are a helpful assistant.", decoded.systemPrompt)
        assertEquals(100, decoded.maxTokens)
    }

    @Test
    fun `CreateMessageResult round-trips with assistant role and text content`() {
        val result = CreateMessageResult(
            role = "assistant",
            content = SamplingContent.Text(text = "Hello! How can I help you today?"),
            model = "claude-sonnet-4-6-20260101",
            stopReason = "endTurn"
        )

        val json = McpJson.encodeToJsonElement(result)
        val decoded = McpJson.decodeFromJsonElement<CreateMessageResult>(json)

        assertEquals("assistant", decoded.role)
        assertTrue(decoded.content is SamplingContent.Text)
        assertEquals(
            "Hello! How can I help you today?",
            (decoded.content as SamplingContent.Text).text
        )
        assertEquals("claude-sonnet-4-6-20260101", decoded.model)
        assertEquals("endTurn", decoded.stopReason)
    }

    @Test
    fun `SamplingContent Image round-trips with data and mimeType`() {
        val image: SamplingContent = SamplingContent.Image(
            data = "QkFTRTY0SU1BR0VEQVRB",
            mimeType = "image/png"
        )

        val json = McpJson.encodeToJsonElement(image)
        val decoded = McpJson.decodeFromJsonElement<SamplingContent>(json)

        assertTrue(decoded is SamplingContent.Image)
        assertEquals("QkFTRTY0SU1BR0VEQVRB", (decoded as SamplingContent.Image).data)
        assertEquals("image/png", decoded.mimeType)
    }

    @Test
    fun `McpMethods SAMPLING_CREATE_MESSAGE constant matches spec`() {
        assertEquals("sampling/createMessage", McpMethods.SAMPLING_CREATE_MESSAGE)
    }
}
