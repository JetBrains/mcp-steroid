/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ResourceReadResult
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import java.nio.charset.StandardCharsets

class NpxBuiltInWebServerRpcHandler : HttpRequestHandler() {
    private val log = Logger.getInstance(NpxBuiltInWebServerRpcHandler::class.java)

    override fun isSupported(request: FullHttpRequest): Boolean {
        val path = QueryStringDecoder(request.uri()).path()
        return path == RPC_PREFIX || path.startsWith("$RPC_PREFIX/")
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        if (!isSupported(request)) return false

        val startedAt = System.nanoTime()
        try {
            log.info("[MCP-BUILTIN-RPC] <- ${request.method()} ${urlDecoder.path()}")
            val response = runBlocking {
                route(urlDecoder, request)
            }
            sendResponse(request, context, response)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            log.info("[MCP-BUILTIN-RPC] -> ${response.status.code()} ${response.status.reasonPhrase()} for ${request.method()} ${urlDecoder.path()} in ${elapsedMs}ms")
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to handle MCP Steroid built-in-webserver RPC request ${request.method()} ${request.uri()}", e)
            sendResponse(
                request,
                context,
                HandlerResponse.text(
                    status = HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    text = "Internal MCP Steroid RPC error: ${e.message ?: e.javaClass.name}",
                ),
            )
        }
        return true
    }

    private suspend fun route(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
    ): HandlerResponse {
        val bridge = NpxBridgeService.getInstance()
        if (!bridge.isAuthorized(request.headers().get(HttpHeaderNames.AUTHORIZATION))) {
            return HandlerResponse.text(HttpResponseStatus.UNAUTHORIZED, "Missing or invalid MCP Steroid bridge token")
        }

        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val relativePath = urlDecoder.path().removePrefix(RPC_PREFIX).ifBlank { "/" }
        return when (relativePath) {
            "/metadata" -> requireGet(request) ?: HandlerResponse.json(
                NpxBridgeMetadataResponse.serializer(),
                bridge.buildMetadata(server.mcpUrl),
            )
            "/server-metadata" -> requireGet(request) ?: HandlerResponse.json(
                ServerMetadataResponse.serializer(),
                bridge.buildServerMetadata(server.mcpUrl),
            )
            "/products" -> requireGet(request) ?: HandlerResponse.json(
                ListProductsResponse.serializer(),
                bridge.buildProducts(),
            )
            "/projects" -> requireGet(request) ?: HandlerResponse.json(
                NpxBridgeProjectsResponse.serializer(),
                bridge.buildProjects(server.mcpUrl),
            )
            "/windows" -> requireGet(request) ?: HandlerResponse.json(
                NpxBridgeWindowsResponse.serializer(),
                bridge.buildWindows(server.mcpUrl),
            )
            "/summary" -> requireGet(request) ?: HandlerResponse.json(
                NpxBridgeSummaryResponse.serializer(),
                bridge.buildSummary(server.mcpUrl),
            )
            "/resources" -> requireGet(request) ?: HandlerResponse.json(
                NpxBridgeResourcesResponse.serializer(),
                bridge.buildResources(server.getServer()),
            )
            "/resources/read" -> requireGet(request) ?: readResource(urlDecoder, bridge, server)
            "/tools/call" -> requirePost(request) ?: callTool(request, bridge, server, stream = false)
            "/tools/call/stream" -> requirePost(request) ?: callTool(request, bridge, server, stream = true)
            else -> HandlerResponse.text(HttpResponseStatus.NOT_FOUND, "Unknown MCP Steroid RPC endpoint: $relativePath")
        }
    }

    private fun requireGet(request: FullHttpRequest): HandlerResponse? =
        if (request.method() == HttpMethod.GET) null else methodNotAllowed("GET")

    private fun requirePost(request: FullHttpRequest): HandlerResponse? =
        if (request.method() == HttpMethod.POST) null else methodNotAllowed("POST")

    private fun methodNotAllowed(expected: String): HandlerResponse =
        HandlerResponse.text(HttpResponseStatus.METHOD_NOT_ALLOWED, "Use HTTP $expected for this MCP Steroid RPC endpoint")

    private fun readResource(
        urlDecoder: QueryStringDecoder,
        bridge: NpxBridgeService,
        server: SteroidsMcpServer,
    ): HandlerResponse {
        val uri = urlDecoder.parameters()["uri"]?.firstOrNull()
        if (uri.isNullOrBlank()) {
            return HandlerResponse.text(HttpResponseStatus.BAD_REQUEST, "Missing uri query parameter")
        }

        val payload = bridge.readResource(server.getServer(), uri)
        if (payload != null) {
            return HandlerResponse.json(ResourceReadResult.serializer(), payload)
        }

        return if (uri.startsWith("mcp-steroid://")) {
            HandlerResponse.text(
                HttpResponseStatus.NOT_FOUND,
                "Detailed MCP Steroid resources are available through steroid_fetch_resource with project_name and uri, not resources/read: $uri",
            )
        } else {
            HandlerResponse.text(HttpResponseStatus.NOT_FOUND, "Resource not found: $uri")
        }
    }

    private suspend fun callTool(
        request: FullHttpRequest,
        bridge: NpxBridgeService,
        server: SteroidsMcpServer,
        stream: Boolean,
    ): HandlerResponse {
        val toolRequest = decodeToolRequest(request) ?: return HandlerResponse.text(
            HttpResponseStatus.BAD_REQUEST,
            "Invalid MCP Steroid RPC tool-call request body",
        )

        if (stream) {
            val text = buildString {
                bridge.streamToolCall(server.getServer(), toolRequest) { event ->
                    appendLine("event: ${event.eventType() ?: "message"}")
                    appendLine("data: $event")
                    appendLine()
                }
            }
            return HandlerResponse.text(HttpResponseStatus.OK, text, EVENT_STREAM_CONTENT_TYPE)
        }

        var result: JsonElement? = null
        var errorMessage: String? = null
        bridge.streamToolCall(server.getServer(), toolRequest) { event ->
            when (event.eventType()) {
                "result" -> result = event["result"]
                "error" -> errorMessage = event["message"]?.jsonPrimitive?.contentOrNull
            }
        }

        return result?.let { HandlerResponse.jsonElement(it) }
            ?: HandlerResponse.text(HttpResponseStatus.BAD_GATEWAY, errorMessage ?: "Tool call failed")
    }

    private fun decodeToolRequest(request: FullHttpRequest): NpxBridgeToolCallRequest? {
        val body = request.content().toString(StandardCharsets.UTF_8)
        return try {
            McpJson.decodeFromString(NpxBridgeToolCallRequest.serializer(), body)
        } catch (e: Exception) {
            log.warn("Invalid MCP Steroid built-in-webserver tool-call request body", e)
            null
        }
    }

    private fun sendResponse(
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        response: HandlerResponse,
    ) {
        val bytes = response.text.toByteArray(StandardCharsets.UTF_8)
        val nettyResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            response.status,
            Unpooled.wrappedBuffer(bytes),
        )
        nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, response.contentType)
        nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
        if (response.contentType == EVENT_STREAM_CONTENT_TYPE) {
            nettyResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
        }

        val keepAlive = HttpUtil.isKeepAlive(request)
        if (keepAlive) {
            nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }
        val future = context.writeAndFlush(nettyResponse)
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    private data class HandlerResponse(
        val status: HttpResponseStatus,
        val text: String,
        val contentType: String,
    ) {
        companion object {
            fun text(
                status: HttpResponseStatus,
                text: String,
                contentType: String = PLAIN_TEXT_CONTENT_TYPE,
            ): HandlerResponse = HandlerResponse(status, text, contentType)

            fun <T> json(serializer: KSerializer<T>, payload: T): HandlerResponse =
                HandlerResponse(HttpResponseStatus.OK, McpJson.encodeToString(serializer, payload), JSON_CONTENT_TYPE)

            fun jsonElement(payload: JsonElement): HandlerResponse =
                HandlerResponse(HttpResponseStatus.OK, payload.toString(), JSON_CONTENT_TYPE)
        }
    }

    companion object {
        const val RPC_PREFIX: String = "/api/mcp-steroid/v1"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val PLAIN_TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"
        private const val EVENT_STREAM_CONTENT_TYPE = "text/event-stream; charset=utf-8"

        fun builtInRpcBaseUrl(): String {
            val manager = BuiltInServerManager.getInstance().waitForStart()
            val port = manager.port
            require(port > 0) { "IntelliJ built-in webserver did not report a valid port: $port" }
            return "http://127.0.0.1:$port$RPC_PREFIX"
        }
    }
}

private fun JsonObject.eventType(): String? = this["type"]?.jsonPrimitive?.contentOrNull
