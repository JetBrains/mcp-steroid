/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

enum class HostOs(val id: String) {
    LINUX("linux"),
    MAC("mac"),
    WINDOWS("windows"),
}

fun resolveHostOs(osName: String = System.getProperty("os.name")): HostOs {
    val normalized = osName.trim().lowercase()
    return when {
        normalized.startsWith("linux") -> HostOs.LINUX
        normalized.startsWith("mac") || normalized.startsWith("darwin") -> HostOs.MAC
        normalized.startsWith("windows") || normalized.startsWith("win") -> HostOs.WINDOWS
        else -> throw IllegalArgumentException(
            "Unsupported OS '$osName'. Supported: Linux, Mac OS X, Windows."
        )
    }
}
