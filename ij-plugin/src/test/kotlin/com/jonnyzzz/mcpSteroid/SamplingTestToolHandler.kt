/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpRequestException
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Test-only MCP tool that uses sampling to request LLM completions from the client.
 * This tool exists only in tests to verify the sampling protocol flow.
 */
class SamplingTestToolHandler : McpTool {

    override val name = TOOL_NAME

    override val description = """
        Test tool that requests an LLM completion from the client via MCP sampling.
        This tool demonstrates the sampling protocol flow where the server asks the
        client to generate a completion using the client's LLM.

        Parameters:
        - prompt: The text prompt to send to the LLM
        - system_prompt: Optional system prompt for the LLM

        Returns the LLM's response or an error if sampling is not supported.
    """.trimIndent()

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "The prompt to send to the LLM")
            }
            putJsonObject("system_prompt") {
                put("type", "string")
                put("description", "Optional system prompt")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val prompt = context.params.arguments["prompt"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text("Missing required parameter: prompt")),
                isError = true
            )

        val systemPrompt = context.params.arguments["system_prompt"]
            ?.let { (it as? JsonPrimitive)?.content }

        // Check if client supports sampling
        if (!context.supportsSampling()) {
            return ToolCallResult(
                content = listOf(
                    ContentItem.Text("SAMPLING_NOT_SUPPORTED: Client does not have sampling capability enabled")
                ),
                isError = true
            )
        }

        // Request completion from the client
        return try {
            val completion = context.requestCompletion(
                prompt = prompt,
                systemPrompt = systemPrompt,
            )

            if (completion != null) {
                ToolCallResult(
                    content = listOf(
                        ContentItem.Text("SAMPLING_SUCCESS: $completion")
                    ),
                    isError = false
                )
            } else {
                ToolCallResult(
                    content = listOf(
                        ContentItem.Text("SAMPLING_TIMEOUT: No response received from client")
                    ),
                    isError = true
                )
            }
        } catch (e: McpRequestException) {
            ToolCallResult(
                content = listOf(
                    ContentItem.Text("SAMPLING_ERROR: ${e.error.message}")
                ),
                isError = true
            )
        } catch (e: Exception) {
            ToolCallResult(
                content = listOf(
                    ContentItem.Text("SAMPLING_EXCEPTION: ${e.message}")
                ),
                isError = true
            )
        }
    }

    companion object {
        const val TOOL_NAME = "steroid_test_sampling"
    }
}
