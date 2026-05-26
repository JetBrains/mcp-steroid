/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runtime classloader probe — launched by `VerifyBundledKotlinxRuntimeTask`
 * with a classpath of *only* every jar under the IDE's lib dir plus this jar
 * (no kotlinx-coroutines / kotlinx-serialization shipped alongside). Validates
 * that the plugin's compile-time kotlinx pins are link-compatible with what
 * IntelliJ 261/262 bundles inside `lib/intellij.libraries.kotlinx.*.jar`.
 *
 * Per the JetBrains rule
 * (https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#coroutinesLibraries),
 * the plugin must use the IDE-bundled `kotlinx-coroutines` at runtime —
 * never ship its own. Our `configurations.implementation.exclude(group = "org.jetbrains.kotlinx")`
 * already prevents bundling, but a compile-time-only API drift (e.g.
 * kotlinx-serialization adding or signature-changing a method we call) would
 * only surface at runtime in the IDE. This probe is the build-time gate that
 * fails fast.
 *
 * Coverage was deliberately broadened (followup #9 from the c6+c7 quorum
 * review) so a real bytecode-incompat across the production-relevant
 * surface gets caught, not just the smoke path. The probe now exercises:
 *
 * **kotlinx-serialization (json + core)**
 *  - `Json.encodeToString(...)` over a `@Serializable` data class (codegen).
 *  - `Json.decodeFromString(...)` over the same JSON (deserialization path).
 *  - `Json.parseToJsonElement(...)` + `jsonObject` / `jsonPrimitive`
 *    extension accessors (tree API).
 *  - `buildJsonObject { put(...) }` (DSL + JsonObject construction).
 *
 * **kotlinx-coroutines (core)**
 *  - `runBlocking { ... }` (structured concurrency).
 *  - `delay(...)` (Dispatcher / TimeSource).
 *  - `withContext(Dispatchers.Default) { ... }` (context switching).
 *  - `Channel<T>.send(...)` / `receive()` (Channel API).
 *  - `MutableStateFlow<T>.value` round-trip (StateFlow API).
 *  - `flow { emit(...) }.fold(...)` (Flow builder + terminal operator).
 *  - `CompletableDeferred<T>.complete(...)` / `await()` (Deferred).
 *
 * On success it prints `RUNTIME_PROBE_OK: ...` and exits 0. Any
 * `LinkageError` / `NoClassDefFoundError` / `IncompatibleClassChangeError`
 * surfaces as a non-zero exit, which the Gradle task interprets as a build
 * failure. The probe does NOT exercise `kotlinx-io` — the repo doesn't
 * directly depend on it; the IDE bundles it transitively via ktor only.
 * Add a Buffer round-trip if and when production code starts using kotlinx-io.
 */
@Serializable
internal data class KotlinxRuntimeProbeMessage(
    val ide: String,
    val build: String,
    val kotlinSerializationOk: Boolean,
)

object KotlinxRuntimeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val ide = args.getOrNull(0) ?: "unknown"
        val build = args.getOrNull(1) ?: "unknown"

        // === kotlinx-serialization: encode -> decode round-trip + JsonObject DSL ===
        val payload = KotlinxRuntimeProbeMessage(
            ide = ide,
            build = build,
            kotlinSerializationOk = true,
        )
        val json = Json.encodeToString(payload)
        println("RUNTIME_PROBE_JSON_ENCODE_OK: $json")

        val decoded = Json.decodeFromString<KotlinxRuntimeProbeMessage>(json)
        check(decoded == payload) {
            "decodeFromString round-trip mismatch: $decoded != $payload"
        }
        println("RUNTIME_PROBE_JSON_DECODE_OK")

        val tree = Json.parseToJsonElement(json).jsonObject
        check(tree["ide"]?.jsonPrimitive?.content == ide) {
            "tree-API ide field mismatch: ${tree["ide"]} != $ide"
        }
        println("RUNTIME_PROBE_JSON_TREE_OK")

        val built: JsonObject = buildJsonObject {
            put("k", JsonPrimitive(42))
        }
        check(built["k"]?.jsonPrimitive?.content == "42") {
            "buildJsonObject value mismatch: ${built["k"]}"
        }
        println("RUNTIME_PROBE_JSON_BUILD_OK")

        // === kotlinx-coroutines: structured concurrency + Channel + StateFlow + Flow + Deferred ===
        runBlocking {
            delay(1)

            val onDefault = withContext(Dispatchers.Default) { "default-dispatch-ok" }
            println("RUNTIME_PROBE_COROUTINES_WITHCONTEXT_OK: $onDefault")

            val ch = Channel<Int>()
            val deferredSum = CompletableDeferred<Int>()
            coroutineScope {
                launch {
                    repeat(3) { ch.send(it + 1) }
                }
                launch {
                    var sum = 0
                    repeat(3) { sum += ch.receive() }
                    deferredSum.complete(sum)
                }
            }
            val channelSum = deferredSum.await()
            check(channelSum == 6) { "channel sum should be 6, got $channelSum" }
            println("RUNTIME_PROBE_COROUTINES_CHANNEL_OK")

            val stateFlow = MutableStateFlow(0)
            stateFlow.value = 7
            check(stateFlow.value == 7) { "StateFlow value should be 7, got ${stateFlow.value}" }
            println("RUNTIME_PROBE_COROUTINES_STATEFLOW_OK")

            val flowSum = sampleFlow().fold(0) { acc, x -> acc + x }
            check(flowSum == 10) { "Flow fold should be 10, got $flowSum" }
            println("RUNTIME_PROBE_COROUTINES_FLOW_OK")
        }

        println("RUNTIME_PROBE_OK")
    }

    private fun sampleFlow(): Flow<Int> = flow {
        emit(1); emit(2); emit(3); emit(4)
    }
}
