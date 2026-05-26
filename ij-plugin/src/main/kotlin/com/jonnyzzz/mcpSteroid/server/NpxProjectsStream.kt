/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Streams the projects-stream NDJSON response into [ApplicationCall].
 *
 * Behavior:
 *  - Reads the request body as [NpxStreamClientInfo] (best-effort: malformed
 *    or empty body falls back to defaults so clients on older protocols
 *    still connect).
 *  - Emits a `snapshot` envelope immediately for the current state.
 *  - Emits a fresh `snapshot` envelope on every distinct value of
 *    [projectsFlow] — `collectLatest` ensures we never queue stale snapshots
 *    when changes pile up faster than the consumer drains.
 *  - Emits a `ping` envelope every [pingInterval] so the client can detect
 *    a half-open TCP connection without waiting for the next change.
 *
 * Pure-function shape (no `Project`, no `service<>`) so the route is
 * testable in isolation against an in-memory `Flow<List<ProjectInfo>>`.
 */
internal suspend fun ApplicationCall.streamProjectsNdjson(
    projectsFlow: Flow<List<ProjectInfo>>,
    instanceId: String,
    pid: Long,
    nextSeq: () -> Long,
    pingInterval: Duration = NPX_STREAM_KEEPALIVE_INTERVAL_SECONDS.seconds,
    onClientInfo: (NpxStreamClientInfo) -> Unit,
) {
    val rawBody = try { receiveText() } catch (e: Exception) { "" }
    val clientInfo = if (rawBody.isBlank()) {
        NpxStreamClientInfo()
    } else {
        try {
            NpxStreamJson.decodeClientInfo(rawBody)
        } catch (e: Exception) {
            NpxStreamClientInfo(client = "unparseable: ${e.message}")
        }
    }
    onClientInfo(clientInfo)

    respondTextWriter(contentType = NDJSON_CONTENT_TYPE) {
        val sendMutex = Mutex()
        suspend fun sendLine(line: String) {
            sendMutex.withLock {
                withContext(Dispatchers.IO) {
                    write(line)
                    write("\n")
                    flush()
                }
            }
        }

        coroutineScope {
            val pings = launch {
                while (isActive) {
                    delay(pingInterval)
                    val ping = NpxStreamEnvelope(
                        type = "ping",
                        seq = nextSeq(),
                        sentAt = nowIso(),
                        instanceId = instanceId,
                        pid = pid,
                    )
                    sendLine(NpxStreamJson.encodeEnvelope(ping))
                }
            }

            try {
                projectsFlow.collectLatest { projects ->
                    val snapshot = NpxStreamEnvelope(
                        type = "snapshot",
                        seq = nextSeq(),
                        sentAt = nowIso(),
                        instanceId = instanceId,
                        pid = pid,
                        projects = projects,
                    )
                    sendLine(NpxStreamJson.encodeEnvelope(snapshot))
                }
            } finally {
                pings.cancel()
            }
        }
    }
}

internal val NDJSON_CONTENT_TYPE: ContentType =
    ContentType.parse(NPX_NDJSON_MIME_TYPE)

private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
