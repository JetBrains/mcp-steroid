/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ServerEntry(
    val serverId: String,
    val pid: Long,
    var url: String,
    var baseUrl: String,
    var bridgeBaseUrl: String?,
    var port: Int?,
    var label: String,
    var markerPath: String,
    var status: String = "offline",
    var lastSeenAt: String? = null,
    var discovered: Boolean = true,

    var metadata: ServerMetadata? = null,
    var metadataFetchedAt: Long = 0,

    var tools: List<JsonObject>? = null,
    var toolsFetchedAt: Long = 0,
    var resources: List<JsonObject>? = null,
    var resourcesFetchedAt: Long = 0,
    var projects: List<JsonObject>? = null,
    var projectsFetchedAt: Long = 0,
    var windows: List<JsonObject>? = null,
    var windowsFetchedAt: Long = 0,
    var products: List<JsonObject>? = null,
    var productsFetchedAt: Long = 0,

    var client: UpstreamClient? = null
)

data class ServerSummary(
    val serverId: String,
    val serverLabel: String,
    val serverUrl: String,
    val serverPort: Int?,
    val ide: IdeMetadata?,
    val plugin: PluginMetadata?
)

data class ProjectMapping(
    val projectName: String,
    val projectPath: String,
    val serverId: String,
    val serverLabel: String,
    val serverUrl: String,
    val serverPort: Int?,
    val ide: IdeMetadata?,
    val plugin: PluginMetadata?
)

sealed class RoutingResult {
    data class Resolved(val serverId: String, val toolName: String) : RoutingResult()
    data class Error(val message: String) : RoutingResult()
}

fun makeToolError(message: String): JsonObject = buildJsonObject {
    put("content", JsonArray(listOf(buildJsonObject {
        put("type", "text")
        put("text", message)
    })))
    put("isError", true)
}

class ServerRegistry(val config: ProxyConfig, private val traffic: TrafficLogger) {
    val servers: MutableMap<String, ServerEntry> = mutableMapOf()
    private var lastScanAt: Long = 0
    private var refreshing = false

    private var projectIndexByName: MutableMap<String, MutableList<String>> = mutableMapOf()
    private var projectIndexByKey: MutableMap<String, MutableList<String>> = mutableMapOf()
    var projectMappings: List<ProjectMapping> = emptyList()
    private var windowIndex: MutableMap<String, String> = mutableMapOf()
    private var executionIndex: MutableMap<String, String> = mutableMapOf()
    var resourceIndex: MutableMap<String, MutableList<String>> = mutableMapOf()

    fun listOnlineServers(): List<ServerEntry> =
        servers.values.filter { it.status == "online" }

    private fun compareServersByFreshness(left: ServerEntry, right: ServerEntry): Int {
        val lMeta = left.metadata
        val rMeta = right.metadata

        val pluginCmp = compareVersionStringsDescending(lMeta?.plugin?.version, rMeta?.plugin?.version)
        if (pluginCmp != 0) return pluginCmp

        val ideCmp = compareVersionStringsDescending(lMeta?.ide?.version, rMeta?.ide?.version)
        if (ideCmp != 0) return ideCmp

        val buildCmp = compareVersionStringsDescending(lMeta?.ide?.build, rMeta?.ide?.build)
        if (buildCmp != 0) return buildCmp

        val seenCmp = compareIsoTimesDescending(left.lastSeenAt, right.lastSeenAt)
        if (seenCmp != 0) return seenCmp

        if (left.pid > right.pid) return -1
        if (left.pid < right.pid) return 1
        return left.serverId.compareTo(right.serverId)
    }

    fun listOnlineServersByFreshness(): List<ServerEntry> =
        listOnlineServers().sortedWith { a, b -> compareServersByFreshness(a, b) }

    fun getServer(serverId: String): ServerEntry? = servers[serverId]

    fun resolveServerIdHint(serverHint: Any?): String? {
        if (serverHint == null) return null
        val raw = serverHint.toString().trim()
        if (raw.isEmpty()) return null
        if (servers.containsKey(raw)) return raw

        val normalized = raw.lowercase()
        val online = listOnlineServersByFreshness()

        val byLabel = online.filter { it.label.trim().lowercase() == normalized }
        if (byLabel.size == 1) return byLabel[0].serverId

        if ((normalized == "intellij" || normalized == "default_api" || normalized == "mcp-steroid") && online.size == 1) {
            return online[0].serverId
        }

        return null
    }

    fun getServerSummary(serverId: String): ServerSummary? {
        val server = servers[serverId] ?: return null
        return ServerSummary(
            serverId = server.serverId,
            serverLabel = server.label,
            serverUrl = server.url,
            serverPort = server.port,
            ide = server.metadata?.ide,
            plugin = server.metadata?.plugin
        )
    }

    private fun upsert(entry: MarkerEntry): ServerEntry {
        val serverId = buildServerId(entry.pid, entry.url, servers)
        var server = servers[serverId]

        if (server == null) {
            val base = baseUrlFromMcpUrl(entry.url)
            server = ServerEntry(
                serverId = serverId,
                pid = entry.pid,
                url = entry.url,
                baseUrl = base,
                bridgeBaseUrl = "$base/npx/v1",
                port = portFromUrl(entry.url),
                label = entry.label,
                markerPath = entry.markerPath
            )
            server.client = UpstreamClient(server, config, traffic)
            servers[serverId] = server
        } else {
            val base = baseUrlFromMcpUrl(entry.url)
            server.url = entry.url
            server.baseUrl = base
            server.bridgeBaseUrl = "$base/npx/v1"
            server.port = portFromUrl(entry.url)
            server.label = entry.label
            server.markerPath = entry.markerPath
            server.discovered = true
        }

        return server
    }

    suspend fun refreshDiscovery() {
        if (refreshing) return
        refreshing = true
        try {
            val homeDir = java.io.File(config.homeDir ?: System.getProperty("user.home"))
            val discovered = scanMarkers(homeDir, config.allowHosts)
            val seen = mutableSetOf<String>()

            for (entry in discovered) {
                val server = upsert(entry)
                seen.add(server.serverId)
            }

            for (server in servers.values) {
                if (!seen.contains(server.serverId)) {
                    server.discovered = false
                    server.status = "offline"
                }
            }

            for (server in servers.values) {
                if (!server.discovered) continue
                checkHealth(server)
                if (server.status == "online") {
                    refreshServerSnapshot(server)
                }
            }

            rebuildProjectIndexFromCaches()
            rebuildWindowIndexFromCaches()
            lastScanAt = System.currentTimeMillis()
        } finally {
            refreshing = false
        }
    }

    suspend fun ensureFresh() {
        if (System.currentTimeMillis() - lastScanAt > config.scanIntervalMs) {
            refreshDiscovery()
        }
    }

    private suspend fun checkHealth(server: ServerEntry) {
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = config.upstreamTimeoutMs
                connectTimeoutMillis = 10_000
            }
        }
        var ok = false
        try {
            val response = httpClient.get(server.url) {
                accept(ContentType.Application.Json)
            }
            if (response.status.isSuccess()) {
                val body = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ok = body?.get("status")?.jsonPrimitive?.contentOrNull == "available"
            }
        } catch (e: Exception) {
            ok = false
        } finally {
            httpClient.close()
        }
        server.status = if (ok) "online" else "offline"
        if (ok) server.lastSeenAt = nowIso()
    }

    private suspend fun refreshServerSnapshot(server: ServerEntry) {
        val ttlMs = if (config.cache.enabled) config.cache.ttlSeconds * 1000L else 0L
        val now = System.currentTimeMillis()

        if (server.metadata == null || now - server.metadataFetchedAt > ttlMs) {
            val bridgeMeta = fetchBridgeServerMetadata(server.serverId)
            if (bridgeMeta != null) {
                applyMetadata(server.serverId, normalizeServerMetadataPayload(bridgeMeta))
                server.metadataFetchedAt = now
            }
        }

        if (server.tools == null || now - server.toolsFetchedAt > ttlMs) {
            try {
                val result = server.client!!.sendRequest("tools/list", buildJsonObject {  })
                server.tools = (result["tools"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
                server.toolsFetchedAt = now
            } catch (e: Exception) {
                if (server.tools == null) server.tools = emptyList()
            }
        }

        if (server.resources == null || now - server.resourcesFetchedAt > ttlMs) {
            try {
                val result = server.client!!.sendRequest("resources/list", buildJsonObject {  })
                server.resources = (result["resources"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                server.resourcesFetchedAt = now
            } catch (e: Exception) {
                if (server.resources == null) server.resources = emptyList()
            }
        }
    }

    suspend fun fetchBridgeServerMetadata(serverId: String): JsonObject? =
        servers[serverId]?.client?.fetchJson("/server-metadata")

    suspend fun fetchBridgeProducts(serverId: String): JsonObject? =
        servers[serverId]?.client?.fetchJson("/products")

    fun applyMetadata(serverId: String, patch: ServerMetadata?) {
        if (patch == null) return
        val server = servers[serverId] ?: return
        server.metadata = mergeServerMetadata(server.metadata, patch)
    }

    fun updateServerProjects(serverId: String, projects: List<JsonObject>, metadataPatch: ServerMetadata? = null) {
        val server = servers[serverId] ?: return
        server.projects = projects
        server.projectsFetchedAt = System.currentTimeMillis()
        applyMetadata(serverId, metadataPatch)
    }

    fun updateServerWindows(serverId: String, windows: List<JsonObject>, metadataPatch: ServerMetadata? = null) {
        val server = servers[serverId] ?: return
        server.windows = windows
        server.windowsFetchedAt = System.currentTimeMillis()
        applyMetadata(serverId, metadataPatch)
    }

    fun updateServerProducts(serverId: String, products: List<JsonObject>, metadataPatch: ServerMetadata? = null) {
        val server = servers[serverId] ?: return
        server.products = products
        server.productsFetchedAt = System.currentTimeMillis()
        applyMetadata(serverId, metadataPatch)
    }

    fun rebuildProjectIndexFromCaches() {
        val byName = mutableMapOf<String, MutableList<String>>()
        val byKey = mutableMapOf<String, MutableList<String>>()
        val mappings = mutableListOf<ProjectMapping>()

        for (server in listOnlineServersByFreshness()) {
            for (project in server.projects ?: emptyList()) {
                val name = project["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val path = project["path"]?.jsonPrimitive?.contentOrNull ?: ""

                mappings += ProjectMapping(
                    projectName = name,
                    projectPath = path,
                    serverId = server.serverId,
                    serverLabel = server.label,
                    serverUrl = server.url,
                    serverPort = server.port,
                    ide = server.metadata?.ide,
                    plugin = server.metadata?.plugin
                )

                byName.getOrPut(name) { mutableListOf() }.apply {
                    if (!contains(server.serverId)) add(server.serverId)
                }
                val key = projectKey(name, path)
                byKey.getOrPut(key) { mutableListOf() }.apply {
                    if (!contains(server.serverId)) add(server.serverId)
                }
            }
        }

        for (ids in byName.values) ids.sortWith { a, b ->
            compareServersByFreshness(servers[a] ?: return@sortWith 0, servers[b] ?: return@sortWith 0)
        }
        for (ids in byKey.values) ids.sortWith { a, b ->
            compareServersByFreshness(servers[a] ?: return@sortWith 0, servers[b] ?: return@sortWith 0)
        }

        projectIndexByName = byName
        projectIndexByKey = byKey
        projectMappings = mappings
    }

    fun rebuildWindowIndexFromCaches() {
        val idx = mutableMapOf<String, String>()
        val rawOwners = mutableMapOf<String, MutableSet<String>>()

        for (server in listOnlineServersByFreshness()) {
            for (window in server.windows ?: emptyList()) {
                val rawId = window["windowId"]?.jsonPrimitive?.contentOrNull ?: continue
                val namespaced = "${server.serverId}::$rawId"
                idx[namespaced] = server.serverId
                rawOwners.getOrPut(rawId) { mutableSetOf() }.add(server.serverId)
            }
        }

        for ((rawWindowId, owners) in rawOwners) {
            if (owners.size == 1) idx[rawWindowId] = owners.first()
        }

        windowIndex = idx
    }

    fun buildToolGroups(): Map<String, List<JsonObject>> {
        val groups = mutableMapOf<String, MutableList<JsonObject>>()

        for (tool in proxyToolsList()) {
            val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: continue
            groups.getOrPut(name) { mutableListOf() } += tool
        }

        for (server in listOnlineServers()) {
            for (tool in server.tools ?: emptyList()) {
                val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: continue
                groups.getOrPut(name) { mutableListOf() } += tool
            }
        }

        return groups
    }

    fun buildResourceIndex(): List<JsonObject> {
        val idx = mutableMapOf<String, MutableList<String>>()
        val resources = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()

        for (server in listOnlineServersByFreshness()) {
            for (resource in server.resources ?: emptyList()) {
                val uri = resource["uri"]?.jsonPrimitive?.contentOrNull ?: continue
                if (!seen.contains(uri)) {
                    seen.add(uri)
                    resources += resource
                    idx.getOrPut(uri) { mutableListOf() }.add(server.serverId)
                } else {
                    val alias = buildAliasUri(server.serverId, uri)
                    resources += JsonObject(resource.toMutableMap().apply { put("uri", JsonPrimitive(alias)) })
                    idx.getOrPut(uri) { mutableListOf() }.add(server.serverId)
                }
            }
        }

        resourceIndex = idx
        return resources
    }

    fun resolveServerForToolCall(name: String, args: JsonObject): RoutingResult {
        val serverIdsSet = servers.keys.toSet()

        val serverIdHint = args["server_id"]?.jsonPrimitive?.contentOrNull
        if (serverIdHint != null) {
            val resolved = resolveServerIdHint(serverIdHint)
                ?: return RoutingResult.Error("Unknown server_id: $serverIdHint")
            return RoutingResult.Resolved(serverId = resolved, toolName = name)
        }

        val namespaced = parseNamespacedTool(name, serverIdsSet)
        if (namespaced != null) return RoutingResult.Resolved(serverId = namespaced.serverId, toolName = namespaced.toolName)

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
        if (projectName != null) {
            val projectPath = args["project_path"]?.jsonPrimitive?.contentOrNull
            if (projectPath != null) {
                val byPath = projectIndexByKey[projectKey(projectName, projectPath)]
                if (!byPath.isNullOrEmpty()) return RoutingResult.Resolved(serverId = byPath[0], toolName = name)
            }
            val byName = projectIndexByName[projectName]
            if (!byName.isNullOrEmpty()) return RoutingResult.Resolved(serverId = byName[0], toolName = name)
        }

        val windowId = args["window_id"]?.jsonPrimitive?.contentOrNull
        if (windowId != null) {
            val match = windowIndex[windowId]
            if (match != null) return RoutingResult.Resolved(serverId = match, toolName = name)
        }

        val execId = args["execution_id"]?.jsonPrimitive?.contentOrNull
            ?: args["screenshot_execution_id"]?.jsonPrimitive?.contentOrNull
        if (execId != null) {
            val match = executionIndex[execId]
            if (match != null) return RoutingResult.Resolved(serverId = match, toolName = name)
        }

        val online = listOnlineServersByFreshness()
        if (online.size == 1) return RoutingResult.Resolved(serverId = online[0].serverId, toolName = name)

        val defaultId = config.defaultServerId
        if (defaultId != null) return RoutingResult.Resolved(serverId = defaultId, toolName = name)

        return RoutingResult.Error("Unable to route tool call; specify server_id or provide project_name/project_path")
    }

    suspend fun callTool(
        serverId: String,
        toolName: String,
        args: JsonObject,
        onEvent: (suspend (JsonObject) -> Unit)? = null
    ): JsonObject {
        val resolvedId = resolveServerIdHint(serverId) ?: serverId
        val server = servers[resolvedId]
            ?: return makeToolError("Unknown server_id: $serverId")
        if (server.status != "online") return makeToolError("Server $serverId is offline")

        val cleanArgs = JsonObject(args.filterKeys { it != "server_id" })
        val result = server.client!!.callTool(toolName, cleanArgs, onEvent)
        captureExecutionIds(resolvedId, result)
        return result
    }

    suspend fun callRpc(serverId: String, method: String, params: JsonObject): JsonObject {
        val resolvedId = resolveServerIdHint(serverId) ?: serverId
        val server = servers[resolvedId] ?: throw Exception("Unknown server_id: $serverId")
        if (server.status != "online") throw Exception("Server $serverId is offline")
        return server.client!!.sendRequest(method, params)
    }

    private fun captureExecutionIds(serverId: String, result: JsonObject) {
        val content = result["content"] as? JsonArray ?: return
        for (item in content) {
            val obj = item as? JsonObject ?: continue
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") continue
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: continue
            val match = Regex("execution_id:\\s*([\\w-]+)").find(text) ?: continue
            executionIndex[match.groupValues[1]] = serverId
        }
    }
}
