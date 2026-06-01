/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val BACKEND_TYPE_INTELLIJ = "intellij"

fun markerBackendDisplayName(ide: DiscoveredIde): String =
    ideNameWithVersion(ide.marker.ide.name, ide.marker.ide.version)

fun markerBackendLocatorLabel(ide: DiscoveredIde): String = buildString {
    ide.marker.ide.build.trim().takeIf { it.isNotEmpty() }?.let {
        append("build ").append(it).append(", ")
    }
    append("pid ").append(ide.pid)
}

fun markerBackendIdentityJson(ide: DiscoveredIde): JsonObject = buildJsonObject {
    put("name", ide.marker.ide.name)
    put("version", ide.marker.ide.version)
    put("build", ide.marker.ide.build)
    put("buildNumber", ide.marker.ide.build)
    put("pid", ide.pid)
    put("rpcBaseUrl", ide.rpcBaseUrl)
}

private fun ideNameWithVersion(name: String, version: String): String {
    val trimmedName = name.trim()
    val trimmedVersion = version.trim()
    if (trimmedVersion.isEmpty()) return trimmedName
    if (trimmedName == trimmedVersion || trimmedName.endsWith(" $trimmedVersion")) return trimmedName
    return "$trimmedName $trimmedVersion".trim()
}

fun portBackendDisplayName(ide: DiscoveredIdeByPort): String =
    ide.productFullName ?: ide.productName ?: "(unknown JetBrains IDE)"

fun portBackendLocatorLabel(ide: DiscoveredIdeByPort): String = buildString {
    ide.buildNumber?.let { append("build ").append(it).append(", ") }
    append("port ").append(ide.port)
}

fun backendDisplayName(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> markerBackendDisplayName(row.ide)
    is BackendRow.FromPort -> portBackendDisplayName(row.ide)
    is BackendRow.FromManaged -> row.displayName
}

fun backendLocatorLabel(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> markerBackendLocatorLabel(row.ide) + if (row.managed) ", managed" else ""
    is BackendRow.FromPort -> portBackendLocatorLabel(row.ide) + if (row.managed) ", managed" else ""
    is BackendRow.FromManaged -> row.locatorLabel
}

fun backendStableId(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> "pid-${row.ide.pid}"
    is BackendRow.FromPort -> "port-${row.ide.port}"
    is BackendRow.FromManaged -> row.info.id
}

fun backendPluginStatusText(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> {
        val plugin = row.ide.marker.plugin
        "${plugin.name.ifBlank { "MCP Steroid" }}: ${plugin.version.ifBlank { "unknown" }}"
    }
    is BackendRow.FromPort,
    is BackendRow.FromManaged -> "MCP Steroid: not installed"
}

fun backendEntryJson(id: String, row: BackendRow): JsonObject = buildJsonObject {
    put("id", id)
    put("type", BACKEND_TYPE_INTELLIJ)
    put("source", backendSource(row))
    put("displayName", backendDisplayName(row))
    put("locator", backendLocatorLabel(row))
    put("managed", row.managed)
    put("plugin", backendPluginJson(row))
    put("actions", backendActionsJson(row))
    when (row) {
        is BackendRow.FromMarker -> {
            put("pluginInstalled", true)
            val reachable = row.projects != null
            put("reachable", reachable)
            putJsonFields(markerBackendIdentityJson(row.ide))
            if (!reachable) {
                put("error", row.errorMessage ?: "unreachable")
            }
        }
        is BackendRow.FromPort -> {
            put("pluginInstalled", false)
            put("reachable", true)
            putJsonFields(portBackendIdentityJson(row.ide))
        }
        is BackendRow.FromManaged -> {
            val info = row.info
            put("pluginInstalled", false)
            put("reachable", info.state == ManagedBackendState.RUNNING)
            put("managedId", info.id)
            put("productKey", info.productKey)
            put("productCode", info.productCode)
            put("version", info.version)
            info.buildNumber?.let { put("buildNumber", it) }
            put("state", info.state.name.lowercase())
            put("installPath", info.installPath.toString())
            put("cachePath", info.cachePath.toString())
            info.runningPid?.let { put("runningPid", it) }
        }
    }
}

private fun backendPluginJson(row: BackendRow): JsonObject = buildJsonObject {
    when (row) {
        is BackendRow.FromMarker -> {
            val plugin = row.ide.marker.plugin
            put("installed", true)
            put("id", plugin.id)
            put("name", plugin.name)
            put("version", plugin.version)
        }
        is BackendRow.FromPort,
        is BackendRow.FromManaged -> {
            put("installed", false)
        }
    }
}

private fun backendActionsJson(row: BackendRow) = buildJsonArray {
    if (row is BackendRow.FromPort) {
        add(provisionActionJson(provisionTargetId(row.ide.port)))
    }
}

private fun backendSource(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> "marker"
    is BackendRow.FromPort -> "port"
    is BackendRow.FromManaged -> "managed"
}

fun portBackendIdentityJson(ide: DiscoveredIdeByPort): JsonObject = buildJsonObject {
    put("port", ide.port)
    put("baseUrl", ide.baseUrl)
    ide.productName?.let { put("productName", it) }
    ide.productFullName?.let { put("productFullName", it) }
    ide.edition?.let { put("edition", it) }
    ide.baselineVersion?.let { put("baselineVersion", it) }
    ide.buildNumber?.let { put("buildNumber", it) }
}

fun JsonObjectBuilder.putJsonFields(fields: JsonObject) {
    for ((key, value) in fields) {
        put(key, value)
    }
}
