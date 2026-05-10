/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

// ----- PID / marker file utilities -----

fun isPidAlive(pid: Long): Boolean = ProcessHandle.of(pid).isPresent

data class MarkerEntry(
    val pid: Long,
    val url: String,
    val label: String,
    val markerPath: String
)

data class ParsedMarker(val url: String, val label: String)

fun parseMarkerContent(content: String, pid: Long): ParsedMarker? {
    val lines = content.split(Regex("\r?\n")).map { it.trim() }
    val url = lines.firstOrNull() ?: return null
    if (url.isEmpty()) return null

    var label: String? = null
    for (i in 1 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) continue
        if (line.startsWith("URL:")) continue
        if (line.startsWith("MCP Steroid Server")) continue
        if (line.startsWith("Created:")) continue
        if (line.startsWith("Plugin ")) continue
        if (line.startsWith("IDE ")) continue
        label = line
        break
    }

    return ParsedMarker(url = url, label = label ?: "pid:$pid")
}

fun isAllowedHost(urlValue: String, allowHosts: List<String>): Boolean {
    return try {
        val uri = URI(urlValue)
        allowHosts.contains(uri.host)
    } catch (e: Exception) {
        false
    }
}

fun scanMarkers(homeDir: java.io.File, allowHosts: List<String>): List<MarkerEntry> {
    val pattern = Regex("""^\.(\d+)\.mcp-steroid$""")
    return homeDir.listFiles()
        ?.filter { it.isFile }
        ?.mapNotNull { file ->
            val match = pattern.matchEntire(file.name) ?: return@mapNotNull null
            val pid = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            if (!isPidAlive(pid)) return@mapNotNull null
            val content = try { file.readText() } catch (e: Exception) { return@mapNotNull null }
            val parsed = parseMarkerContent(content, pid) ?: return@mapNotNull null
            if (!isAllowedHost(parsed.url, allowHosts)) return@mapNotNull null
            MarkerEntry(pid = pid, url = parsed.url, label = parsed.label, markerPath = file.absolutePath)
        } ?: emptyList()
}

// ----- URL utilities -----

fun baseUrlFromMcpUrl(serverUrl: String): String =
    serverUrl.replace(Regex("/mcp/?$"), "")

fun portFromUrl(urlValue: String): Int? {
    return try {
        val uri = URI(urlValue)
        when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
    } catch (e: Exception) {
        null
    }
}

fun buildServerId(pid: Long, urlValue: String, existing: Map<String, ServerEntry>): String {
    val baseId = "pid:$pid"
    val existingServer = existing[baseId] ?: return baseId
    if (existingServer.url == urlValue) return baseId
    val hash = MessageDigest.getInstance("SHA-1")
        .digest(urlValue.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(8)
    return "$baseId:$hash"
}

// ----- Time utilities -----

fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

fun compareIsoTimesDescending(left: String?, right: String?): Int {
    val leftMs = if (left != null) try { Instant.parse(left).toEpochMilli() } catch (e: Exception) { 0L } else 0L
    val rightMs = if (right != null) try { Instant.parse(right).toEpochMilli() } catch (e: Exception) { 0L } else 0L
    return when {
        leftMs > rightMs -> -1
        leftMs < rightMs -> 1
        else -> 0
    }
}

// ----- Version comparison -----

fun versionParts(value: String?): List<Int> {
    if (value.isNullOrBlank()) return emptyList()
    return Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
}

fun compareVersionPartsDescending(leftParts: List<Int>, rightParts: List<Int>): Int {
    val max = maxOf(leftParts.size, rightParts.size)
    for (i in 0 until max) {
        val left = leftParts.getOrElse(i) { 0 }
        val right = rightParts.getOrElse(i) { 0 }
        if (left > right) return -1
        if (left < right) return 1
    }
    return 0
}

fun compareVersionStringsDescending(left: String?, right: String?): Int =
    compareVersionPartsDescending(versionParts(left), versionParts(right))

fun extractBaseVersion(fullVersion: String?): String {
    if (fullVersion.isNullOrBlank()) return ""
    val trimmed = fullVersion.trim()
    val snapshotIdx = trimmed.indexOf("-SNAPSHOT")
    if (snapshotIdx > 0) return trimmed.substring(0, snapshotIdx)
    val dashIdx = trimmed.indexOf('-')
    if (dashIdx > 0) return trimmed.substring(0, dashIdx)
    return trimmed
}

fun isVersionNewer(candidateVersion: String?, currentVersion: String?): Boolean {
    val candidate = extractBaseVersion(candidateVersion)
    val current = extractBaseVersion(currentVersion)
    if (candidate.isEmpty() || current.isEmpty()) return false
    return compareVersionStringsDescending(candidate, current) < 0
}

// ----- Routing helpers -----

fun buildAliasUri(serverId: String, uri: String): String =
    "mcp-steroid+proxy://${java.net.URLEncoder.encode(serverId, "UTF-8")}/${java.net.URLEncoder.encode(uri, "UTF-8")}"

data class AliasUri(val serverId: String, val uri: String)

fun parseAliasUri(uri: String): AliasUri? {
    val prefix = "mcp-steroid+proxy://"
    if (!uri.startsWith(prefix)) return null
    val rest = uri.removePrefix(prefix)
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val serverId = java.net.URLDecoder.decode(rest.substring(0, slash), "UTF-8")
    val original = java.net.URLDecoder.decode(rest.substring(slash + 1), "UTF-8")
    return AliasUri(serverId = serverId, uri = original)
}

data class NamespacedTool(val serverId: String, val toolName: String)

fun parseNamespacedTool(name: String, serverIds: Set<String>): NamespacedTool? {
    val dot = name.indexOf('.')
    if (dot <= 0) return null
    val prefix = name.substring(0, dot)
    if (!serverIds.contains(prefix)) return null
    return NamespacedTool(serverId = prefix, toolName = name.substring(dot + 1))
}

fun projectKey(name: String?, path: String?): String = "${name ?: ""}\u0000${path ?: ""}"

// ----- Server metadata -----

data class IdeMetadata(
    val name: String? = null,
    val version: String? = null,
    val build: String? = null
)

data class PluginMetadata(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null
)

data class ServerMetadata(
    val pid: Long? = null,
    val mcpUrl: String? = null,
    val ide: IdeMetadata? = null,
    val plugin: PluginMetadata? = null,
    val paths: JsonObject? = null,
    val executable: JsonObject? = null
)

fun normalizeServerMetadataPayload(payload: JsonObject?): ServerMetadata? {
    if (payload == null) return null
    val ide = payload["ide"]?.takeIf { it !is JsonNull }?.jsonObject
    val plugin = payload["plugin"]?.takeIf { it !is JsonNull }?.jsonObject
    val paths = payload["paths"]?.takeIf { it is JsonObject }?.jsonObject
    val executable = payload["executable"]?.takeIf { it is JsonObject }?.jsonObject

    if (ide == null && plugin == null && paths == null && executable == null
        && payload["mcpUrl"] == null && payload["pid"] == null
    ) return null

    return ServerMetadata(
        pid = payload["pid"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        mcpUrl = payload["mcpUrl"]?.jsonPrimitive?.contentOrNull,
        ide = ide?.let {
            IdeMetadata(
                name = it["name"]?.jsonPrimitive?.contentOrNull,
                version = it["version"]?.jsonPrimitive?.contentOrNull,
                build = it["build"]?.jsonPrimitive?.contentOrNull
            )
        },
        plugin = plugin?.let {
            PluginMetadata(
                id = it["id"]?.jsonPrimitive?.contentOrNull,
                name = it["name"]?.jsonPrimitive?.contentOrNull,
                version = it["version"]?.jsonPrimitive?.contentOrNull
            )
        },
        paths = paths,
        executable = executable
    )
}

fun metadataFromInitializeResult(payload: JsonObject?): ServerMetadata? {
    if (payload == null) return null
    val serverInfo = payload["serverInfo"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
    val version = serverInfo["version"]?.jsonPrimitive?.contentOrNull ?: return null
    return ServerMetadata(
        plugin = PluginMetadata(
            id = serverInfo["name"]?.jsonPrimitive?.contentOrNull,
            name = serverInfo["name"]?.jsonPrimitive?.contentOrNull,
            version = version
        )
    )
}

fun metadataFromProductsPayload(payload: JsonObject?): ServerMetadata? {
    if (payload == null) return null
    val products = payload["products"]?.takeIf { it is JsonArray }?.jsonArray
    if (products.isNullOrEmpty()) return null
    return normalizeServerMetadataPayload(products.first().takeIf { it is JsonObject }?.jsonObject)
}

fun metadataFromWindowsPayload(payload: JsonObject?): ServerMetadata? =
    normalizeServerMetadataPayload(payload)

fun metadataFromProjectsPayload(payload: JsonObject?): ServerMetadata? =
    normalizeServerMetadataPayload(payload)

fun mergeServerMetadata(current: ServerMetadata?, patch: ServerMetadata?): ServerMetadata {
    if (patch == null) return current ?: ServerMetadata()
    val base = current ?: ServerMetadata()
    return ServerMetadata(
        pid = patch.pid ?: base.pid,
        mcpUrl = patch.mcpUrl ?: base.mcpUrl,
        ide = when {
            patch.ide != null && base.ide != null -> IdeMetadata(
                name = patch.ide.name ?: base.ide.name,
                version = patch.ide.version ?: base.ide.version,
                build = patch.ide.build ?: base.ide.build
            )
            else -> patch.ide ?: base.ide
        },
        plugin = when {
            patch.plugin != null && base.plugin != null -> PluginMetadata(
                id = patch.plugin.id ?: base.plugin.id,
                name = patch.plugin.name ?: base.plugin.name,
                version = patch.plugin.version ?: base.plugin.version
            )
            else -> patch.plugin ?: base.plugin
        },
        paths = if (patch.paths != null) {
            JsonObject((base.paths ?: emptyMap()) + patch.paths)
        } else base.paths,
        executable = if (patch.executable != null) {
            JsonObject((base.executable ?: emptyMap()) + patch.executable)
        } else base.executable
    )
}

// ----- Tool schema merging -----

fun mergeInputSchemas(schemas: List<JsonObject?>): JsonObject {
    val properties = mutableMapOf<String, JsonElement>()
    var required: Set<String>? = null

    for (schema in schemas) {
        if (schema == null) continue
        val props = schema["properties"]?.takeIf { it is JsonObject }?.jsonObject ?: continue
        for ((key, value) in props) {
            if (!properties.containsKey(key)) {
                properties[key] = value
            }
        }
        val req = schema["required"]?.takeIf { it is JsonArray }?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
        if (req != null) {
            required = if (required == null) req else required.intersect(req)
        }
    }

    if (!properties.containsKey("server_id")) {
        properties["server_id"] = buildJsonObject {
            put("type", "string")
            put("description", "Target server id for routing (optional)")
        }
    }

    return buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.orEmpty().map { JsonPrimitive(it) }))
    }
}

fun mergeToolGroups(toolGroups: Map<String, List<JsonObject>>): List<JsonObject> {
    return toolGroups.entries
        .map { (name, tools) ->
            val inputSchemas = tools.map { it["inputSchema"]?.takeIf { s -> s is JsonObject }?.jsonObject }
            val mergedInput = mergeInputSchemas(inputSchemas)
            val primary = tools.firstOrNull() ?: buildJsonObject {  }
            val desc = primary["description"]?.jsonPrimitive?.contentOrNull
                ?.let { "$it (aggregated; optional server_id)" }
                ?: "Aggregated tool (optional server_id)"

            buildJsonObject {
                put("name", name)
                put("description", desc)
                primary["title"]?.let { put("title", it) }
                put("inputSchema", mergedInput)
                primary["outputSchema"]?.let { put("outputSchema", it) }
            }
        }
        .sortedBy { it["name"]?.jsonPrimitive?.contentOrNull ?: "" }
}

fun extractJsonFromToolResult(result: JsonObject?): JsonObject? {
    if (result == null) return null
    val content = result["content"]?.takeIf { it is JsonArray }?.jsonArray ?: return null
    for (item in content) {
        if (item !is JsonObject) continue
        if (item["type"]?.jsonPrimitive?.contentOrNull != "text") continue
        val text = item["text"]?.jsonPrimitive?.contentOrNull ?: continue
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(text).takeIf { it is JsonObject }?.jsonObject
        } catch (e: Exception) {
            null
        }
    }
    return null
}
