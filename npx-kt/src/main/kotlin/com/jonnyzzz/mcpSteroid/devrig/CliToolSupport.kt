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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Shared rendering, exit-code, and progress-reporting plumbing for `devrig` subcommands that call
 * through to an existing MCP tool — both the manual, bespoke commands that exist today (`project`,
 * `backend`, `install`) and, once wired up, the tool-backed commands issue #284's schema-driven CLI
 * generates one per `steroid_*` tool. It factors out only the parts every such command repeats:
 * rendering a [ToolCallResult] to stdout/stderr, a stable `--json` envelope, meaningful exit codes
 * ([CliExit]), and progress plumbing ([stderrProgressReporter]). The tool behavior itself always
 * lives behind the existing bridge handlers (single source of truth); this file never reimplements
 * it, only presents the result.
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
                    is ContentItem.Image -> {
                        // A hard image failure (undecodable payload, unwritable disk) outranks the tool's
                        // own success/failure: abort rendering immediately and report it as the exit code,
                        // rather than letting a corrupt/unsaved image hide behind an otherwise-OK exit.
                        val failureExit = renderImage(item, index, sink, err)
                        if (failureExit != null) return failureExit
                    }
                    is ContentItem.Resource -> {
                        val res = item.resource
                        sink.println("[resource: ${res.uri}${res.mimeType?.let { " ($it)" } ?: ""}]")
                        res.text?.let { sink.println(it) }
                    }
                }
            }
            return if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }

        /** Renders [item]; returns a non-null exit code only when rendering must abort (bad payload or a
         * filesystem write failure), `null` when it printed normally and rendering should continue. */
        private fun renderImage(item: ContentItem.Image, index: Int, sink: PrintStream, err: PrintStream): Int? {
            val decoded = try {
                Base64.getDecoder().decode(item.data)
            } catch (e: IllegalArgumentException) {
                err.println("image payload was not valid base64 (${e.message})")
                null
            }
            if (decoded == null) {
                sink.println("[image: ${item.mimeType}, undecodable]")
                return CliExit.DATA_ERROR
            }
            val ext = item.mimeType.substringAfterLast('/', "png")
            return try {
                val file = Files.createTempFile(imageDir(), "image-$index-", ".$ext")
                Files.write(file, decoded)
                sink.println("Saved image: ${file.toAbsolutePath()}")
                null
            } catch (e: IOException) {
                err.println("failed to write image: ${e.message}")
                CliExit.IO_ERROR
            }
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
 * parameter. It redirects the FIRST returned image AFTER the tool call returns to a caller-chosen path
 * instead of [HomePaths.tmpDir]. It is not screenshot-only: `steroid_execute_code` also returns a PNG
 * (the modal-dialog failure screenshot) via `ExecutionManager.logImage`, so any tool's result may carry
 * one — and `execute_code` in particular can carry two (a `logImage` the script produced, plus a
 * dialog-failure screenshot), which is why only the image actually written is removed below.
 *
 * Composes [Presentation.render] rather than living as a third [Presentation] member: a per-tool
 * rendering method on the shared interface is exactly the duplication [Presentation] exists to avoid.
 *
 * When [outPath] is `null` (the flag was not passed) this delegates straight to [Presentation.render].
 *
 * The user explicitly asked for an image to be saved, so every failure to do that — no image in the
 * result, an undecodable payload, or a write failure — is [CliExit.DATA_ERROR] / [CliExit.IO_ERROR],
 * never a silent no-op: `devrig take_screenshot --out shot.png && open shot.png` must not report success
 * with `shot.png` absent. The accepted cost is that a caller who passes `--out` speculatively (on the
 * chance of a failure screenshot that may never materialize) now fails an otherwise-successful run.
 *
 * The image that IS written is removed from the rendered content by identity (not "every image") so the
 * console and `--json` presentations agree: its bytes go to [outPath] and only its path is reported
 * (`savedOut` under `--json`; a path line on the console) — never a second copy under [HomePaths.tmpDir]
 * or a second copy of its base64 in the envelope. Any OTHER image in the result passes through the
 * normal [Presentation.render] path untouched.
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
        val message = "--out was given but the $command result carries no image; nothing was written to $outPath"
        return presentation.renderError(command, message, CliExit.DATA_ERROR, out, err)
    }

    val decoded = try {
        Base64.getDecoder().decode(image.data)
    } catch (e: IllegalArgumentException) {
        val message = "--out image payload was not valid base64: ${e.message}"
        return presentation.renderError(command, message, CliExit.DATA_ERROR, out, err)
    }

    try {
        outPath.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }
        Files.write(outPath, decoded)
    } catch (e: IOException) {
        val message = "failed to write --out to $outPath: ${e.message}"
        return presentation.renderError(command, message, CliExit.IO_ERROR, out, err)
    }

    val savedOutPath = outPath.toAbsolutePath().toString()
    val remaining = result.content.filterNot { it === image }
    return when (presentation) {
        is Presentation.Json -> {
            val strippedResult = result.copy(content = remaining)
            val data = buildJsonObject {
                for ((key, value) in strippedResult.contentDataJson()) put(key, value)
                put("savedOut", savedOutPath)
            }
            out.println(cliEnvelopeJson(command, isError = result.isError, data = data))
            if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }
        is Presentation.Console -> {
            val withNote = result.copy(content = remaining + ContentItem.Text("Saved --out: $savedOutPath"))
            presentation.render(withNote, command, out, err)
        }
    }
}

/**
 * The unified JSON envelope for `devrig` CLI commands that render a [ToolCallResult] through
 * [Presentation.Json] — `{tool, command, isError, data}`, where `data` is always the tool-result
 * shape this file produces: `{content:[...]}`, plus `savedOut` when `--out` wrote an image (see
 * [renderWithOut]). Commands that predate the schema-driven CLI and never produce a [ToolCallResult]
 * at all — `devrig project --json`, for instance — hand-roll their own JSON today and are out of
 * scope for this envelope; under the schema-driven design a tool-backed command reports through this
 * same shape instead.
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

/**
 * Extracts native serialized `content` for command-specific envelopes, unpacking each text item's
 * payload under `content[].json` instead of `content[].text` when that payload parses whole as a JSON
 * object or array. `--json`'s entire audience is machine consumers; leaving a tool's own JSON payload
 * double-encoded as an escaped string forces every caller to parse twice. A bare scalar (`123`, `"hi"`,
 * `true`) or anything that fails to parse (prose, an `execute_code` transcript with JSON embedded
 * mid-log) stays under `text`, unchanged and unvalidated — this is presentation, not a schema check.
 * Image and resource items are untouched. `devrig mcp` (stdio) never calls this: it still serializes
 * [ToolCallResult] as native MCP content, where a JSON-shaped tool payload staying a text string is
 * correct wire behavior.
 */
fun ToolCallResult.contentDataJson(): JsonObject = buildJsonObject {
    val native = McpJson.encodeToJsonElement(ToolCallResult.serializer(), this@contentDataJson).jsonObject
    val content = native.getValue("content").jsonArray
    put("content", buildJsonArray { for (item in content) add(unpackJsonPayload(item.jsonObject)) })
}

/** Replaces a text content item's `text` key with `json` when [item]'s `text` payload parses whole as a
 * JSON object or array; returns [item] unchanged otherwise (non-text items, scalars, parse failures). */
private fun unpackJsonPayload(item: JsonObject): JsonObject {
    val text = item["text"] ?: return item
    val parsed = parseAsJsonContainer(text.jsonPrimitive.content) ?: return item
    return buildJsonObject {
        put("type", item.getValue("type"))
        put("json", parsed)
    }
}

/** Parses [text] as JSON, returning it only when the whole document is an object or array. A bare
 * scalar is prose that happens to look like JSON, not a tool's structured payload, so it is left to the
 * caller to keep under `text`. Any parse failure (the common case: plain prose, a transcript with JSON
 * embedded mid-log) is silently `null` — this is a presentation choice, not validation. */
private fun parseAsJsonContainer(text: String): JsonElement? {
    val parsed = try {
        McpJson.parseToJsonElement(text)
    } catch (e: SerializationException) {
        return null
    }
    return parsed.takeIf { it is JsonObject || it is JsonArray }
}
