/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessStreamType
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Subprocess-driven MCP stdio client harness for integration tests.
 *
 * Built on top of [StartedProcess] / [RunProcessRequest] — the same
 * ProcessRunner pipeline every other process-driving test in the repo uses.
 * The two streaming hooks make it work despite ProcessRunner being usually
 * fire-and-forget:
 *  - **stdin**: a `Channel<ByteArray>(UNLIMITED)` consumed as a `Flow` and
 *    handed to `RunProcessRequest.withStdin`. The harness pushes frames into
 *    the channel as needed; closing the channel signals stdin EOF to the
 *    server, which makes its `McpStdioServer.run()` return cleanly.
 *  - **stdout**: a coroutine collects `StartedProcess.messagesFlow` filtered
 *    to STDOUT lines and pushes parsed [JsonObject] envelopes into a
 *    [LinkedBlockingQueue]. [request] polls that queue for the matching id.
 *
 * Wire format: MCP 2025-11-25 JSON-RPC over stdio in NDJSON mode (one JSON
 * value per line — the simplest form the
 * `com.jonnyzzz.mcpSteroid.mcp.McpStdioServer` accepts and the form it
 * produces by default).
 *
 * Constructed exclusively via [startStdioMcpProcess] — that factory mirrors
 * `startDockerContainerAndDispose`: the caller hands in a [CloseableStack]
 * and gets back an already-started harness; teardown is owned by the
 * lifetime, with each underlying resource registered as a separate cleanup
 * action so failures during one teardown don't take the others down.
 */
class StdioMcpProcess internal constructor(
    private val started: StartedProcess,
    private val stdinChannel: SendChannel<ByteArray>,
    private val responseQueue: LinkedBlockingQueue<JsonObject>,
) {
    private val nextId = AtomicInteger(1)

    /**
     * Send an MCP `initialize` request and wait for the response. Also sends the
     * `notifications/initialized` notification afterwards as required by the spec.
     *
     * The protocol version is inlined as a literal — depending on `:mcp-core`
     * for one constant would couple the test infrastructure to the
     * implementation's package layout. If the spec moves on, bump this
     * literal; the `CliMcpStdioIntegrationTest.initialize returns server info`
     * assertion catches a mismatch.
     */
    fun initialize(timeoutMillis: Long = 10_000): JsonObject {
        val params = buildJsonObject {
            put("protocolVersion", "2025-11-25")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "stdio-mcp-process")
                put("version", "0.0")
            }
        }
        val response = request("initialize", params, timeoutMillis)
        notify("notifications/initialized", buildJsonObject {})
        return response
    }

    /**
     * Send a JSON-RPC request and block until the matching response arrives or
     * [timeoutMillis] elapses. The id is generated and matched internally.
     */
    fun request(method: String, params: JsonObject, timeoutMillis: Long = 10_000): JsonObject {
        val id = nextId.getAndIncrement()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        writeFrame(payload)

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            val element = responseQueue.poll(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: continue
            val responseId = (element["id"] as? JsonPrimitive)?.content
            if (responseId == id.toString()) return element
            // Out-of-band notifications or unrelated messages — surface them in
            // stderr so a flaky failure leaves a trace, then keep polling.
            System.err.println("[STDIO-CLIENT] discarding non-matching frame (expected id=$id): $element")
        }
        throw AssertionError("Timed out after ${timeoutMillis}ms waiting for response to method=$method id=$id")
    }

    fun notify(method: String, params: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        writeFrame(payload)
    }

    /**
     * Wait [timeoutMillis] for any unsolicited frame from the server and return
     * everything that arrived during the wait. Used by the
     * "notifications-without-id receive no response" test to assert that
     * sending a notification yields zero stdout frames — without this helper
     * the test relied on [request]'s "discard non-matching id" behaviour,
     * which silently masked an illegal response.
     *
     * Returns an empty list on quiet stdout (the success case).
     */
    fun drainNoMore(timeoutMillis: Long): List<JsonObject> {
        val collected = mutableListOf<JsonObject>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) return collected
            val element = responseQueue.poll(remaining, TimeUnit.MILLISECONDS) ?: return collected
            collected += element
        }
    }

    private fun writeFrame(obj: JsonObject) {
        val text = Json.encodeToString(JsonObject.serializer(), obj)
        val bytes = (text + "\n").toByteArray(StandardCharsets.UTF_8)
        // The channel is UNLIMITED, so trySend never fails for capacity. It
        // does fail if the channel is closed — that means the harness's
        // lifetime already triggered teardown, so writing a new frame is a
        // programming error worth surfacing.
        check(stdinChannel.trySend(bytes).isSuccess) {
            "stdin channel is closed; cannot send: $obj"
        }
    }
}

/**
 * Spawn the [launcher] as a subprocess via [RunProcessRequest]/[ProcessRunner]
 * and wire it up as a stdio MCP client harness. Teardown is owned by
 * [lifetime] — the factory creates a nested stack and registers each
 * underlying resource (stdin channel, collector scope, the [StartedProcess]
 * itself) as a separate cleanup action on it, in the order
 * collectors-first → stdin-EOF → process-shutdown so the server gets a
 * graceful exit when possible and a forced kill otherwise.
 */
fun startStdioMcpProcess(launcher: File, lifetime: CloseableStack): StdioMcpProcess {
    require(launcher.canExecute()) {
        "Launcher script is not executable: ${launcher.absolutePath}"
    }

    val stack = lifetime.nestedStack("stdio-mcp:${launcher.name}")

    // Channel-backed stdin: every writeFrame() pushes here, ProcessRunner's
    // own collector copies bytes to the subprocess's stdin and flushes.
    // UNLIMITED so writers never block if the subprocess is briefly slow to
    // drain stdin.
    val stdinChannel = Channel<ByteArray>(Channel.UNLIMITED)

    // Long-running collector pulls stdout NDJSON lines off
    // StartedProcess.messagesFlow into responseQueue. Lives in a dedicated
    // SupervisorJob so a parse error on one line doesn't kill the harness.
    val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val responseQueue = LinkedBlockingQueue<JsonObject>()

    val request = RunProcessRequest()
        .command(launcher.absolutePath)
        .withStdin(stdinChannel.consumeAsFlow())
        .logPrefix("stdio-mcp:${launcher.name}")
        .description("MCP stdio client harness for ${launcher.name}")
        // ProcessRunner's timeout is a safety net only — the harness's per-
        // request timeouts in [request] drive correctness. Set high enough
        // that no realistic interactive flow trips it.
        .withTimeout(Duration.ofMinutes(5))
        .quietly()

    val started: StartedProcess = request.startProcess()

    // Register cleanups in the order they should run (LIFO):
    //  1. (registered first → runs last) Forcibly destroy the subprocess if
    //     the graceful path didn't complete; awaitForProcessFinish() also
    //     reaps the runner's reader threads.
    stack.registerCleanupAction { destroyAndAwait(started) }

    //  2. (registered second → runs second-from-last) Close stdin so the
    //     server sees EOF and exits cleanly. ProcessRunner's stdin pump uses
    //     `process.outputStream.use { … }` — collection completes when the
    //     channel closes, the use-block closes the OutputStream, the server
    //     gets EOF.
    stack.registerCleanupAction { stdinChannel.close() }

    //  3. (registered last → runs first) Cancel the stdout collector so it
    //     doesn't keep polling messagesFlow after we've decided we're done.
    stack.registerCleanupAction { collectorScope.cancel() }

    started.messagesFlow
        .filter { it.type == ProcessStreamType.STDOUT }
        .onEach { line ->
            if (line.line.isBlank()) return@onEach
            val element = try {
                stdoutJson.parseToJsonElement(line.line)
            } catch (e: Exception) {
                System.err.println("[STDIO-CLIENT] non-JSON line from server: ${line.line} (${e.message})")
                return@onEach
            }
            if (element is JsonObject) responseQueue.add(element)
        }
        .launchIn(collectorScope)

    return StdioMcpProcess(
        started = started,
        stdinChannel = stdinChannel,
        responseQueue = responseQueue,
    )
}

private val stdoutJson = Json { ignoreUnknownKeys = true }

private fun destroyAndAwait(started: StartedProcess) {
    // awaitForProcessFinish honours the request's timeout (5 min above) and
    // also drains the runner's reader threads. If the process is already
    // dead — typical happy path because closeStdin → EOF → exit — this
    // returns near-instantly.
    runBlocking {
        try {
            started.awaitForProcessFinish()
        } catch (e: Exception) {
            System.err.println(
                "[STDIO-CLIENT] awaitForProcessFinish raised " +
                        "${e.javaClass.simpleName}: ${e.message}"
            )
            started.destroyForcibly()
        }
    }
}
