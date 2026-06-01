/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpStdioJsonRpcTest {

    // -------------------------------------------------------------------------
    // jsonRpcResult
    // -------------------------------------------------------------------------

    @Test
    fun `result contains jsonrpc version`() {
        val response = jsonRpcResult("1", JsonObject(emptyMap()))
        assertEquals(JSONRPC_VERSION, response["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `result preserves string id`() {
        val response = jsonRpcResult("abc-123", JsonObject(emptyMap()))
        assertEquals("abc-123", response["id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `result preserves Long id as numeric`() {
        val response = jsonRpcResult(42L, JsonObject(emptyMap()))
        val idPrim = response["id"] as JsonPrimitive
        assertEquals(42L, idPrim.content.toLong())
    }

    @Test
    fun `result preserves Int id coerced to Long`() {
        val response = jsonRpcResult(7, JsonObject(emptyMap()))
        val idPrim = response["id"] as JsonPrimitive
        assertEquals(7L, idPrim.content.toLong())
    }

    @Test
    fun `result preserves Double id truncated to Long`() {
        val response = jsonRpcResult(3.9, JsonObject(emptyMap()))
        val idPrim = response["id"] as JsonPrimitive
        assertEquals(3L, idPrim.content.toLong())
    }

    @Test
    fun `result with null id uses JsonNull`() {
        val response = jsonRpcResult(null, JsonObject(emptyMap()))
        assertEquals(JsonNull, response["id"])
    }

    @Test
    fun `result with unsupported id type falls back to toString`() {
        data class CustomId(val v: String) { override fun toString(): String = "custom:$v" }
        val response = jsonRpcResult(CustomId("42"), JsonObject(emptyMap()))
        assertEquals("custom:42", response["id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `result embeds the provided result object`() {
        val inner = buildJsonObject {
            put("tools", "ok")
            put("count", 3)
        }
        val response = jsonRpcResult("1", inner)
        val embedded = response["result"] as JsonObject
        assertEquals("ok", embedded["tools"]?.jsonPrimitive?.content)
        assertEquals("3", embedded["count"]?.jsonPrimitive?.content)
    }

    @Test
    fun `result has no error field`() {
        val response = jsonRpcResult("1", JsonObject(emptyMap()))
        assertNull(response["error"])
    }

    // -------------------------------------------------------------------------
    // jsonRpcError
    // -------------------------------------------------------------------------

    @Test
    fun `error contains jsonrpc version`() {
        val response = jsonRpcError("1", JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found")
        assertEquals(JSONRPC_VERSION, response["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `error embeds code and message`() {
        val response = jsonRpcError("1", JsonRpcErrorCodes.INVALID_PARAMS, "Missing uri")
        val err = response["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, err["code"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Missing uri", err["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `error preserves id types identically to result`() {
        assertEquals("x", (jsonRpcError("x", 1, "m")["id"] as JsonPrimitive).content)
        assertEquals(JsonNull, jsonRpcError(null, 1, "m")["id"])
        assertEquals("9", (jsonRpcError(9L, 1, "m")["id"] as JsonPrimitive).content)
    }

    @Test
    fun `error has no result field`() {
        val response = jsonRpcError("1", -1, "oops")
        assertNull(response["result"])
    }

    @Test
    fun `error accepts all standard JsonRpcErrorCodes`() {
        val codes = listOf(
            JsonRpcErrorCodes.PARSE_ERROR,
            JsonRpcErrorCodes.INVALID_REQUEST,
            JsonRpcErrorCodes.METHOD_NOT_FOUND,
            JsonRpcErrorCodes.INVALID_PARAMS,
            JsonRpcErrorCodes.INTERNAL_ERROR,
        )
        for (code in codes) {
            val response = jsonRpcError("1", code, "m")
            val err = response["error"] as JsonObject
            assertEquals(code, err["code"]?.jsonPrimitive?.content?.toInt())
        }
    }

    // -------------------------------------------------------------------------
    // RpcException
    // -------------------------------------------------------------------------

    @Test
    fun `RpcException carries message and code`() {
        val e = RpcException("boom", JsonRpcErrorCodes.INTERNAL_ERROR)
        assertEquals("boom", e.message)
        assertEquals(JsonRpcErrorCodes.INTERNAL_ERROR, e.code)
    }

    @Test
    fun `RpcException is throwable and catchable`() {
        val caught = try {
            throw RpcException("method x missing", JsonRpcErrorCodes.METHOD_NOT_FOUND)
        } catch (e: RpcException) {
            e
        }
        assertNotNull(caught)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, caught.code)
    }
}
