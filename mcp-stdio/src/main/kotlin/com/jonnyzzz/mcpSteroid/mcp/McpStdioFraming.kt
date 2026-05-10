/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

/**
 * Parses MCP stdio framing: Content-Length headers or NDJSON.
 *
 * Content-Length framing: "Content-Length: N\r\n\r\n<body>"
 * NDJSON fallback: "<json>\n" — only when buffer starts like JSON ({/[).
 */
data class FrameResult(
    val consumed: Int,
    val payloadText: String,
    val mode: String  // "framed" | "ndjson"
)

class FramingBuffer {
    private var data = ByteArray(0)
    var outputMode: String? = null  // detected from first inbound frame

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        val newData = ByteArray(data.size + length)
        data.copyInto(newData)
        bytes.copyInto(newData, destinationOffset = data.size, endIndex = length)
        data = newData
    }

    fun readNextFrame(): FrameResult? {
        val frame = tryParseNextFrame(data) ?: return null
        data = data.copyOfRange(frame.consumed, data.size)
        return frame
    }

    fun isEmpty(): Boolean = data.isEmpty()
}

private fun decodeContentLength(headersText: String): Int? {
    for (line in headersText.split(Regex("\r?\n"))) {
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val key = line.substring(0, idx).trim().lowercase()
        if (key != "content-length") continue
        return line.substring(idx + 1).trim().toIntOrNull()?.takeIf { it >= 0 }
    }
    return null
}

private fun startsLikeJsonPayload(data: ByteArray): Boolean {
    val prefix = data.take(minOf(data.size, 64))
        .toByteArray()
        .toString(Charsets.UTF_8)
        .trimStart()
    return prefix.startsWith("{") || prefix.startsWith("[")
}

private fun ByteArray.indexOf(target: ByteArray, fromIndex: Int = 0): Int {
    outer@ for (i in fromIndex..this.size - target.size) {
        for (j in target.indices) {
            if (this[i + j] != target[j]) continue@outer
        }
        return i
    }
    return -1
}

private fun tryParseNextFrame(data: ByteArray): FrameResult? {
    val headerSep = "\r\n\r\n".toByteArray()
    val altHeaderSep = "\n\n".toByteArray()

    var headerEnd = data.indexOf(headerSep)
    var delimiterLength = 4

    if (headerEnd < 0) {
        headerEnd = data.indexOf(altHeaderSep)
        delimiterLength = 2
    }

    if (headerEnd >= 0) {
        val headersText = data.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
        val bodyLength = decodeContentLength(headersText)
        if (bodyLength != null) {
            val total = headerEnd + delimiterLength + bodyLength
            if (data.size < total) return null
            val payloadText = data.copyOfRange(headerEnd + delimiterLength, total).toString(Charsets.UTF_8)
            return FrameResult(consumed = total, payloadText = payloadText, mode = "framed")
        }
    }

    // NDJSON fallback — only when buffer starts like JSON
    if (!startsLikeJsonPayload(data)) return null

    val newlineIdx = data.indexOf(byteArrayOf(0x0a))
    if (newlineIdx < 0) return null

    val payloadText = data.copyOfRange(0, newlineIdx).toString(Charsets.UTF_8).trim()
    return FrameResult(consumed = newlineIdx + 1, payloadText = payloadText, mode = "ndjson")
}

fun encodeFramedMessage(payload: String): String {
    val bytes = payload.toByteArray(Charsets.UTF_8)
    return "Content-Length: ${bytes.size}\r\n\r\n$payload"
}

fun encodeNdjsonMessage(payload: String): String = "$payload\n"
