/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import com.jonnyzzz.mcpSteroid.PluginInfo
import kotlinx.serialization.Serializable

fun PluginInfo.Companion.ofCurrentPlugin(): PluginInfo {
    val plugin = PluginDescriptorProvider.getInstance()
    return PluginInfo(
        id = plugin.pluginId,
        name = plugin.name,
        version = plugin.version
    )
}

@Serializable
data class ProductInfo(
    val pid: Long = ProcessHandle.current().pid(),
    val ide: IdeInfo = IdeInfo.ofApplication(),
    val plugin: PluginInfo = PluginInfo.ofCurrentPlugin()
)

@Serializable
data class ListProductsResponse(
    val products: List<ProductInfo>
)

