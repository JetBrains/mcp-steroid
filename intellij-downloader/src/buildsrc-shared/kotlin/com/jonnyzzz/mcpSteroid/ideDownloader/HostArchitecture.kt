/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

enum class HostArchitecture(
    val isArmArch: Boolean,
    val aliases: Set<String>,
) {
    ARM64(
        isArmArch = true,
        aliases = setOf("aarch64", "arm64"),
    ),
    X86_64(
        isArmArch = false,
        aliases = setOf("x86_64", "amd64"),
    );
}

fun resolveHostArchitecture(osArch: String = System.getProperty("os.arch")): HostArchitecture {
    val normalizedArch = osArch.trim().lowercase()
    return when {
        normalizedArch in HostArchitecture.ARM64.aliases -> HostArchitecture.ARM64
        normalizedArch in HostArchitecture.X86_64.aliases -> HostArchitecture.X86_64
        else -> throw IllegalArgumentException(
            "Unsupported host architecture '$osArch'. " +
                    "Supported values are: ${HostArchitecture.ARM64.aliases + HostArchitecture.X86_64.aliases}"
        )
    }
}
