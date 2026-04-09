/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.transport.stdio

import com.jonnyzzz.mcpSteroid.mcp.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.*

/**
 * Stdio transport for MCP servers. Reads NDJSON messages from stdin,
 * dispatches via [McpServerCore], and writes responses to stdout.
 *
 * Adapted from npx-kt stdio transport patterns but designed as a
 * reusable transport layer (not a proxy).
 *
 * Uses a single implicit session for the lifetime of the transport.
 */
class McpStdioTransport(
    private val server: McpServerCore,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
) {
    private val logger = LoggerFactory.getLogger(McpStdioTransport::class.java)
    private val session: McpSession = server.sessionManager.createSession()
    private val outputLock = Any()

    /**
     * Run the stdio MCP server. Blocks until EOF on input.
     * Reads NDJSON (newline-delimited JSON) messages, dispatches via McpServerCore,
     * and writes responses + notifications to output.
     */
    suspend fun run() {
        coroutineScope {
            // Forward server notifications to stdout
            val notificationJob = launch {
                session.notifications().collect { notification ->
                    val json = McpJson.encodeToString(JsonRpcNotification.serializer(), notification)
                    writeLine(json)
                }
            }

            // Forward server-to-client requests to stdout
            val requestJob = launch {
                session.outgoingRequests().collect { request ->
                    val json = McpJson.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        kotlinx.serialization.json.buildJsonObject {
                            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive(JSONRPC_VERSION))
                            put("id", request.id)
                            put("method", kotlinx.serialization.json.JsonPrimitive(request.method))
                            val p = request.params
                            if (p != null) {
                                put("params", p)
                            }
                        }
                    )
                    writeLine(json)
                }
            }

            try {
                val reader = BufferedReader(InputStreamReader(input))
                while (true) {
                    val line = withContext(Dispatchers.IO) {
                        reader.readLine()
                    } ?: break // EOF

                    if (line.isBlank()) continue

                    logger.debug("[MCP Stdio] Received: {}", line)

                    val response = server.handleMessage(line, session)
                    if (response != null) {
                        writeLine(response)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("[MCP Stdio] Error reading input", e)
            } finally {
                notificationJob.cancel()
                requestJob.cancel()
                session.close()
                server.sessionManager.removeSession(session.id)
                logger.info("[MCP Stdio] Transport closed")
            }
        }
    }

    private fun writeLine(message: String) {
        synchronized(outputLock) {
            output.write(message.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
        }
        logger.debug("[MCP Stdio] Sent: {}", message)
    }
}
