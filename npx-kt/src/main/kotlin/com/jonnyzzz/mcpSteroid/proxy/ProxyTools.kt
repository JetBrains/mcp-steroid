/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun proxyToolsList(): List<JsonObject> {
    val emptySchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {  })
        put("required", buildJsonArray {  })
    }
    return listOf(
        buildJsonObject {
            put("name", AGGREGATE_TOOL_PROJECTS)
            put("description", "Aggregate projects across all running IDE servers.")
            put("inputSchema", emptySchema)
        },
        buildJsonObject {
            put("name", AGGREGATE_TOOL_WINDOWS)
            put("description", "Aggregate windows across all running IDE servers.")
            put("inputSchema", emptySchema)
        }
    )
}

fun toolResult(payload: JsonObject, isError: Boolean = false): JsonObject = buildJsonObject {
    put("content", JsonArray(listOf(buildJsonObject {
        put("type", "text")
        put("text", kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), payload))
    })))
    put("isError", isError)
}

private data class TargetList(val targets: List<String>, val error: String?)

private fun getTargetsByFreshness(registry: ServerRegistry, args: JsonObject): TargetList {
    val targetId = args["server_id"]?.jsonPrimitive?.contentOrNull
    if (targetId != null) {
        val resolved = registry.resolveServerIdHint(targetId)
            ?: return TargetList(emptyList(), "Unknown server_id: $targetId")
        return TargetList(listOf(resolved), null)
    }
    return TargetList(
        targets = registry.listOnlineServersByFreshness().map { it.serverId },
        error = null
    )
}

suspend fun handleAggregateProjects(registry: ServerRegistry, args: JsonObject): JsonObject {
    val (targets, error) = getTargetsByFreshness(registry, args)
    if (error != null) return makeToolError(error)

    val errors = mutableListOf<JsonObject>()
    val projects = mutableListOf<JsonObject>()

    coroutineScope {
        targets.map { serverId ->
            async {
                try {
                    val result = registry.callTool(serverId, AGGREGATE_TOOL_PROJECTS, buildJsonObject {  })
                    if (result["isError"]?.jsonPrimitive?.content?.lowercase() == "true") {
                        synchronized(errors) { errors += buildJsonObject { put("serverId", serverId); put("message", "Upstream error") } }
                        return@async
                    }
                    val payload = extractJsonFromToolResult(result)
                    val rawProjects = (payload?.get("projects") as? JsonArray)?.mapNotNull { it as? JsonObject }
                    if (payload == null || rawProjects == null) {
                        synchronized(errors) { errors += buildJsonObject { put("serverId", serverId); put("message", "Invalid response") } }
                        return@async
                    }

                    registry.updateServerProjects(serverId, rawProjects, metadataFromProjectsPayload(payload))
                    val summary = registry.getServerSummary(serverId)

                    val enriched = rawProjects.map { project ->
                        val projectPath = project["path"]?.jsonPrimitive?.contentOrNull ?: ""
                        buildJsonObject {
                            put("projectId", "$serverId::${projectPath.ifEmpty { project["name"]?.jsonPrimitive?.contentOrNull ?: "" }}")
                            put("serverId", serverId)
                            put("serverLabel", summary?.serverLabel)
                            put("serverUrl", summary?.serverUrl)
                            put("serverPort", summary?.serverPort)
                        }
                    }
                    synchronized(projects) { projects += enriched }
                } catch (e: Exception) {
                    synchronized(errors) { errors += buildJsonObject { put("serverId", serverId); put("message", e.message ?: "Error") } }
                }
            }
        }.awaitAll()
    }

    registry.rebuildProjectIndexFromCaches()
    return toolResult(buildJsonObject {
        put("projects", JsonArray(projects))
        put("projectMappings", JsonArray(registry.projectMappings.map { m ->
            buildJsonObject {
                put("projectName", m.projectName)
                put("projectPath", m.projectPath)
                put("serverId", m.serverId)
                put("serverLabel", m.serverLabel)
                put("serverUrl", m.serverUrl)
                m.serverPort?.let { put("serverPort", it) }
            }
        }))
        put("errors", JsonArray(errors))
    })
}

suspend fun handleAggregateWindows(registry: ServerRegistry, args: JsonObject): JsonObject {
    val (targets, error) = getTargetsByFreshness(registry, args)
    if (error != null) return makeToolError(error)

    val errors = mutableListOf<JsonObject>()
    val windows = mutableListOf<JsonObject>()
    val backgroundTasks = mutableListOf<JsonObject>()

    coroutineScope {
        targets.map { serverId ->
            async {
                try {
                    val result = registry.callTool(serverId, AGGREGATE_TOOL_WINDOWS, buildJsonObject {  })
                    if (result["isError"]?.jsonPrimitive?.content?.lowercase() == "true") {
                        synchronized(errors) { errors += buildJsonObject { put("serverId", serverId); put("message", "Upstream error") } }
                        return@async
                    }
                    val payload = extractJsonFromToolResult(result)
                    val rawWindows = (payload?.get("windows") as? JsonArray)?.mapNotNull { it as? JsonObject }
                    if (payload == null || rawWindows == null) {
                        synchronized(errors) { errors += buildJsonObject { put("serverId", serverId); put("message", "Invalid response") } }
                        return@async
                    }

                    registry.updateServerWindows(serverId, rawWindows, metadataFromWindowsPayload(payload))
                    val summary = registry.getServerSummary(serverId)

                    val enriched = rawWindows.map { window ->
                        val rawWindowId = window["windowId"]?.jsonPrimitive?.contentOrNull ?: ""
                        buildJsonObject {
                            put("serverWindowId", rawWindowId)
                            put("windowId", "$serverId::$rawWindowId")
                            put("serverId", serverId)
                            put("serverLabel", summary?.serverLabel)
                            put("serverUrl", summary?.serverUrl)
                            put("serverPort", summary?.serverPort)
                        }
                    }
                    synchronized(windows) { windows += enriched }

                    val rawTasks = (payload["backgroundTasks"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    val enrichedTasks = rawTasks.map { task ->
                        buildJsonObject {
                            put("serverId", serverId)
                            put("serverLabel", summary?.serverLabel)
                            put("serverUrl", summary?.serverUrl)
                            put("serverPort", summary?.serverPort)
                        }
                    }
                    synchronized(backgroundTasks) { backgroundTasks += enrichedTasks }
                } catch (e: Exception) {
                    synchronized(errors) { errors += buildJsonObject { put("serverId", serverId); put("message", e.message ?: "Error") } }
                }
            }
        }.awaitAll()
    }

    registry.rebuildWindowIndexFromCaches()

    val products = targets.mapNotNull { serverId ->
        val summary = registry.getServerSummary(serverId) ?: return@mapNotNull null
        buildJsonObject {
            put("serverId", serverId)
            put("serverLabel", summary.serverLabel)
            put("serverUrl", summary.serverUrl)
            summary.serverPort?.let { put("serverPort", it) }
            summary.ide?.let { ide ->
                put("ide", buildJsonObject {
                    ide.name?.let { put("name", it) }
                    ide.version?.let { put("version", it) }
                    ide.build?.let { put("build", it) }
                })
            }
            summary.plugin?.let { plugin ->
                put("plugin", buildJsonObject {
                    plugin.id?.let { put("id", it) }
                    plugin.name?.let { put("name", it) }
                    plugin.version?.let { put("version", it) }
                })
            }
        }
    }

    return toolResult(buildJsonObject {
        put("windows", JsonArray(windows))
        put("backgroundTasks", JsonArray(backgroundTasks))
        put("products", JsonArray(products))
        put("errors", JsonArray(errors))
    })
}

suspend fun handleListServers(registry: ServerRegistry): JsonObject {
    val servers = registry.listOnlineServersByFreshness().map { server ->
        buildJsonObject {
            put("serverId", server.serverId)
            put("label", server.label)
            put("url", server.url)
            server.port?.let { put("port", it) }
            put("status", server.status)
            server.lastSeenAt?.let { put("lastSeenAt", it) }
            put("toolCount", server.tools?.size ?: 0)
            put("resourceCount", server.resources?.size ?: 0)
            server.metadata?.ide?.let { ide ->
                put("ide", buildJsonObject {
                    ide.name?.let { put("name", it) }
                    ide.version?.let { put("version", it) }
                    ide.build?.let { put("build", it) }
                })
            }
            server.metadata?.plugin?.let { plugin ->
                put("plugin", buildJsonObject {
                    plugin.id?.let { put("id", it) }
                    plugin.name?.let { put("name", it) }
                    plugin.version?.let { put("version", it) }
                })
            }
        }
    }
    return toolResult(buildJsonObject { put("servers", JsonArray(servers)) })
}
