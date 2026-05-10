/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * MCP Streamable HTTP Transport implementation for Ktor.
 * Follows the MCP 2025-11-25 specification.
 *
 * The transport supports:
 * - POST requests for sending messages (requests and notifications)
 * - GET requests for establishing SSE streams for server-to-client notifications
 * - OPTIONS requests for CORS preflight
 * - Session management via Mcp-Session-Id header
 *
 * Header requirements per MCP spec:
 * - Client MUST include Accept header with application/json (and optionally text/event-stream)
 * - Client MUST include Content-Type: application/json for POST requests
 * - Server responds with Content-Type: application/json for JSON responses
 */
object McpHttpTransport {
    private val log = thisLogger()

    const val SESSION_HEADER = "Mcp-Session-Id"
    const val SESSION_NOTICE_HEADER = "Mcp-Session-Notice"
    const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
    private const val CONTENT_TYPE_JSON = "application/json"
    private const val CONTENT_TYPE_SSE = "text/event-stream"
    private const val UNKNOWN_SESSION_NOTICE =
        "Unknown session; new session created. Update stored Mcp-Session-Id."
    /**
     * Install MCP routes at the specified path.
     */
    fun Route.installMcp(path: String, server: McpServerCore) {
        route(path) {
            // OPTIONS - CORS preflight
            options {
                handleOptions(call)
            }

            // POST - Handle incoming messages (requests and notifications)
            post {
                addCorsHeaders(call)
                handlePost(call, server)
            }

            // GET - SSE stream for server-to-client notifications
            // Per MCP spec:
            // - Client MUST include Accept header with text/event-stream
            // - Server MUST return Content-Type: text/event-stream OR HTTP 405 Method Not Allowed
            get {
                addCorsHeaders(call)
                handleGet(call, server)
            }

            // DELETE - Terminate session
            delete {
                addCorsHeaders(call)
                val remoteHost = call.request.local.remoteHost
                log.info("[MCP] DELETE request from $remoteHost")
                handleDelete(call, server)
            }
        }
    }

    /**
     * Handle OPTIONS preflight requests for CORS.
     */
    private suspend fun handleOptions(call: ApplicationCall) {
        val remoteHost = call.request.local.remoteHost
        log.info("[MCP] OPTIONS request from $remoteHost - returning CORS headers")
        addCorsHeaders(call)
        call.respond(HttpStatusCode.NoContent)
    }

    /**
     * Add CORS headers to allow cross-origin requests from Claude CLI and other tools.
     */
    private fun addCorsHeaders(call: ApplicationCall) {
        call.response.header("Access-Control-Allow-Origin", "*")
        call.response.header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        call.response.header(
            "Access-Control-Allow-Headers",
            "Content-Type, Accept, $SESSION_HEADER, $SESSION_NOTICE_HEADER, $PROTOCOL_VERSION_HEADER"
        )
        call.response.header("Access-Control-Expose-Headers", "$SESSION_HEADER, $SESSION_NOTICE_HEADER, $PROTOCOL_VERSION_HEADER")
        // Per MCP 2025-11-25 spec: Include protocol version in all responses
        call.response.header(PROTOCOL_VERSION_HEADER, MCP_PROTOCOL_VERSION)
    }

    private suspend fun handlePost(call: ApplicationCall, server: McpServerCore) {
        // Log incoming request details
        val remoteHost = call.request.local.remoteHost
        val userAgent = call.request.userAgent() ?: "unknown"
        log.info("[MCP] POST request from $remoteHost (User-Agent: $userAgent)")

        // Log all headers for debugging
        call.request.headers.forEach { name, values ->
            log.debug("[MCP] Header: $name = ${values.joinToString(", ")}")
        }

        // Validate Accept header per MCP spec
        // Client MUST include Accept header that includes application/json
        val acceptHeader = call.request.accept()
        if (acceptHeader != null && !acceptsJson(acceptHeader)) {
            log.warn("[MCP] Rejecting request: Accept header '$acceptHeader' doesn't include application/json")
            call.respond(HttpStatusCode.NotAcceptable, "Accept header must include application/json")
            return
        }

        // Validate Content-Type header per MCP spec
        // Client MUST include Content-Type: application/json
        val contentType = call.request.contentType()
        if (!contentType.match(ContentType.Application.Json)) {
            log.warn("[MCP] Rejecting request: Content-Type '$contentType' is not application/json")
            call.respond(HttpStatusCode.UnsupportedMediaType, "Content-Type must be application/json")
            return
        }

        val body = call.receiveText()
        if (body.isBlank()) {
            log.warn("[MCP] Empty request body")
            call.respond(HttpStatusCode.BadRequest, "Empty request body")
            return
        }

        val sessionId = call.request.header(SESSION_HEADER)
        val (session, isNewSession, sessionNotice) = if (sessionId != null) {
            val existingSession = server.sessionManager.getSession(sessionId)
            if (existingSession != null) {
                log.debug("[MCP] Using existing session: $sessionId")
                Triple(existingSession, false, null)
            } else {
                log.info("[MCP] Unknown session ID: $sessionId (likely IDE was restarted)")
                log.info("[MCP] Creating new session for client (User-Agent: $userAgent)")
                Triple(server.sessionManager.createSession(), true, UNKNOWN_SESSION_NOTICE)
            }
        } else {
            log.info("[MCP] No session ID provided, creating new session")
            Triple(server.sessionManager.createSession(), true, null)
        }

        // Log the request body (truncated for large payloads)
        val truncatedBody = if (body.length > 500) body.take(500) + "...[truncated]" else body
        log.info("[MCP] Request body: $truncatedBody")

        val response = try {
            server.handleMessage(body, session)
        } catch (e: Throwable) {
            log.error("[MCP] Exception in handleMessage: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, "Internal error: ${e.message}")
            return
        }

        // Include session ID in response for new sessions (or when we created a replacement session)
        if (isNewSession) {
            log.info("[MCP] Returning new session ID: ${session.id}")
            call.response.header(SESSION_HEADER, session.id)
        }
        if (sessionNotice != null) {
            call.response.header(SESSION_NOTICE_HEADER, sessionNotice)
        }

        if (response != null) {
            // Log the response (truncated for large payloads)
            val truncatedResponse = if (response.length > 500) response.take(500) + "...[truncated]" else response
            log.info("[MCP] Response: $truncatedResponse")
            call.respondText(response, ContentType.Application.Json)
        } else {
            // Notification - no response needed
            log.info("[MCP] Notification processed, returning 202 Accepted")
            call.respond(HttpStatusCode.Accepted)
        }
    }

    /**
     * Handle GET requests.
     *
     * For SSE streams (Accept: text/event-stream only), per MCP spec:
     * - Server MUST return Content-Type: text/event-stream OR HTTP 405 Method Not Allowed
     *
     * For availability checks (Accept includes application/json or wildcard):
     * - Return server info JSON to indicate the server is available
     *
     * Important: Claude CLI sends "Accept: application/json, text/event-stream"
     * which means it accepts both - we should return JSON in this case.
     */
    private suspend fun handleGet(call: ApplicationCall, server: McpServerCore) {
        val remoteHost = call.request.local.remoteHost
        val userAgent = call.request.userAgent() ?: "unknown"
        log.info("[MCP] GET request from $remoteHost (User-Agent: $userAgent)")

        val acceptHeader = call.request.accept()

        // If client accepts JSON (either directly or via wildcard), return server info
        // This is the common case for health checks from Claude CLI
        if (acceptHeader == null || acceptsJson(acceptHeader)) {
            log.info("[MCP] GET request from $remoteHost - returning server info (accepts JSON)")
            val serverInfo = server.serverInfo
            val payload = buildJsonObject {
                put("name", JsonPrimitive(serverInfo.name))
                put("version", JsonPrimitive(serverInfo.version))
                put("status", JsonPrimitive("available"))
            }
            call.respondText(McpJson.encodeToString(JsonObject.serializer(), payload), ContentType.Application.Json)
            return
        }

        // If client explicitly requests SSE only (not JSON), return 405 (we don't support it)
        if (acceptsSse(acceptHeader)) {
            log.info("[MCP] GET request from $remoteHost - returning 405 (SSE-only not supported)")
            call.respond(HttpStatusCode.MethodNotAllowed, "SSE notifications not supported")
            return
        }

        // For other accept types, return Not Acceptable
        log.warn("[MCP] GET request from $remoteHost - unsupported Accept header: $acceptHeader")
        call.respond(HttpStatusCode.NotAcceptable, "Unsupported Accept header")
    }

    /**
     * Check if the Accept header includes application/json.
     * Per MCP spec, clients MUST accept application/json for POST requests.
     */
    private fun acceptsJson(acceptHeader: String): Boolean {
        // Accept header can contain multiple types separated by comma
        // Each type can have parameters like q=0.9
        // Examples: "application/json", "*/*", "application/json, text/event-stream"
        return acceptHeader.split(",").any { part ->
            val mediaType = part.trim().split(";").first().trim()
            mediaType == CONTENT_TYPE_JSON || mediaType == "*/*" || mediaType == "application/*"
        }
    }

    /**
     * Check if the Accept header includes text/event-stream.
     * Per MCP spec, clients MUST accept text/event-stream for GET requests.
     */
    private fun acceptsSse(acceptHeader: String): Boolean {
        return acceptHeader.split(",").any { part ->
            val mediaType = part.trim().split(";").first().trim()
            mediaType == CONTENT_TYPE_SSE || mediaType == "*/*" || mediaType == "text/*"
        }
    }

    private suspend fun handleDelete(call: ApplicationCall, server: McpServerCore) {
        val sessionId = call.request.header(SESSION_HEADER)
        if (sessionId != null) {
            log.info("[MCP] Terminating session: $sessionId")
            server.sessionManager.removeSession(sessionId)
            call.respond(HttpStatusCode.NoContent)
        } else {
            log.warn("[MCP] DELETE request without session ID")
            call.respond(HttpStatusCode.BadRequest, "Missing session ID")
        }
    }
}
