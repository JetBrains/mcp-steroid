/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Shared fixtures for the runtime dispatcher tests. They drive the REAL tool specs from
 * `devrigToolSpecs()` — so a test exercises the same `spec.call()` path production takes, including the
 * spec's own argument parsing — and replace only the handler behind each spec with a double.
 */

/**
 * A tool source whose handlers are test doubles. Registered per handler INTERFACE, the same key
 * [McpSteroidTools.handler] looks up, so a spec asking for its handler gets the double and any spec whose
 * handler was not registered fails loudly instead of quietly reaching a real bridge.
 */
class FakeMcpSteroidTools : McpSteroidTools() {
    private val handlers = LinkedHashMap<Class<*>, Any>()

    fun <T : Any> with(type: Class<T>, handler: T): FakeMcpSteroidTools = apply { handlers[type] = handler }

    override fun <T> handler(type: Class<T>): T =
        type.cast(handlers[type] ?: error("no test double is registered for handler ${type.name}"))
}

/** Everything one dispatched command produced, with stdout and stderr kept separate. */
data class GeneratedToolRun(val exit: Int, val stdout: String, val stderr: String) {
    /** The `--json` envelope parsed back, for asserting its shape rather than its exact formatting. */
    fun envelope(): JsonObject = Json.parseToJsonElement(stdout).jsonObject
}

/**
 * Parses [args] and asserts the invocation reached a generated tool command, so a test that mistypes a
 * flag fails on the parse rather than silently asserting against a help or parse-error command.
 */
fun parseRunTool(vararg args: String): GeneratedToolInvocation {
    val command = parseDevrigCommand(arrayOf(*args))
    return command.generatedTool
        ?: error("'devrig ${args.joinToString(" ")}' did not parse into a generated tool command, but into $command")
}

/**
 * Runs [command] through the runtime dispatcher against [tools], with [stdin] standing in for the process
 * standard input, and captures devrig's stdout. [home] is a scratch devrig home so nothing touches the
 * developer's real `~/.mcp-steroid`.
 */
fun runGeneratedToolForTest(
    home: Path,
    command: GeneratedToolInvocation,
    tools: McpSteroidTools,
    stdin: ByteArray = ByteArray(0),
): GeneratedToolRun = withDevrigServices(home, stdin) { runGeneratedToolCommand(command, tools) }

/**
 * Runs [command] through the whole [runCli] router — the production path, including the `RunTool` arm —
 * against the real [com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools] wiring, with an EMPTY IDE
 * routing table.
 *
 * The empty table is injected, not hoped for. A scratch [home] does not isolate IDE discovery:
 * [HomePaths.markersDir] is anchored at the real `user.home` by design, so on a developer machine with an
 * IDE open these tests would reach it over localhost — and `list_windows` is all-or-nothing, so one stale
 * pid marker or one `/windows` fetch failing would turn a green CI test red on that machine. With no route
 * the listers answer from a known table and open no connection.
 */
fun runCliForToolTest(home: Path, command: DevrigCliInvocation): GeneratedToolRun =
    withDevrigServices(home, ByteArray(0)) services@{ runBlocking { command.execute(this@services) } }

fun runCliForToolTest(home: Path, command: GeneratedToolInvocation): GeneratedToolRun =
    withDevrigServices(home, ByteArray(0)) { runGeneratedToolCommand(command) }

private fun withDevrigServices(
    home: Path,
    stdin: ByteArray,
    block: DevrigServices.() -> Int,
): GeneratedToolRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val originalErr = System.err
    val lifetime = CloseableStackHost()
    val exit = try {
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        DevrigServices(
            lifetime = lifetime,
            homePaths = HomePaths(home).also { it.mkdirsAll() },
            mcpStdin = ByteArrayInputStream(stdin),
            mcpStdout = PrintStream(out, true, Charsets.UTF_8),
            ideStateProvider = { emptyList() },
        ).block()
    } finally {
        try {
            lifetime.closeAllStacks()
        } finally {
            System.setErr(originalErr)
        }
    }
    return GeneratedToolRun(
        exit,
        out.toString(Charsets.UTF_8).replace("\r\n", "\n"),
        err.toString(Charsets.UTF_8).replace("\r\n", "\n"),
    )
}
