/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets

/**
 * Stand-alone stdout-cleanliness assertions for any stdio MCP server.
 *
 * Use [handshakeBytes] as the stdin to a launcher run, then pass the captured
 * stdout to [assertStdoutClean]. Both halves of the test fit ProcessRunner /
 * `startProcessInContainer`'s fire-and-forget shape (one fixed handshake →
 * wait for exit → inspect stdout) — no need to drag in the streaming
 * [StdioMcpProcess] harness.
 *
 * Exists in test-helper so any stdio MCP server in the repo can pin the same
 * "stdout is exclusively NDJSON frames, no banner, no log noise" invariant.
 *
 * The MCP protocol version is inlined here on purpose — the test-helper
 * module deliberately doesn't depend on the production `:mcp-core` for one
 * constant.
 *
 * Failures throw plain [AssertionError] rather than calling into a JUnit
 * assertion API: test-helper is consumed by callers that use different JUnit
 * versions, so the main classpath stays JUnit-Jupiter-free. Every JUnit
 * version surfaces an `AssertionError` as a test failure.
 */
object StdoutCleanlinessHarness {

    /**
     * The JSON-RPC frames the harness sends during the handshake. id=1
     * initialize, id=2 tools/list, id=3 ping — each id MUST round-trip in the
     * response set, which is what [assertStdoutClean] enforces.
     */
    val handshakeBytes: ByteArray by lazy {
        buildString {
            appendLine(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                    """{"protocolVersion":"$MCP_PROTOCOL_VERSION_FOR_HANDSHAKE",""" +
                    """"capabilities":{},""" +
                    """"clientInfo":{"name":"stdout-cleanliness","version":"0.0"}}}"""
            )
            appendLine("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            appendLine("""{"jsonrpc":"2.0","id":3,"method":"ping","params":{}}""")
        }.toByteArray(StandardCharsets.UTF_8)
    }

    private const val MCP_PROTOCOL_VERSION_FOR_HANDSHAKE = "2025-11-25"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Assert that EVERY non-blank line in [stdout] is a JSON-RPC 2.0 envelope
     * for one of the three handshake ids. Caller is responsible for spawning
     * the launcher and capturing its stdout/stderr — typically through
     * `RunProcessRequest`/`startProcess()` or `startProcessInContainer`.
     * [stderrForDiagnostics] is included verbatim in failure messages — pass
     * an empty string if the caller has no stderr to surface.
     */
    fun assertStdoutClean(
        stdout: String,
        variantLabel: String,
        stderrForDiagnostics: String,
    ) {
        val nonBlank = stdout.lineSequence().filter { it.isNotBlank() }.toList()

        if (nonBlank.isEmpty()) {
            throw AssertionError(
                "[$variantLabel] expected at least one JSON-RPC frame on stdout but got none.\n" +
                        "stderr=\n$stderrForDiagnostics"
            )
        }

        val seenIds = mutableSetOf<String>()
        nonBlank.forEachIndexed { index, line ->
            val element = try {
                json.parseToJsonElement(line)
            } catch (e: Exception) {
                throw AssertionError(
                    "[$variantLabel] stdout line $index is not valid JSON: $line\n" +
                            "(if this looks like a banner or log, fix the launcher / logger so " +
                            "that only NDJSON frames reach stdout)\n" +
                            "stderr=\n$stderrForDiagnostics",
                    e,
                )
            }
            if (element !is JsonObject) {
                throw AssertionError("[$variantLabel] stdout line $index is JSON but not an object: $line")
            }
            val jsonrpc = element["jsonrpc"]?.jsonPrimitive?.contentOrNull
            if (jsonrpc != "2.0") {
                throw AssertionError(
                    "[$variantLabel] stdout line $index is JSON but not a JSON-RPC 2.0 envelope " +
                            "(jsonrpc=$jsonrpc): $line"
                )
            }
            // Every response carries the id we sent (1, 2, or 3).
            val id = element["id"]?.jsonPrimitive?.contentOrNull
            if (id != null) seenIds += id
        }

        // Each of our three requests must produce a response — i.e. all three ids
        // must round-trip.
        val expectedIds = setOf("1", "2", "3")
        val missing = expectedIds - seenIds
        if (missing.isNotEmpty()) {
            throw AssertionError(
                "[$variantLabel] expected responses for ids $expectedIds, missing $missing.\n" +
                        "stdout=\n${nonBlank.joinToString("\n")}\n" +
                        "stderr=\n$stderrForDiagnostics"
            )
        }
    }
}
