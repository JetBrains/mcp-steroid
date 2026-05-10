/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.mcp.FramingBuffer
import com.jonnyzzz.mcpSteroid.mcp.MCP_PROTOCOL_VERSION
import com.jonnyzzz.mcpSteroid.mcp.encodeFramedMessage
import com.jonnyzzz.mcpSteroid.mcp.encodeNdjsonMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive MCP stdio protocol acceptance tests.
 *
 * Uses [McpTestHarness] to pipe framed/NDJSON messages through [StdioServer]
 * and verify JSON-RPC 2.0 compliance, MCP 2025-11-25 protocol behaviour, and
 * all edge-cases identified from the official spec.
 */
class StdioServerProtocolTest {

    // =========================================================================
    // Test Harness
    // =========================================================================

    private class McpTestHarness {
        val config = ProxyConfig(scanIntervalMs = Long.MAX_VALUE)
        val traffic = TrafficLogger(config)
        val registry = ServerRegistry(config, traffic)

        private val inputBytes = ByteArrayOutputStream()

        fun sendFramed(jsonStr: String) {
            inputBytes.write(encodeFramedMessage(jsonStr).toByteArray(Charsets.UTF_8))
        }

        fun sendNdjson(jsonStr: String) {
            inputBytes.write(encodeNdjsonMessage(jsonStr).toByteArray(Charsets.UTF_8))
        }

        /** Run the server against buffered input; return the raw output bytes. */
        suspend fun runRaw(): ByteArray {
            val outputBuffer = ByteArrayOutputStream()
            val input = ByteArrayInputStream(inputBytes.toByteArray())
            val server = StdioServer(registry, traffic, null, input, outputBuffer)
            server.run()
            return outputBuffer.toByteArray()
        }

        /** Run and parse all response frames into JsonElement (objects or arrays). */
        suspend fun runAndGetElements(): List<JsonElement> {
            val raw = runRaw()
            val results = mutableListOf<JsonElement>()
            val buffer = FramingBuffer()
            buffer.append(raw)
            while (true) {
                val frame = buffer.readNextFrame() ?: break
                if (frame.payloadText.isNotBlank()) {
                    results.add(Json.parseToJsonElement(frame.payloadText))
                }
            }
            return results
        }

        /** Run and return only top-level JsonObject responses (filters out batch arrays). */
        suspend fun runAndGetObjects(): List<JsonObject> =
            runAndGetElements().filterIsInstance<JsonObject>()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun init(id: String = "1") =
        """{"jsonrpc":"2.0","id":"$id","method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""

    private fun req(id: String, method: String, params: String = "{}") =
        """{"jsonrpc":"2.0","id":"$id","method":"$method","params":$params}"""

    private fun notification(method: String, params: String = "{}") =
        """{"jsonrpc":"2.0","method":"$method","params":$params}"""

    // =========================================================================
    // initialize
    // =========================================================================

    @Test
    fun `initialize response has jsonrpc 2dot0`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(init())
        val resp = h.runAndGetObjects()[0]
        assertEquals("2.0", resp["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize response id matches request id`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(init("req-42"))
        val resp = h.runAndGetObjects()[0]
        assertEquals("req-42", resp["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize result has protocolVersion`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(init())
        val result = (h.runAndGetObjects()[0]["result"] as JsonObject)
        assertEquals(MCP_PROTOCOL_VERSION, result["protocolVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize result has serverInfo with name and version`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(init())
        val result = (h.runAndGetObjects()[0]["result"] as JsonObject)
        val serverInfo = result["serverInfo"] as JsonObject
        assertNotNull(serverInfo["name"]?.jsonPrimitive?.content)
        assertNotNull(serverInfo["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize result has capabilities with tools prompts and resources`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(init())
        val result = (h.runAndGetObjects()[0]["result"] as JsonObject)
        val caps = result["capabilities"] as JsonObject
        assertNotNull(caps["tools"])
        assertNotNull(caps["prompts"])
        assertNotNull(caps["resources"])
    }

    @Test
    fun `initialize result has instructions field`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(init())
        val result = (h.runAndGetObjects()[0]["result"] as JsonObject)
        assertNotNull(result["instructions"])
    }

    @Test
    fun `initialize result has no error field`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(init())
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        assertNotNull(resp["result"])
    }

    @Test
    fun `initialize with older protocol version still returns result`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""")
        val resp = h.runAndGetObjects()[0]
        assertNotNull(resp["result"])
        assertNull(resp["error"])
    }

    // =========================================================================
    // ping
    // =========================================================================

    @Test
    fun `ping returns empty result object`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        val resp = h.runAndGetObjects()[0]
        val result = resp["result"] as JsonObject
        assertEquals(0, result.size, "ping result must be empty object {}")
    }

    @Test
    fun `ping id is echoed`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("ping-id-99", "ping"))
        assertEquals("ping-id-99", h.runAndGetObjects()[0]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ping has jsonrpc 2dot0`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        assertEquals("2.0", h.runAndGetObjects()[0]["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ping has no error field`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        assertNotNull(resp["result"])
    }

    @Test
    fun `ping works before initialize`() = runTest {
        // MCP spec: ping is allowed before initialized notification
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(1, responses.size)
        assertNull(responses[0]["error"])
    }

    // =========================================================================
    // Unknown / missing method
    // =========================================================================

    @Test
    fun `unknown method returns error code minus32601`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "no/such/method"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `unknown method error has message`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "totally/missing"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertNotNull(error["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown method response has no result field`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "nope"))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["result"])
        assertNotNull(resp["error"])
    }

    @Test
    fun `unknown method echoes request id`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("err-id", "unknown"))
        assertEquals("err-id", h.runAndGetObjects()[0]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `missing method field returns invalid request minus32600`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1"}""")
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(-32600, error["code"]?.jsonPrimitive?.int)
    }

    // =========================================================================
    // Parse errors
    // =========================================================================

    @Test
    fun `invalid json returns parse error minus32700`() = runTest {
        val h = McpTestHarness()
        // Use clearly malformed JSON (truncated object) — definitely not parseable
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":""")
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(-32700, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `json with syntax error returns parse error`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""{"id": "1", "method": "ping" """)  // missing closing brace
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(-32700, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `parse error response has jsonrpc 2dot0`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("bad json")
        assertEquals("2.0", h.runAndGetObjects()[0]["jsonrpc"]?.jsonPrimitive?.content)
    }

    // =========================================================================
    // Notifications — must produce no response
    // =========================================================================

    @Test
    fun `notification without id field produces no response`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(notification("notifications/initialized"))
        assertEquals(0, h.runAndGetObjects().size)
    }

    @Test
    fun `notification with null id produces no response`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":null,"method":"ping"}""")
        assertEquals(0, h.runAndGetObjects().size)
    }

    @Test
    fun `cancellation notification produces no response`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(notification("notifications/cancelled", """{"requestId":"123","reason":"user"}"""))
        assertEquals(0, h.runAndGetObjects().size)
    }

    @Test
    fun `progress notification produces no response`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(notification("notifications/progress", """{"progressToken":"tok","progress":50}"""))
        assertEquals(0, h.runAndGetObjects().size)
    }

    @Test
    fun `roots list changed notification produces no response`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(notification("notifications/roots/list_changed"))
        assertEquals(0, h.runAndGetObjects().size)
    }

    // =========================================================================
    // tools/list
    // =========================================================================

    @Test
    fun `tools list result has tools array`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/list"))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertTrue(result["tools"] is JsonArray)
    }

    @Test
    fun `tools list contains at least the aggregate proxy tools`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/list"))
        val tools = (h.runAndGetObjects()[0]["result"] as JsonObject)["tools"] as JsonArray
        val names = tools.map { (it as JsonObject)["name"]?.jsonPrimitive?.content }
        assertTrue(AGGREGATE_TOOL_PROJECTS in names)
        assertTrue(AGGREGATE_TOOL_WINDOWS in names)
    }

    @Test
    fun `each tool has a name field`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/list"))
        val tools = (h.runAndGetObjects()[0]["result"] as JsonObject)["tools"] as JsonArray
        for (tool in tools) {
            assertNotNull((tool as JsonObject)["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `each tool has an inputSchema field of type object`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/list"))
        val tools = (h.runAndGetObjects()[0]["result"] as JsonObject)["tools"] as JsonArray
        for (tool in tools) {
            val schema = (tool as JsonObject)["inputSchema"] as? JsonObject
            assertNotNull(schema, "Tool ${tool["name"]} missing inputSchema")
            assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `tools list response has no error field`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/list"))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        assertNotNull(resp["result"])
    }

    // =========================================================================
    // resources/list
    // =========================================================================

    @Test
    fun `resources list result has resources array`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "resources/list"))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertTrue(result["resources"] is JsonArray)
    }

    @Test
    fun `resources list response has no error field`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "resources/list"))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
    }

    // =========================================================================
    // resources/read
    // =========================================================================

    @Test
    fun `resources read with missing uri returns minus32602`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "resources/read", """{}"""))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(-32602, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `resources read with unknown uri returns error`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "resources/read", """{"uri":"mcp-steroid://nonexistent"}"""))
        val resp = h.runAndGetObjects()[0]
        assertNotNull(resp["error"])
    }

    // =========================================================================
    // tools/call
    // =========================================================================

    @Test
    fun `tools call with missing tool name returns tool error not protocol error`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/call", """{"arguments":{}}"""))
        val resp = h.runAndGetObjects()[0]
        // Must be a result with isError=true — not a JSON-RPC error object
        assertNull(resp["error"])
        val result = resp["result"] as JsonObject
        assertEquals("true", result["isError"]?.jsonPrimitive?.content?.lowercase())
    }

    @Test
    fun `tools call with no online servers returns tool routing error`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/call", """{"name":"some_tool","arguments":{}}"""))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        val result = resp["result"] as JsonObject
        assertEquals("true", result["isError"]?.jsonPrimitive?.content?.lowercase())
        val content = result["content"] as JsonArray
        assertTrue(content.isNotEmpty())
        assertNotNull((content[0] as JsonObject)["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools call aggregate projects returns successful tool result`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/call", """{"name":"$AGGREGATE_TOOL_PROJECTS","arguments":{}}"""))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        val result = resp["result"] as JsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.content?.lowercase() == "true")
        val content = result["content"] as JsonArray
        assertTrue(content.isNotEmpty())
    }

    @Test
    fun `tools call aggregate windows returns successful tool result`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/call", """{"name":"$AGGREGATE_TOOL_WINDOWS","arguments":{}}"""))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        val result = resp["result"] as JsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.content?.lowercase() == "true")
    }

    @Test
    fun `tools call tool error has content array with text items`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "tools/call", """{"name":"nonexistent","arguments":{}}"""))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        val content = result["content"] as JsonArray
        val first = content[0] as JsonObject
        assertEquals("text", first["type"]?.jsonPrimitive?.content)
        assertNotNull(first["text"]?.jsonPrimitive?.content)
    }

    // =========================================================================
    // Response structure invariants
    // =========================================================================

    @Test
    fun `all responses have jsonrpc field equal to 2dot0`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        h.sendFramed(req("2", "unknown/method"))
        h.sendFramed("bad json")
        val responses = h.runAndGetObjects()
        assertEquals(3, responses.size)
        for (resp in responses) {
            assertEquals("2.0", resp["jsonrpc"]?.jsonPrimitive?.content,
                "Expected jsonrpc=2.0 in: $resp")
        }
    }

    @Test
    fun `success response has result and no error`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        val resp = h.runAndGetObjects()[0]
        assertNotNull(resp["result"])
        assertNull(resp["error"])
    }

    @Test
    fun `error response has error and no result`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "no/such/method"))
        val resp = h.runAndGetObjects()[0]
        assertNotNull(resp["error"])
        assertNull(resp["result"])
    }

    @Test
    fun `response id matches string request id`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("my-special-id", "ping"))
        assertEquals("my-special-id", h.runAndGetObjects()[0]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `response id matches numeric request id`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":42,"method":"ping"}""")
        assertEquals(42, h.runAndGetObjects()[0]["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `error code is an integer`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "unknown"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        // Must be parseable as int — not a string
        val code = error["code"]?.jsonPrimitive?.int
        assertNotNull(code)
    }

    @Test
    fun `error object has message string field`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "unknown"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertNotNull(error["message"]?.jsonPrimitive?.content)
    }

    // =========================================================================
    // Multiple sequential requests
    // =========================================================================

    @Test
    fun `three sequential pings return three responses`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        h.sendFramed(req("2", "ping"))
        h.sendFramed(req("3", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(3, responses.size)
        assertEquals("1", responses[0]["id"]?.jsonPrimitive?.content)
        assertEquals("2", responses[1]["id"]?.jsonPrimitive?.content)
        assertEquals("3", responses[2]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mixed request types in sequence all get responses`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        h.sendFramed(req("2", "tools/list"))
        h.sendFramed(req("3", "resources/list"))
        val responses = h.runAndGetObjects()
        assertEquals(3, responses.size)
        assertNull(responses[0]["error"])
        assertNull(responses[1]["error"])
        assertNull(responses[2]["error"])
    }

    @Test
    fun `notifications interspersed with requests do not add extra responses`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        h.sendFramed(notification("notifications/initialized"))
        h.sendFramed(req("2", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size)
    }

    @Test
    fun `empty input produces no output`() = runTest {
        val h = McpTestHarness()
        assertEquals(0, h.runAndGetObjects().size)
    }

    // =========================================================================
    // Batch requests (JSON array)
    // =========================================================================

    @Test
    fun `batch with two pings returns array with two responses`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},{"jsonrpc":"2.0","id":"2","method":"ping"}]""")
        val elements = h.runAndGetElements()
        assertEquals(1, elements.size)
        val batch = elements[0] as JsonArray
        assertEquals(2, batch.size)
    }

    @Test
    fun `batch responses all have jsonrpc 2dot0`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},{"jsonrpc":"2.0","id":"2","method":"ping"}]""")
        val batch = h.runAndGetElements()[0] as JsonArray
        for (item in batch) {
            assertEquals("2.0", (item as JsonObject)["jsonrpc"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `all-notification batch produces no output`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"x"}}]""")
        assertEquals(0, h.runAndGetElements().size)
    }

    @Test
    fun `batch with request and notification returns single-element array`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""")
        val elements = h.runAndGetElements()
        assertEquals(1, elements.size)
        val batch = elements[0] as JsonArray
        assertEquals(1, batch.size)  // only the ping response, not the notification
    }

    @Test
    fun `batch with mixed methods returns responses for requests only`() = runTest {
        val h = McpTestHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},{"jsonrpc":"2.0","id":"2","method":"tools/list"}]""")
        val batch = h.runAndGetElements()[0] as JsonArray
        assertEquals(2, batch.size)
        val ids = batch.map { (it as JsonObject)["id"]?.jsonPrimitive?.content }.toSet()
        assertTrue("1" in ids)
        assertTrue("2" in ids)
    }

    // =========================================================================
    // Framing mode — framed input → framed output
    // =========================================================================

    @Test
    fun `framed input produces framed output with Content-Length header`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        val raw = h.runRaw().toString(Charsets.UTF_8)
        assertTrue(raw.startsWith("Content-Length:"), "Expected Content-Length header in: $raw")
    }

    @Test
    fun `framed output can be round-tripped through FramingBuffer`() = runTest {
        val h = McpTestHarness()
        h.sendFramed(req("1", "ping"))
        val raw = h.runRaw()
        val buffer = FramingBuffer()
        buffer.append(raw)
        val frame = buffer.readNextFrame()
        assertNotNull(frame)
        assertEquals("framed", frame.mode)
    }

    // =========================================================================
    // Framing mode — NDJSON input → NDJSON output
    // =========================================================================

    @Test
    fun `ndjson input produces ndjson output without Content-Length header`() = runTest {
        val h = McpTestHarness()
        h.sendNdjson("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
        val raw = h.runRaw().toString(Charsets.UTF_8)
        assertFalse(raw.contains("Content-Length"), "NDJSON output must not have Content-Length header")
        assertTrue(raw.endsWith("\n"), "NDJSON output must end with newline")
    }

    @Test
    fun `ndjson output is valid JSON followed by newline`() = runTest {
        val h = McpTestHarness()
        h.sendNdjson("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
        val raw = h.runRaw().toString(Charsets.UTF_8)
        val withoutTrailingNewline = raw.trimEnd('\n', '\r')
        val parsed = Json.parseToJsonElement(withoutTrailingNewline)
        assertTrue(parsed is JsonObject)
        assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ndjson output can be round-tripped through FramingBuffer`() = runTest {
        val h = McpTestHarness()
        h.sendNdjson("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
        val raw = h.runRaw()
        val buffer = FramingBuffer()
        buffer.append(raw)
        val frame = buffer.readNextFrame()
        assertNotNull(frame)
        assertEquals("ndjson", frame.mode)
    }
}
