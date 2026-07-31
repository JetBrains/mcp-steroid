/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
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

/** Everything one dispatched command produced: its exit code and everything it wrote to devrig's stdout. */
data class GeneratedToolRun(val exit: Int, val stdout: String) {
    /** The `--json` envelope parsed back, for asserting its shape rather than its exact formatting. */
    fun envelope(): JsonObject = Json.parseToJsonElement(stdout).jsonObject
}

/**
 * Parses [args] and asserts the invocation reached a generated tool command, so a test that mistypes a
 * flag fails on the parse rather than silently asserting against a help or parse-error command.
 */
fun parseRunTool(vararg args: String): DevrigCommand.RunTool {
    val command = parseDevrigCommand(arrayOf(*args))
    return command as? DevrigCommand.RunTool
        ?: error("'devrig ${args.joinToString(" ")}' did not parse into a generated tool command, but into $command")
}

/**
 * Runs [command] through the runtime dispatcher against [tools], with [stdin] standing in for the process
 * standard input, and captures devrig's stdout. [home] is a scratch devrig home so nothing touches the
 * developer's real `~/.mcp-steroid`.
 */
fun runGeneratedToolForTest(
    home: Path,
    command: DevrigCommand.RunTool,
    tools: McpSteroidTools,
    stdin: ByteArray = ByteArray(0),
): GeneratedToolRun = withDevrigServices(home, stdin) { runGeneratedToolCommand(command, tools) }

/**
 * Runs [command] through the whole [runCli] router — the production path, including the `RunTool` arm —
 * against the real [com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools] wiring. With [home] a
 * scratch directory no IDE marker is discoverable, so the project-scoped handlers see no backends and
 * every lister answers from an empty routing table without opening a connection.
 */
fun runCliForToolTest(home: Path, command: DevrigCommand): GeneratedToolRun =
    withDevrigServices(home, ByteArray(0)) { runCli(command) }

private fun withDevrigServices(
    home: Path,
    stdin: ByteArray,
    block: DevrigServices.() -> Int,
): GeneratedToolRun {
    val out = ByteArrayOutputStream()
    val lifetime = CloseableStackHost()
    val exit = try {
        DevrigServices(
            lifetime = lifetime,
            homePaths = HomePaths(home).also { it.mkdirsAll() },
            mcpStdin = ByteArrayInputStream(stdin),
            mcpStdout = PrintStream(out, true, Charsets.UTF_8),
        ).block()
    } finally {
        lifetime.closeAllStacks()
    }
    return GeneratedToolRun(exit, out.toString(Charsets.UTF_8).replace("\r\n", "\n"))
}
