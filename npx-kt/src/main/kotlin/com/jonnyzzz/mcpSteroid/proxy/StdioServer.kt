/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.mcp.FrameResult
import com.jonnyzzz.mcpSteroid.mcp.FramingBuffer
import com.jonnyzzz.mcpSteroid.mcp.JSONRPC_VERSION
import com.jonnyzzz.mcpSteroid.mcp.JsonRpcErrorCodes
import com.jonnyzzz.mcpSteroid.mcp.RpcException
import com.jonnyzzz.mcpSteroid.mcp.encodeFramedMessage
import com.jonnyzzz.mcpSteroid.mcp.encodeNdjsonMessage
import com.jonnyzzz.mcpSteroid.mcp.jsonRpcError
import com.jonnyzzz.mcpSteroid.mcp.jsonRpcResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream

private val prettyJson = Json { prettyPrint = true }

class StdioServer(
    private val registry: ServerRegistry,
    private val traffic: TrafficLogger,
    private val beacon: NpxBeacon?,
    private val inputStream: InputStream = System.`in`,
    private val outputStream: OutputStream = System.out
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var outputMode: String? = null  // "framed" | "ndjson"

    private fun writeResponse(payload: JsonObject) {
        val text = Json.encodeToString(JsonObject.serializer(), payload)
        val message = when (outputMode) {
            "ndjson" -> encodeNdjsonMessage(text)
            else -> encodeFramedMessage(text)
        }
        synchronized(outputStream) {
            outputStream.write(message.toByteArray(Charsets.UTF_8))
            outputStream.flush()
        }
    }

    private suspend fun handleSingle(request: JsonObject): JsonObject? {
        val idElement = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull

        // Notification — no id, no response
        if (idElement == null || idElement is kotlinx.serialization.json.JsonNull) {
            return null
        }

        val id = idElement.jsonPrimitive.contentOrNull ?: return null

        if (method == null) {
            return jsonRpcError(id, JsonRpcErrorCodes.INVALID_REQUEST, "Missing method")
        }

        return try {
            val params = request["params"] as? JsonObject ?: buildJsonObject {  }
            val result = handleRpc(
                method = method,
                params = params,
                registry = registry,
                beacon = beacon,
                notify = { notifyMethod, notifyParams ->
                    writeResponse(buildJsonObject {
                        put("jsonrpc", JSONRPC_VERSION)
                        put("method", notifyMethod)
                        put("params", notifyParams)
                    })
                }
            )
            jsonRpcResult(id, result)
        } catch (e: RpcException) {
            jsonRpcError(id, e.code, e.message ?: "RPC error")
        } catch (e: Exception) {
            jsonRpcError(id, JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Internal error")
        }
    }

    private suspend fun handlePayload(payload: Any?) {
        if (payload == null) {
            writeResponse(jsonRpcError(null, JsonRpcErrorCodes.INVALID_REQUEST, "Empty request body"))
            return
        }

        if (payload is JsonArray) {
            val responses = mutableListOf<JsonObject>()
            for (item in payload) {
                val obj = item as? JsonObject
                    ?: continue
                val response = handleSingle(obj)
                if (response != null) responses += response
            }
            if (responses.isNotEmpty()) {
                // Batch response: write as JSON array
                val text = Json.encodeToString(JsonArray.serializer(), JsonArray(responses))
                val message = when (outputMode) {
                    "ndjson" -> encodeNdjsonMessage(text)
                    else -> encodeFramedMessage(text)
                }
                synchronized(outputStream) {
                    outputStream.write(message.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
            }
            return
        }

        if (payload !is JsonObject) {
            writeResponse(jsonRpcError(null, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid request"))
            return
        }

        traffic.log(TrafficRecord(
            ts = nowIso(),
            direction = "proxy-in",
            payload = payload
        ))

        val response = handleSingle(payload)
        if (response != null) {
            traffic.log(TrafficRecord(
                ts = nowIso(),
                direction = "proxy-out",
                payload = response
            ))
            writeResponse(response)
        }
    }

    suspend fun run() {
        val frameChannel = Channel<FrameResult>(Channel.UNLIMITED)
        val buffer = FramingBuffer()

        // Stdin reader — blocks on Dispatchers.IO
        val readerJob = kotlinx.coroutines.coroutineScope {
            launch(Dispatchers.IO) {
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = inputStream.read(buf)
                        if (n < 0) break  // EOF
                        buffer.append(buf, n)
                        while (true) {
                            val frame = buffer.readNextFrame() ?: break
                            if (outputMode == null) outputMode = frame.mode
                            frameChannel.send(frame)
                        }
                    }
                } finally {
                    frameChannel.close()
                }
            }
        }

        // Sequential frame processor
        for (frame in frameChannel) {
            if (frame.payloadText.isBlank()) continue

            val parsed = try {
                json.parseToJsonElement(frame.payloadText)
            } catch (e: Exception) {
                writeResponse(jsonRpcError(null, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: ${e.message}"))
                continue
            }

            try {
                handlePayload(parsed)
            } catch (e: Exception) {
                writeResponse(jsonRpcError(null, JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Internal error"))
            }
        }
    }
}

suspend fun runCliMode(args: CliArgs, registry: ServerRegistry, traffic: TrafficLogger, beacon: NpxBeacon?) {
    val (method, params) = buildCliRequest(args)

    traffic.log(TrafficRecord(
        ts = nowIso(),
        direction = "cli-in",
        payload = params
    ))

    val result = handleRpc(method, params, registry, beacon)

    traffic.log(TrafficRecord(
        ts = nowIso(),
        direction = "cli-out",
        payload = result
    ))

    val text = prettyJson.encodeToString(JsonObject.serializer(), result)
    System.out.write("$text\n".toByteArray(Charsets.UTF_8))
    System.out.flush()
}
