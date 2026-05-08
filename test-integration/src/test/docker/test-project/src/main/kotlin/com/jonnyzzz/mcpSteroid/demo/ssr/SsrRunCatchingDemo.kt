/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo.ssr

/**
 * Fixture for StructuralSearchPromptTest — Kotlin `runCatching{}.onFailure{}` audit.
 *
 * Three byte-identical-shape pairs of `runCatching { … }.onFailure { … }` (different
 * bodies, same structural shape). The skill recipe in
 * `mcp-steroid://skill/structural-search-kotlin` teaches an agent to find these via
 * the apostrophe-form pattern:
 *
 *     runCatching {
 *         '_TRYBODY*
 *     }.onFailure { '_E ->
 *         '_HANDLER*
 *     }
 *
 * Inspired by the canonical Kotlin K2 search corpus:
 * https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin/code-insight/structural-search-k2/tests
 *
 * The integration test asserts the agent finds exactly 3 matches via SSR (not via
 * grep/regex/Bash). The skill article also flags this rewrite as **search-only by
 * default** because it would drop the `Result<T>` return value if the call site
 * consumes it — see the `consumesResult()` callsite below as a reminder.
 */
class SsrRunCatchingDemo(private val logger: (String) -> Unit) {

    fun loadConfig(): Map<String, String> {
        val cfg = mutableMapOf<String, String>()
        runCatching {
            cfg["host"] = System.getProperty("demo.host", "localhost")
            cfg["port"] = System.getProperty("demo.port", "8080")
        }.onFailure { e ->
            logger("[loadConfig] failed: ${e.message}")
            cfg["host"] = "127.0.0.1"
            cfg["port"] = "0"
        }
        return cfg
    }

    fun decodePayload(raw: String): String? {
        var decoded: String? = null
        runCatching {
            decoded = raw.reversed().trim()
            require(decoded!!.isNotEmpty()) { "payload is empty after decode" }
        }.onFailure { ex ->
            logger("[decodePayload] swallowed: ${ex.javaClass.simpleName}")
            decoded = null
        }
        return decoded
    }

    fun closeSilently(closeable: AutoCloseable?) {
        runCatching {
            closeable?.close()
        }.onFailure { t ->
            logger("[closeSilently] swallowed: $t")
        }
    }

    /**
     * Mixed-in "consumesResult" call: the skill article warns that rewriting
     * `runCatching{…}.onFailure{…}` to `try/catch` here would lose the `Result<T>`.
     * The integration test prompt asks the agent NOT to flag this construct as
     * a candidate replacement.
     */
    fun consumesResult(raw: String): String {
        return runCatching {
            raw.toInt().toString()
        }.getOrElse { "<n/a>" }
    }
}
