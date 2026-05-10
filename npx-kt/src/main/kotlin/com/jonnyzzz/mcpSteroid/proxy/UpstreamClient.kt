/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.mcp.JSONRPC_VERSION
import com.jonnyzzz.mcpSteroid.mcp.MCP_PROTOCOL_VERSION
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.timeout
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RetryableException(message: String, val retryable: Boolean) : Exception(message)

class UpstreamClient(
    private val server: ServerEntry,
    private val config: ProxyConfig,
    private val traffic: TrafficLogger
) {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = config.upstreamTimeoutMs
            requestTimeoutMillis = Long.MAX_VALUE
        }
    }

    private var sessionId: String? = null
    private var initialized = false
    private var requestId = 0

    suspend fun ensureInitialized() {
        if (initialized) return
        val params = buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", "mcp-steroid-proxy")
                put("version", config.version ?: "0.1.0")
            })
        }
        val initResult = sendRequest("initialize", params, skipInit = true)
        val metadataPatch = metadataFromInitializeResult(initResult)
        if (metadataPatch != null) {
            server.metadata = mergeServerMetadata(server.metadata, metadataPatch)
        }
        sendNotification("notifications/initialized", buildJsonObject {  })
        initialized = true
    }

    suspend fun sendNotification(method: String, params: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("method", method)
            put("params", params)
        }
        sendPayload(payload, expectResponse = false)
    }

    suspend fun sendRequest(method: String, params: JsonObject, skipInit: Boolean = false): JsonObject {
        if (!skipInit) ensureInitialized()
        requestId++
        val payload = buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("id", requestId.toString())
            put("method", method)
            put("params", params)
        }
        val response = sendPayload(payload, expectResponse = true)
        val error = response["error"]
        if (error != null && error !is JsonNull) {
            val errObj = error as? JsonObject
            val message = errObj?.get("message")?.jsonPrimitive?.content ?: "Upstream error"
            throw Exception(message)
        }
        return response["result"] as? JsonObject ?: buildJsonObject {  }
    }

    suspend fun callTool(
        toolName: String,
        args: JsonObject,
        onEvent: (suspend (JsonObject) -> Unit)? = null
    ): JsonObject {
        ensureInitialized()
        try {
            return callToolViaBridgeStream(toolName, args, onEvent)
        } catch (e: RetryableException) {
            if (!e.retryable) throw e
            // Fall through to MCP fallback
        }
        return sendRequest("tools/call", buildJsonObject {
            put("name", toolName)
            put("arguments", args)
        })
    }

    private suspend fun callToolViaBridgeStream(
        toolName: String,
        args: JsonObject,
        onEvent: (suspend (JsonObject) -> Unit)? = null
    ): JsonObject {
        val bridgeBaseUrl = server.bridgeBaseUrl
            ?: throw RetryableException("Bridge base URL is missing", retryable = true)
        val url = "$bridgeBaseUrl/tools/call/stream"
        val requestPayload = buildJsonObject {
            put("name", toolName)
            put("arguments", args)
        }

        traffic.log(TrafficRecord(
            ts = nowIso(),
            direction = "upstream-out",
            serverId = server.serverId,
            method = "bridge/tools/call/stream",
            payload = requestPayload
        ))

        var sawEvent = false
        var resultPayload: JsonObject? = null

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody(Json.encodeToString(JsonObject.serializer(), requestPayload))
            // Override socket timeout for SSE streaming: reset per line via readUTF8Line timeout
            header("Connection", "keep-alive")
        }

        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(400) } catch (e: Exception) { "" }
            val retryable = response.status.value in listOf(404, 405, 501)
            throw RetryableException(
                "Bridge HTTP ${response.status.value} ${response.status.description}${if (body.isNotEmpty()) ": $body" else ""}",
                retryable = retryable
            )
        }

        val channel = response.bodyAsChannel()
        val parser = SseStreamParser()

        try {
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line()
                    ?: break  // EOF

                val event = parser.feed(line)
                if (event != null) {
                    sawEvent = true
                    traffic.log(TrafficRecord(
                        ts = nowIso(),
                        direction = "upstream-in",
                        serverId = server.serverId,
                        method = "bridge/tools/call/stream",
                        payload = event.data
                    ))
                    onEvent?.invoke(event.data)

                    when (event.type) {
                        "result" -> {
                            resultPayload = event.data["result"] as? JsonObject
                        }
                        "error" -> {
                            val msg = event.data["message"]?.jsonPrimitive?.content ?: "Bridge stream error"
                            throw RetryableException(msg, retryable = false)
                        }
                    }
                }
            }

            // Flush any remaining partial block
            parser.flush()?.also { event ->
                sawEvent = true
                onEvent?.invoke(event.data)
                if (event.type == "result") {
                    resultPayload = event.data["result"] as? JsonObject
                }
            }
        } catch (e: RetryableException) {
            throw e
        } catch (e: Exception) {
            throw RetryableException(
                e.message ?: "Bridge stream error for $toolName",
                retryable = !sawEvent
            )
        }

        return resultPayload
            ?: throw RetryableException("Bridge stream completed without result for $toolName", retryable = !sawEvent)
    }

    private suspend fun sendPayload(payload: JsonObject, expectResponse: Boolean): JsonObject {
        traffic.log(TrafficRecord(
            ts = nowIso(),
            direction = "upstream-out",
            serverId = server.serverId,
            method = payload["method"]?.jsonPrimitive?.content,
            payload = payload
        ))

        val response = client.post(server.url) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            sessionId?.let { header(SESSION_HEADER, it) }
            setBody(Json.encodeToString(JsonObject.serializer(), payload))
            timeout {
                requestTimeoutMillis = config.upstreamTimeoutMs
                socketTimeoutMillis = config.upstreamTimeoutMs
            }
        }

        val newSession = response.headers[SESSION_HEADER.lowercase()]
            ?: response.headers[SESSION_HEADER]
        if (newSession != null) sessionId = newSession

        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(400) } catch (e: Exception) { "" }
            throw Exception("Upstream HTTP ${response.status.value} ${response.status.description}${if (body.isNotEmpty()) ": $body" else ""}")
        }

        if (!expectResponse) return buildJsonObject {  }

        val text = response.bodyAsText()
        val json = try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw Exception("Expected JSON object, got array or primitive")
        } catch (e: Exception) {
            throw Exception("Invalid JSON from upstream: ${e.message}")
        }

        traffic.log(TrafficRecord(
            ts = nowIso(),
            direction = "upstream-in",
            serverId = server.serverId,
            method = payload["method"]?.jsonPrimitive?.content,
            payload = json
        ))

        return json
    }

    suspend fun fetchJson(path: String): JsonObject? {
        val url = "${server.bridgeBaseUrl}$path"
        return try {
            val response = client.get(url) {
                accept(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = config.upstreamTimeoutMs
                    socketTimeoutMillis = config.upstreamTimeoutMs
                }
            }
            if (!response.status.isSuccess()) return null
            Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        client.close()
    }
}
