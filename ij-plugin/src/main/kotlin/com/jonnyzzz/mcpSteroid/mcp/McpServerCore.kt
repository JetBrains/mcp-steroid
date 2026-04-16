/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.*

/**
 * Core MCP server logic that handles JSON-RPC message dispatch.
 * Independent of transport layer.
 */
class McpServerCore(
    val serverInfo: ServerInfo,
    private val capabilities: ServerCapabilities,
    private val instructions: String? = null,
) {
    private val log = thisLogger()
    val sessionManager = McpSessionManager()
    val toolRegistry = McpToolRegistry()
    val resourceRegistry = McpResourceRegistry()
    val promptRegistry = McpPromptRegistry()

    /**
     * Handle an incoming JSON-RPC message and return a response (if applicable).
     * Returns null for notifications that don't require a response.
     */
    suspend fun handleMessage(message: String, session: McpSession): String? {
        val jsonElement = try {
            McpJson.parseToJsonElement(message)
        } catch (e: Exception) {
            return encodeError(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")
        }

        return when (jsonElement) {
            is JsonArray -> handleBatch(jsonElement, session)
            is JsonObject -> handleSingle(jsonElement, session)
            else -> encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid request")
        }
    }

    private suspend fun handleBatch(batch: JsonArray, session: McpSession): String {
        val responses = batch.mapNotNull { element ->
            if (element is JsonObject) {
                handleSingle(element, session)?.let { McpJson.parseToJsonElement(it) }
            } else {
                McpJson.parseToJsonElement(
                    encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid request in batch")
                )
            }
        }
        return McpJson.encodeToString(JsonArray.serializer(), JsonArray(responses))
    }

    private suspend fun handleSingle(json: JsonObject, session: McpSession): String? {
        val id = json["id"]
        val method = json["method"]?.jsonPrimitive?.contentOrNull

        // Check if this is a notification (no id)
        if (id == null) {
            if (method != null) {
                handleNotification(method)
            }
            return null
        }

        // Check if this is a response to a server-initiated request (has id but no method)
        if (method == null) {
            // This might be a response to a server request (like sampling)
            val idString = id.toString().trim('"')
            val result = json["result"]
            val error = json["error"]

            if (result != null) {
                if (session.handleResponse(idString, result)) {
                    // Successfully routed the response, no need to reply
                    return null
                }
            } else if (error != null) {
                val rpcError = try {
                    McpJson.decodeFromJsonElement<JsonRpcError>(error)
                } catch (_: Exception) {
                    JsonRpcError(code = -1, message = "Unknown error")
                }
                if (session.handleErrorResponse(idString, rpcError)) {
                    return null
                }
            }

            return encodeError(id, JsonRpcErrorCodes.INVALID_REQUEST, "Missing method")
        }

        return handleRequest(id, method, json["params"]?.jsonObject, session)
    }

    private suspend fun handleRequest(
        id: JsonElement,
        method: String,
        params: JsonObject?,
        session: McpSession
    ): String {
        return when (method) {
            McpMethods.INITIALIZE -> handleInitialize(id, params, session)
            McpMethods.PING -> handlePing(id)
            McpMethods.TOOLS_LIST -> handleToolsList(id)
            McpMethods.TOOLS_CALL -> handleToolsCall(id, params, session)
            McpMethods.PROMPTS_LIST -> handlePromptsList(id, params)
            McpMethods.PROMPTS_GET -> handlePromptsGet(id, params)
            McpMethods.RESOURCES_LIST -> handleResourcesList(id)
            McpMethods.RESOURCES_READ -> handleResourcesRead(id, params)
            else -> encodeError(id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun handleNotification(method: String) {
        log.info("Client notification: $method")
        when (method) {
            McpMethods.INITIALIZED -> {
                // Client confirmed initialization - nothing special needed
            }
            // Handle other notifications as needed
        }
    }

    private fun handleInitialize(id: JsonElement, params: JsonObject?, session: McpSession): String {
        val initParams = try {
            params?.let { McpJson.decodeFromJsonElement<InitializeParams>(it) }
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid initialize params: ${e.message}")
        }

        if (initParams != null) {
            session.markInitialized(initParams.clientInfo, initParams.capabilities)
        }

        val result = InitializeResult(
            protocolVersion = MCP_PROTOCOL_VERSION,
            capabilities = capabilities,
            serverInfo = serverInfo,
            instructions = instructions
        )

        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handlePing(id: JsonElement): String {
        return encodeResult(id, JsonObject(emptyMap()))
    }

    private fun handleToolsList(id: JsonElement): String {
        val tools = toolRegistry.listTools()
        val result = ToolsListResult(tools = tools)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private suspend fun handleToolsCall(id: JsonElement, params: JsonObject?, session: McpSession): String {
        if (params == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing tool call params")
        }

        val callParams = try {
            McpJson
                .decodeFromJsonElement<ToolCallParams>(params)
                .copy(rawArguments = params)
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid tool call params: ${e.message}")
        }

        val result = toolRegistry.callTool(callParams, session)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handleResourcesList(id: JsonElement): String {
        val resources = resourceRegistry.listResources()
        log.info("MCP resources/list: ${resources.size} resources")
        val result = ResourcesListResult(resources = resources)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handlePromptsList(id: JsonElement, params: JsonObject?): String {
        if (params != null) {
            try {
                McpJson.decodeFromJsonElement<PromptsListParams>(params)
            } catch (e: Exception) {
                return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid prompts list params: ${e.message}")
            }
        }
        val prompts = promptRegistry.listPrompts()
        val result = PromptsListResult(prompts = prompts)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handlePromptsGet(id: JsonElement, params: JsonObject?): String {
        if (params == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing prompt get params")
        }

        val getParams = try {
            McpJson.decodeFromJsonElement<PromptGetParams>(params)
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid prompt get params: ${e.message}")
        }

        val result = promptRegistry.getPrompt(getParams)
            ?: return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Prompt not found: ${getParams.name}")

        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handleResourcesRead(id: JsonElement, params: JsonObject?): String {
        if (params == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing resource read params")
        }

        val readParams = try {
            McpJson.decodeFromJsonElement<ResourceReadParams>(params)
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid resource read params: ${e.message}")
        }

        val result = resourceRegistry.readResource(readParams.uri)
            ?: return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Resource not found: ${readParams.uri}")

        log.info("MCP resource read: ${readParams.uri}")

        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun encodeResult(id: JsonElement, result: JsonElement): String {
        val response = JsonRpcResponse(id = id, result = result)
        return McpJson.encodeToString(JsonRpcResponse.serializer(), response)
    }

    private fun encodeError(id: JsonElement, code: Int, message: String): String {
        val response = JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
        return McpJson.encodeToString(JsonRpcResponse.serializer(), response)
    }

    /**
     * Send tools/list_changed notification to all sessions.
     * Currently unused but part of MCP protocol API for future use.
     */
    @Suppress("unused")
    fun notifyToolsListChanged() {
        val notification = JsonRpcNotification(method = McpMethods.TOOLS_LIST_CHANGED)
        sessionManager.getAllSessions().forEach { it.sendNotification(notification) }
    }
}
