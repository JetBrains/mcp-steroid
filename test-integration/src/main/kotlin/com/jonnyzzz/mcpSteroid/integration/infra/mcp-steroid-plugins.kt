package com.jonnyzzz.mcpSteroid.integration.infra

/**
 * Detect required IDE plugins from project dependencies and install any that are missing.
 *
 * Scans `pom.xml` / `build.gradle` for known dependency keywords and maps them to
 * JetBrains Marketplace plugin IDs. Missing plugins are installed via
 * PluginsAdvertiser.installAndEnable, which downloads and dynamically loads them without
 * requiring an IDE restart (when the plugin supports dynamic loading).
 *
 * Call this after initial indexing and before JDK/Maven setup so that Maven re-sync can
 * already benefit from freshly installed framework support plugins.
 */
fun McpSteroidDriver.mcpInstallRequiredPlugins() {
    val code = """
import com.intellij.openapi.extensions.PluginId
import com.intellij.ide.plugins.PluginManagerCore
import java.io.File

// Dependency keyword → Marketplace plugin ID
val detectionRules = mapOf(
    "spring-kafka"   to "com.intellij.bigdatatools.kafka",
    "kafka-clients"  to "com.intellij.bigdatatools.kafka",
    "kafka-streams"  to "com.intellij.bigdatatools.kafka",
)

val basePath = project.basePath ?: ""
val buildContent = sequenceOf("pom.xml", "build.gradle", "build.gradle.kts")
    .map { File(basePath, it) }
    .firstOrNull { it.exists() }
    ?.readText() ?: ""

val toInstall = detectionRules
    .filter { (keyword, _) -> buildContent.contains(keyword, ignoreCase = true) }
    .values.toSet()
    .filter { PluginManagerCore.getPlugin(PluginId.getId(it)) == null }

if (toInstall.isEmpty()) {
    println("[PLUGIN-INSTALL] All required plugins already installed (or no matching dependencies)")
} else {
    println("[PLUGIN-INSTALL] Installing plugins: ${'$'}toInstall")
    // Use reflection to avoid compile error on IDE builds where PluginsAdvertiser was removed/moved.
    // In IU-253+ the class may not exist; we skip dynamic install gracefully in that case.
    val advertiserClass = try {
        Class.forName("com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginsAdvertiser")
    } catch (e: ClassNotFoundException) {
        null
    }
    if (advertiserClass == null) {
        println("[PLUGIN-INSTALL] PluginsAdvertiser not available in this IDE build — skipping dynamic install")
    } else {
        val installMethod = advertiserClass.declaredMethods
            .firstOrNull { it.name == "installAndEnable" }
        if (installMethod == null) {
            println("[PLUGIN-INSTALL] installAndEnable method not found — skipping dynamic install")
        } else {
            installMethod.invoke(
                null, project,
                toInstall.map { PluginId.getId(it) }.toSet(),
                Runnable { println("[PLUGIN-INSTALL] installAndEnable callback fired") },
            )
            // Plugin installation triggers re-indexing (dumb mode). Wait for smart mode —
            // this is the canonical way to wait for all IDE background work to complete.
            println("[PLUGIN-INSTALL] Waiting for smart mode after plugin installation...")
            waitForSmartMode()
            println("[PLUGIN-INSTALL] Smart mode reached — plugins ready: ${'$'}toInstall")
        }
    }
}
"done"
""".trimIndent()

    try {
        mcpExecuteCode(
            code = code,
            reason = "Install required IDE plugins for project dependencies",
            timeout = 200,
        )
    } catch (e: Exception) {
        println("[PLUGIN-INSTALL] Warning: plugin installation failed: ${e.message}")
    }
}

