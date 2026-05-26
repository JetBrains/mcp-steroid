/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NpxBridgeWindowsResponse(
    val windows: List<WindowInfo>,
    val backgroundTasks: List<ProgressTaskInfo>,
    val pid: Long,
    val mcpUrl: String,
    val instanceId: String,
    val seq: Long,
    val schemaVersion: String,
    val updatedAt: String
)

@Serializable
data class NpxBridgeToolCallRequest(
    val name: String,
    val arguments: JsonObject? = null
)
