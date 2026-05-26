package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Schema-versioned JSON document written to
 * `~/.mcp-steroid/markers/<pid>.mcp-steroid` by every IDE that runs the
 * MCP Steroid plugin. Discovered by external monitors, including devrig,
 * to learn where the IDE's MCP server is reachable.
 *
 * Forward compatibility: readers MUST decode with [PidMarkerJson], which
 * is configured to ignore unknown JSON keys so new optional fields stay
 * backwards-readable.
 *
 * All constructor parameters are explicit on purpose — there are no
 * defaults — so every writer is forced to think about every field. Tests
 * keep their own local helpers when they need a quick stub.
 */
@Serializable
data class PidMarker(
    val schema: Int,
    val pid: Long,
    val mcpSteroidServer: McpSteroidServerInfo,
    val ide: IdeInfo,
    val plugin: PluginInfo,
    val createdAt: String,
    val intellijWebServer: IntelliJWebServerInfo?,
    val intellijMcpServer: IntelliJMcpServerInfo?,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** Directory the plugin writes markers into: `<userHome>/.mcp-steroid/markers`. */
        fun markerDirectory(userHome: Path): Path =
            userHome.resolve(".mcp-steroid").resolve("markers")

        /** Filename inside [markerDirectory] for a running IDE pid. */
        fun markerFileNameFor(pid: Long): String = "$pid.mcp-steroid"

        /** Parses the pid out of a marker filename, or returns `null` for an unrelated file. */
        fun pidFromFileName(fileName: String): Long? {
            val match = FILE_NAME_REGEX.matchEntire(fileName) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        private val FILE_NAME_REGEX = Regex("""^(\d+)\.mcp-steroid$""")
    }
}

/**
 * Tolerant JSON codec for [PidMarker]. `ignoreUnknownKeys = true` is the
 * forward-compat hook; pretty-print keeps the file useful when a human
 * `cat`s it.
 */
object PidMarkerJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(marker: PidMarker): String = json.encodeToString(PidMarker.serializer(), marker)

    fun decode(text: String): PidMarker = json.decodeFromString(PidMarker.serializer(), text)
}
