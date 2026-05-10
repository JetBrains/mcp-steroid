/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfoRt
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ServerMetadataResponse(
    val pid: Long,
    val ide: IdeInfo,
    val plugin: PluginInfo,
    val mcpUrl: String,
    val paths: ServerPathInfo,
    val executable: ServerExecutableInfo
)

@Serializable
data class ServerPathInfo(
    val homePath: String,
    val configPath: String,
    val systemPath: String,
    val pluginsPath: String,
    val logPath: String,
    val binPath: String,
    val userHome: String,
    val javaHome: String
) {
    companion object
}

fun ServerPathInfo.Companion.current(): ServerPathInfo = ServerPathInfo(
    homePath = PathManager.getHomePath(),
    configPath = PathManager.getConfigPath(),
    systemPath = PathManager.getSystemPath(),
    pluginsPath = PathManager.getPluginsPath(),
    logPath = PathManager.getLogPath(),
    binPath = PathManager.getBinPath(),
    userHome = System.getProperty("user.home", ""),
    javaHome = System.getProperty("java.home", "")
)

@Serializable
data class ServerExecutableInfo(
    val scriptName: String,
    val executablePath: String?,
    val candidates: List<String>
) {
    companion object
}

fun ServerExecutableInfo.Companion.detect(scriptName: String, paths: ServerPathInfo): ServerExecutableInfo {
    val binPath = Path.of(paths.binPath)
    val homePath = Path.of(paths.homePath)
    val candidates = buildList {
        if (SystemInfoRt.isMac) {
            add(homePath.resolve("MacOS").resolve(scriptName).toString())
        } else if (SystemInfoRt.isWindows) {
            add(binPath.resolve("${scriptName}64.exe").toString())
            add(binPath.resolve("$scriptName.exe").toString())
        } else {
            add(binPath.resolve("${scriptName}.sh").toString())
            add(binPath.resolve(scriptName).toString())
        }
    }
    val executablePath = candidates.firstOrNull { candidate ->
        runCatching { Files.isRegularFile(Path.of(candidate)) }.getOrDefault(false)
    }
    return ServerExecutableInfo(
        scriptName = scriptName,
        executablePath = executablePath,
        candidates = candidates
    )
}
