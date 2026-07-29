/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Shared support for `devrig` subcommands that are thin frontends over an existing MCP tool.
 *
 * This is deliberately a small helper set, NOT a "CLI-from-MCP-schema" generator: it factors out
 * only the parts every tool-backed command repeats — rendering a [ToolCallResult] to stdout/stderr,
 * a stable `--json` envelope, meaningful exit codes, and progress plumbing. The tool behavior itself
 * always lives behind the existing bridge handlers (single source of truth); the CLI never
 * reimplements it.
 */

/** Stable process exit codes shared by tool-backed commands. */
object CliExit {
    /** Success. */
    const val OK: Int = 0

    /** The backend returned a [ToolCallResult] with `isError=true`. */
    const val TOOL_ERROR: Int = 1

    /** Bad invocation the user can fix: missing/blank required args, unknown project_name, malformed path. */
    const val USAGE: Int = 64

    /** The backend returned unusable data, such as an invalid image payload. */
    const val DATA_ERROR: Int = 65

    /** The command could not reach a backend / the bridge failed (no IDE running, connection refused). */
    const val UNAVAILABLE: Int = 69

    /** A filesystem read/write failure. */
    const val IO_ERROR: Int = 74
}

/** A [McpProgressReporter] that streams progress to stderr so stdout stays clean for data. */
fun stderrProgressReporter(err: PrintStream = System.err): McpProgressReporter =
    object : McpProgressReporter {
        override fun report(message: String) {
            err.println(message)
        }
    }

/**
 * Renders tool results as either a JSON envelope or human-readable console output. Deliberately holds
 * only these two members: rendering a whole result and rendering a CLI-level failure. A per-tool method
 * (e.g. a `renderScreenshotSaved`) does not belong here — that would put one tool's concern on an
 * abstraction every tool shares, the same duplication problem this file exists to remove. `--out`
 * (see [renderWithOut]) is therefore a free function that composes [render], not a third interface
 * member.
 */
sealed interface Presentation {
    /** Renders a [ToolCallResult] for [command] and returns the process exit code. */
    fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream = System.err): Int

    /** Renders a CLI-level failure (usage/parse, routing, bridge error) for [command]; returns [exit] verbatim. */
    fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream = System.err): Int

    /** `--json`: one stable envelope on stdout. */
    class Json : Presentation {
        override fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream): Int {
            out.println(result.toEnvelopeJson(command))
            return if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }

        override fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream): Int {
            val data = buildJsonObject {
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", message)
                    })
                }
            }
            out.println(cliEnvelopeJson(command, isError = true, data = data))
            return exit
        }
    }

    /** Human-readable output; image payloads are materialized under [imageDir]. */
    class Console(private val imageDir: () -> Path) : Presentation {
        override fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream): Int {
            val sink = if (result.isError) err else out
            for ((index, item) in result.content.withIndex()) {
                when (item) {
                    is ContentItem.Text -> sink.println(item.text)
                    is ContentItem.Image -> renderImage(item, index, sink, err)
                    is ContentItem.Resource -> {
                        val res = item.resource
                        sink.println("[resource: ${res.uri}${res.mimeType?.let { " ($it)" } ?: ""}]")
                        res.text?.let { sink.println(it) }
                    }
                }
            }
            return if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }

        private fun renderImage(item: ContentItem.Image, index: Int, sink: PrintStream, err: PrintStream) {
            val decoded = try {
                Base64.getDecoder().decode(item.data)
            } catch (e: IllegalArgumentException) {
                err.println("devrig: image payload was not valid base64 (${e.message})")
                null
            }
            if (decoded == null) {
                sink.println("[image: ${item.mimeType}, undecodable]")
                return
            }
            val ext = item.mimeType.substringAfterLast('/', "png")
            val file = Files.createTempFile(imageDir(), "image-$index-", ".$ext")
            Files.write(file, decoded)
            sink.println("Saved image: ${file.toAbsolutePath()}")
        }

        override fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream): Int {
            err.println(message)
            return exit
        }
    }
}

/** Maps the `--json` flag onto a concrete [Presentation]; the only place the boolean is branched on. */
fun presentationFor(json: Boolean, imageDir: () -> Path): Presentation =
    if (json) Presentation.Json() else Presentation.Console(imageDir)

/**
 * `--out` is a devrig CLI framework flag beside `--json`, accepted on every subcommand — never a tool
 * parameter. It redirects a returned image AFTER the tool call returns to a caller-chosen path instead
 * of [HomePaths.tmpDir]. It is not screenshot-only: `steroid_execute_code` also returns a PNG (the
 * modal-dialog failure screenshot) via `ExecutionManager.logImage`, so any tool's result may carry one.
 *
 * Composes [Presentation.render] rather than living as a third [Presentation] member: a per-tool
 * rendering method on the shared interface is exactly the duplication [Presentation] exists to avoid.
 *
 * When [outPath] is `null` (the flag was not passed) this delegates straight to [Presentation.render].
 *
 * Behavior on a result with no image: a stderr warning, and the tool's own result (success or error)
 * still decides the exit code — never a USAGE failure. `--out` is opportunistic: most calls that pass it
 * against a tool that *can* return an image (`execute_code`'s failure screenshot above all) will
 * legitimately not get one, because the dialog most calls are guarding against did not appear. Treating
 * "no image this time" as a usage error would fail the common, successful case merely for having asked
 * defensively for a screenshot that turned out not to be needed — the opposite of what a defensive flag
 * should do. A stderr warning keeps the diagnostic (the caller learns nothing was written) without
 * punishing an otherwise-successful invocation.
 *
 * Undecodable image data is [CliExit.DATA_ERROR]; a write failure is [CliExit.IO_ERROR] — both cases
 * ARE the caller's problem (a corrupt payload, an unwritable path), unlike the merely-absent case above.
 */
fun renderWithOut(
    presentation: Presentation,
    result: ToolCallResult,
    command: String,
    outPath: Path?,
    out: PrintStream,
    err: PrintStream = System.err,
): Int {
    if (outPath == null) return presentation.render(result, command, out, err)

    val image = result.content.filterIsInstance<ContentItem.Image>().firstOrNull()
    if (image == null) {
        err.println("devrig: --out was given but the $command result carries no image; nothing was written to $outPath")
        return presentation.render(result, command, out, err)
    }

    val decoded = try {
        Base64.getDecoder().decode(image.data)
    } catch (e: IllegalArgumentException) {
        val message = "--out image payload was not valid base64: ${e.message}"
        err.println("devrig: $message")
        return presentation.renderError(command, message, CliExit.DATA_ERROR, out, err)
    }

    try {
        outPath.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }
        Files.write(outPath, decoded)
    } catch (e: IOException) {
        val message = "failed to write --out to $outPath: ${e.message}"
        err.println("devrig: $message")
        return presentation.renderError(command, message, CliExit.IO_ERROR, out, err)
    }

    val savedOutPath = outPath.toAbsolutePath().toString()
    return when (presentation) {
        is Presentation.Json -> {
            val data = buildJsonObject {
                for ((key, value) in result.contentDataJson()) put(key, value)
                put("savedOut", savedOutPath)
            }
            out.println(cliEnvelopeJson(command, isError = result.isError, data = data))
            if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }
        is Presentation.Console -> {
            val remaining = result.content.filterNot { it is ContentItem.Image }
            val withNote = result.copy(content = remaining + ContentItem.Text("Saved --out: $savedOutPath"))
            presentation.render(withNote, command, out, err)
        }
    }
}

/**
 * The unified JSON envelope for all `devrig` CLI commands, shared across tool-backed subcommands.
 *
 * `data` shape is command-specific: `{content:[...]}` for tool-result commands, `{projects:[...]}`
 * for list_projects, `{windows,backgroundTasks}` for list_windows.
 */
val CLI_ENVELOPE_JSON: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

/** Wraps a command-specific [data] object in the unified envelope and renders it to a string. */
fun cliEnvelopeJson(command: String, isError: Boolean, data: JsonObject): String {
    val payload = buildJsonObject {
        put("tool", buildJsonObject {
            put("name", "devrig")
            put("version", DevrigVersionMetadata.getDevrigVersion())
        })
        put("command", command)
        put("isError", isError)
        put("data", data)
    }
    return CLI_ENVELOPE_JSON.encodeToString(JsonObject.serializer(), payload)
}

/** Envelope for a [ToolCallResult]: `data:{content:[...]}`. */
fun ToolCallResult.toEnvelopeJson(command: String): String =
    cliEnvelopeJson(command, isError, contentDataJson())

/** Extracts native serialized `content` for command-specific envelopes. */
fun ToolCallResult.contentDataJson(): JsonObject = buildJsonObject {
    val native = McpJson.encodeToJsonElement(ToolCallResult.serializer(), this@contentDataJson).jsonObject
    put("content", native.getValue("content"))
}
