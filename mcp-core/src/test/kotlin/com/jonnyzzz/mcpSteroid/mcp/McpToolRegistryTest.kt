/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McpToolRegistryTest {

    @Test
    fun `progress reporters keep concurrent progress tokens isolated`() = runBlocking {
        val registry = McpToolRegistry()
        registry.registerTool(object : McpTool {
            override val name = "report_progress"
            override val description = "Reports one progress message"
            override val inputSchema = buildJsonObject { put("type", "object") }

            override suspend fun call(context: ToolCallContext): ToolCallResult {
                val label = context.params.arguments["label"]?.jsonPrimitive?.contentOrNull
                    ?: error("missing label")
                context.mcpProgressReporter.report(label)
                return ToolCallResult(content = listOf(ContentItem.Text("done-$label")))
            }
        })
        val session = McpSession()

        coroutineScope {
            awaitAll(
                async { registry.callTool(toolCallParams("token-a", "first"), session) },
                async { registry.callTool(toolCallParams("token-b", "second"), session) },
            )
        }

        val progressByToken = session.drainNotifications()
            .filter { it.method == McpMethods.PROGRESS }
            .map { notification ->
                val params = notification.params ?: error("missing progress params")
                McpJson.decodeFromJsonElement(ProgressParams.serializer(), params)
            }
            .groupBy { it.progressToken.jsonPrimitive.content }

        assertEquals(setOf("token-a", "token-b"), progressByToken.keys)
        assertEquals(listOf("first"), progressByToken.getValue("token-a").map { it.message })
        assertEquals(listOf("second"), progressByToken.getValue("token-b").map { it.message })
        assertEquals(listOf(1.0), progressByToken.getValue("token-a").map { it.progress })
        assertEquals(listOf(1.0), progressByToken.getValue("token-b").map { it.progress })
    }

    private fun toolCallParams(token: String, label: String): ToolCallParams {
        val arguments = buildJsonObject {
            put("label", label)
            putJsonObject("_meta") {
                put("progressToken", JsonPrimitive(token))
            }
        }
        return ToolCallParams(
            name = "report_progress",
            arguments = arguments,
            rawArguments = buildJsonObject {
                put("name", "report_progress")
                put("arguments", arguments)
            },
        )
    }
}
