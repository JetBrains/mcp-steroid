/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Baseline of an IntelliJ build number — the first integer, e.g. `261.24374.151` -> 261,
 * `IU-261.x` -> 261, `253.28294.334` -> 253. IntelliJ platform baselines are always 3-digit, so a
 * leading year-like number (Android Studio's marketing version `2025.3.4.7`) is rejected as "not a
 * platform baseline" (returns null) rather than mistaken for a very-new build. Returns null when no
 * platform baseline can be found.
 */
fun ideBuildBaseline(build: String): Int? =
    Regex("""\d+""").find(build)?.value?.toIntOrNull()?.takeIf { it in 1..999 }

/**
 * The IntelliJ build range the bundled MCP Steroid plugin declares it supports — exactly the
 * `since-build` / `until-build` from the plugin's `plugin.xml`. A managed backend whose build is
 * outside this range cannot load the plugin (so it never writes a marker and is never reachable),
 * which is why we refuse to download/start it and mark it in `devrig backend download`.
 */
data class PluginBuildRange(
    val sinceBaseline: Int,
    val untilBaseline: Int?,
) {
    /** True when [build]'s baseline is within `[sinceBaseline, untilBaseline]` (until is unbounded when null). */
    fun accepts(build: String): Boolean {
        val baseline = ideBuildBaseline(build) ?: return false
        if (baseline < sinceBaseline) return false
        if (untilBaseline != null && baseline > untilBaseline) return false
        return true
    }

    /** Human-readable constraint, e.g. "build >= 261" or "build 261..263". */
    fun describe(): String =
        if (untilBaseline == null) "build >= $sinceBaseline" else "build $sinceBaseline..$untilBaseline"
}

/**
 * Parses `since-build` / `until-build` out of a `plugin.xml` body. Returns null when there is no
 * `since-build` (we cannot reason about compatibility without a lower bound).
 */
fun parsePluginBuildRange(pluginXml: String): PluginBuildRange? {
    val since = Regex("""since-build="([^"]+)"""").find(pluginXml)?.groupValues?.get(1)
    val sinceBaseline = since?.let { ideBuildBaseline(it) } ?: return null
    val untilBaseline = Regex("""until-build="([^"]+)"""").find(pluginXml)?.groupValues?.get(1)?.let { ideBuildBaseline(it) }
    return PluginBuildRange(sinceBaseline, untilBaseline)
}

/**
 * Reads the plugin compatibility range from the bundled `ij-plugin.zip`. The plugin descriptor
 * lives in `META-INF/plugin.xml` inside the nested `lib/ij-plugin*.jar` jar, so this opens the zip,
 * finds that jar, and reads the descriptor from it.
 */
fun readBundledPluginBuildRange(pluginZip: Path): PluginBuildRange {
    require(Files.isRegularFile(pluginZip)) { "Bundled plugin zip is missing: $pluginZip" }
    ZipFile(pluginZip.toFile()).use { zip ->
        val jarEntry = zip.entries().asSequence().firstOrNull { entry ->
            !entry.isDirectory && entry.name.endsWith(".jar") && entry.name.contains("/lib/ij-plugin")
        } ?: error("No */lib/ij-plugin*.jar entry inside $pluginZip")
        zip.getInputStream(jarEntry).use { jarStream ->
            ZipInputStream(jarStream).use { jar ->
                var entry = jar.nextEntry
                while (entry != null) {
                    if (entry.name == "META-INF/plugin.xml") {
                        val xml = jar.readBytes().decodeToString()
                        return parsePluginBuildRange(xml)
                            ?: error("plugin.xml in ${jarEntry.name} has no since-build")
                    }
                    entry = jar.nextEntry
                }
            }
        }
    }
    error("META-INF/plugin.xml not found inside the ij-plugin jar in $pluginZip")
}

/**
 * The bundled plugin's build range, read once from `DevrigRoot.ijPluginZip()`. Null (with a stderr
 * warning) when the zip is absent or unparseable — callers then skip compatibility gating rather
 * than block everything.
 */
val bundledPluginBuildRange: PluginBuildRange? by lazy {
    try {
        readBundledPluginBuildRange(DevrigRoot.ijPluginZip())
    } catch (e: Exception) {
        System.err.println("WARN: could not read plugin compatibility range from bundled plugin: ${e.message}")
        null
    }
}
