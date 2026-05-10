/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.mcp.JsonRpcErrorCodes
import com.jonnyzzz.mcpSteroid.mcp.MCP_PROTOCOL_VERSION
import com.jonnyzzz.mcpSteroid.mcp.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun createServerInfo(config: ProxyConfig): JsonObject = buildJsonObject {
    put("name", "mcp-steroid-proxy")
    put("version", config.version ?: "0.1.0")
}

fun extractClientProgressToken(args: JsonObject?): Any? {
    val meta = args?.get("_meta") as? JsonObject ?: return null
    val token = meta["progressToken"] ?: return null
    return token.jsonPrimitive.contentOrNull
}

fun createProgressToken(): String =
    "npx-${System.currentTimeMillis().toString(36)}-${(Math.random() * Long.MAX_VALUE).toLong().toString(36)}"

suspend fun handleRpc(
    method: String,
    params: JsonObject,
    registry: ServerRegistry,
    beacon: NpxBeacon?,
    notify: (suspend (String, JsonObject) -> Unit)? = null
): JsonObject {
    if (method != "initialize" && method != "ping") {
        registry.ensureFresh()
    }

    return when (method) {
        "initialize" -> buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
                put("prompts", buildJsonObject { put("listChanged", false) })
                put("resources", buildJsonObject {
                    put("subscribe", false)
                    put("listChanged", false)
                })
            })
            put("serverInfo", createServerInfo(registry.config))
            put("instructions", "Proxy MCP server for MCP Steroid instances discovered from local IDE metadata.")
        }

        "ping" -> buildJsonObject {  }

        "tools/list" -> {
            val groups = registry.buildToolGroups()
            buildJsonObject { put("tools", kotlinx.serialization.json.JsonArray(mergeToolGroups(groups))) }
        }

        "resources/list" -> {
            val resources = registry.buildResourceIndex()
            buildJsonObject { put("resources", kotlinx.serialization.json.JsonArray(resources)) }
        }

        "resources/read" -> {
            val uri = params["uri"]?.jsonPrimitive?.contentOrNull
                ?: throw RpcException("Missing uri", JsonRpcErrorCodes.INVALID_PARAMS)

            registry.buildResourceIndex()
            val alias = parseAliasUri(uri)
            val lookupUri = alias?.uri ?: uri
            val serverIds = alias?.let { listOf(it.serverId) }
                ?: (registry.resourceIndex[lookupUri] ?: emptyList())
            val serverId = serverIds.firstOrNull()
                ?: throw RpcException("Resource not found: $uri", JsonRpcErrorCodes.INVALID_PARAMS)

            registry.callRpc(serverId, "resources/read", buildJsonObject { put("uri", lookupUri) })
        }

        "tools/call" -> {
            val toolName = params["name"]?.jsonPrimitive?.contentOrNull
            val args = params["arguments"] as? JsonObject ?: buildJsonObject {  }

            val captureToolCall = { result: JsonObject, route: String, extra: Map<String, Any> ->
                beacon?.capture(BeaconEvents.TOOL_CALL, mapOf(
                    "tool_name" to (toolName ?: "<missing>"),
                    "route" to route,
                    "status" to (if (result["isError"]?.jsonPrimitive?.contentOrNull?.lowercase() == "true") "error" else "ok")
                ) + extra)
                result
            }

            if (toolName == null) {
                return captureToolCall(makeToolError("Missing tool name"), "invalid", emptyMap())
            }

            if (toolName == AGGREGATE_TOOL_PROJECTS) {
                return captureToolCall(handleAggregateProjects(registry, args), "aggregate", emptyMap())
            }
            if (toolName == AGGREGATE_TOOL_WINDOWS) {
                return captureToolCall(handleAggregateWindows(registry, args), "aggregate", emptyMap())
            }

            val resolved = registry.resolveServerForToolCall(toolName, args)
            if (resolved is RoutingResult.Error) {
                return captureToolCall(makeToolError(resolved.message), "resolve", emptyMap())
            }
            val (serverId, resolvedToolName) = resolved as RoutingResult.Resolved

            val progressToken = extractClientProgressToken(params) ?: createProgressToken()
            var lastProgress = 0.0

            val emitProgress: suspend (Double?, String?, Double?) -> Unit = { progress, message, total ->
                if (notify != null) {
                    val safeProgress = if (progress != null && progress.isFinite()) progress else lastProgress
                    if (safeProgress.isFinite()) lastProgress = safeProgress
                    val progressPayload = buildJsonObject {
                        put("progressToken", progressToken.toString())
                        put("progress", lastProgress)
                        if (!message.isNullOrBlank()) put("message", message)
                        if (total != null && total.isFinite()) put("total", total)
                    }
                    notify("notifications/progress", progressPayload)
                }
            }

            emitProgress(0.0, "Tool call started: $resolvedToolName", null)
            val delegated = registry.callTool(serverId, resolvedToolName, args) { event ->
                when (event["type"]?.jsonPrimitive?.contentOrNull) {
                    "progress" -> emitProgress(
                        event["progress"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                        event["message"]?.jsonPrimitive?.contentOrNull,
                        event["total"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    )
                    "heartbeat" -> emitProgress(
                        null,
                        event["message"]?.jsonPrimitive?.contentOrNull ?: "Tool call heartbeat",
                        null
                    )
                }
            }
            emitProgress(1.0, "Tool call completed: $resolvedToolName", null)
            captureToolCall(delegated, "delegated", mapOf("server_id" to serverId))
        }

        else -> throw RpcException("Method not found: $method", JsonRpcErrorCodes.METHOD_NOT_FOUND)
    }
}
