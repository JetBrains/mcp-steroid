/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Build a JSON-RPC 2.0 success response as a [JsonObject]. Kept in the stdio module
 * because stdio servers typically encode responses to framed bytes directly (the typed
 * [JsonRpcResponse] in :mcp-core is for the HTTP transport which Ktor serialises for us).
 *
 * [id] accepts the raw JSON-RPC id: a String, a Long/Int/Double (coerced to Long), or null
 * (JsonNull). Other types fall back to `toString()`.
 */
fun jsonRpcResult(id: Any?, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", JSONRPC_VERSION)
    putId(id)
    put("result", result)
}

/**
 * Build a JSON-RPC 2.0 error response as a [JsonObject]. See [jsonRpcResult] for [id] rules.
 */
fun jsonRpcError(id: Any?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", JSONRPC_VERSION)
    putId(id)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
    })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putId(id: Any?) {
    when (id) {
        is String -> put("id", id)
        is Long -> put("id", id)
        is Int -> put("id", id.toLong())
        is Double -> put("id", id.toLong())
        null -> put("id", JsonNull)
        else -> put("id", id.toString())
    }
}

/**
 * Exception raised by an MCP JSON-RPC handler to signal a protocol error. [code] uses
 * constants from [JsonRpcErrorCodes] (e.g. [JsonRpcErrorCodes.METHOD_NOT_FOUND]).
 */
class RpcException(message: String, val code: Int) : Exception(message)
