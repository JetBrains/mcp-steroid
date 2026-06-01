/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import java.io.File

data class ContainerVolume(
    val host: File,
    val guest: String,
    val mode: String = "rw",
)


fun ContainerDriver.mapGuestPathToHostPath(path: String): File =
    mapGuestPathToHostPathOrNull(path) ?: error("Not found volume for guest path $path")

/**
 * Map a guest (container) path to its host path if it falls under a mounted [ContainerVolume],
 * or null when the path is container-local (no bind mount). Lets disk helpers prefer direct
 * host filesystem access over `docker cp` / `docker exec` when a mapping exists.
 */
fun ContainerDriver.mapGuestPathToHostPathOrNull(path: String): File? {
    // Container (guest) paths are '/'-separated; normalize any '\' so a Windows-built string
    // still matches the mount's guest prefix.
    val guestPath = path.replace('\\', '/').trimEnd('/')
    for (v in volumes) {
        val guestMount = v.guest.replace('\\', '/').trimEnd('/')
        if (guestPath == guestMount) {
            return v.host
        }

        if (guestPath.startsWith("$guestMount/")) {
            val prefix = guestPath.removePrefix("$guestMount/")
            // Resolve segment-by-segment so the resulting host File uses the OS separator
            // (e.g. '\' on Windows) instead of embedding the guest's '/'.
            return prefix.split('/').filter { it.isNotEmpty() }.fold(v.host) { acc, seg -> acc.resolve(seg) }
        }
    }
    return null
}

