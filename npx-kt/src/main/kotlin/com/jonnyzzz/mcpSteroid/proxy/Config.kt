/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class CacheConfig(
    val enabled: Boolean = false,
    val dir: String = "~/.mcp-steroid/proxy",
    val ttlSeconds: Int = 5
)

data class TrafficLogConfig(
    val enabled: Boolean = false,
    val redactFields: List<String> = listOf("code")
)

data class UpdatesConfig(
    val enabled: Boolean = true,
    val initialDelayMs: Long = 30_000,
    val intervalMs: Long = 15 * 60 * 1000L,
    val requestTimeoutMs: Long = 10_000
)

data class BeaconConfig(
    val enabled: Boolean = true,
    val host: String = "https://us.i.posthog.com",
    val apiKey: String = "phc_IPtbjwwy9YIGg0YNHNxYBePijvTvHEcKAjohah6obYW",
    val timeoutMs: Long = 3_000,
    val heartbeatIntervalMs: Long = 30 * 60 * 1000L,
    val distinctIdFile: String = "~/.mcp-steroid/proxy-beacon-id"
)

data class ProxyConfig(
    val homeDir: String? = null,
    val scanIntervalMs: Long = 2_000,
    val allowHosts: List<String> = listOf("127.0.0.1", "localhost"),
    val defaultServerId: String? = null,
    val upstreamTimeoutMs: Long = 120_000,
    val cache: CacheConfig = CacheConfig(),
    val trafficLog: TrafficLogConfig = TrafficLogConfig(),
    val updates: UpdatesConfig = UpdatesConfig(),
    val beacon: BeaconConfig = BeaconConfig(),
    var version: String? = null
)

data class CliArgs(
    val configPath: String? = null,
    val scanIntervalMs: Long? = null,
    val logTraffic: Boolean = false,
    val help: Boolean = false,
    val mode: String = "stdio",  // "stdio" | "cli"
    val cliMethod: String? = null,
    val cliParamsJson: String? = null,
    val cliToolName: String? = null,
    val cliArgumentsJson: String? = null,
    val cliUri: String? = null
)

fun parseArgs(argv: List<String>): CliArgs {
    var configPath: String? = null
    var scanIntervalMs: Long? = null
    var logTraffic = false
    var help = false
    var mode = "stdio"
    var cliMethod: String? = null
    var cliParamsJson: String? = null
    var cliToolName: String? = null
    var cliArgumentsJson: String? = null
    var cliUri: String? = null

    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--config" -> { configPath = argv.getOrNull(++i); i++ }
            "--scan-interval" -> { scanIntervalMs = argv.getOrNull(++i)?.toLongOrNull(); i++ }
            "--log-traffic" -> { logTraffic = true; i++ }
            "--cli" -> { mode = "cli"; i++ }
            "--cli-method" -> { cliMethod = argv.getOrNull(++i); i++ }
            "--cli-params-json" -> { cliParamsJson = argv.getOrNull(++i); i++ }
            "--tool" -> { cliToolName = argv.getOrNull(++i); i++ }
            "--arguments-json" -> { cliArgumentsJson = argv.getOrNull(++i); i++ }
            "--uri" -> { cliUri = argv.getOrNull(++i); i++ }
            "-h", "--help" -> { help = true; i++ }
            else -> i++
        }
    }

    return CliArgs(
        configPath = configPath,
        scanIntervalMs = scanIntervalMs,
        logTraffic = logTraffic,
        help = help,
        mode = mode,
        cliMethod = cliMethod,
        cliParamsJson = cliParamsJson,
        cliToolName = cliToolName,
        cliArgumentsJson = cliArgumentsJson,
        cliUri = cliUri
    )
}

fun parseJsonFlag(rawValue: String?, fieldName: String): JsonObject {
    if (rawValue.isNullOrBlank()) return buildJsonObject {  }
    return try {
        val parsed = Json.parseToJsonElement(rawValue)
        if (parsed !is JsonObject) error("$fieldName must be a JSON object")
        parsed
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid $fieldName: ${e.message}")
    }
}

fun buildCliRequest(args: CliArgs): Pair<String, JsonObject> {
    if (args.cliMethod != null) {
        return args.cliMethod to parseJsonFlag(args.cliParamsJson, "--cli-params-json")
    }
    if (args.cliToolName != null) {
        return "tools/call" to kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(args.cliToolName))
            put("arguments", parseJsonFlag(args.cliArgumentsJson, "--arguments-json"))
        }
    }
    if (args.cliUri != null) {
        return "resources/read" to kotlinx.serialization.json.buildJsonObject {
            put("uri", kotlinx.serialization.json.JsonPrimitive(args.cliUri))
        }
    }
    return "tools/list" to buildJsonObject {  }
}

fun expandHome(value: String?): String? {
    if (value == null) return null
    return if (value.startsWith("~")) {
        System.getProperty("user.home") + value.substring(1)
    } else value
}

fun loadConfig(args: CliArgs): ProxyConfig {
    val defaultPath = File(System.getProperty("user.home"), ".mcp-steroid/proxy.json")
    val configFile = if (args.configPath != null) File(args.configPath) else defaultPath

    var config = ProxyConfig()

    if (configFile.exists()) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(configFile.readText()).jsonObject
            config = mergeConfigFromJson(config, obj)
        } catch (e: Exception) {
            if (args.configPath != null) throw e
        }
    }

    if (args.scanIntervalMs != null && args.scanIntervalMs > 0) {
        config = config.copy(scanIntervalMs = args.scanIntervalMs)
    }
    if (args.logTraffic) {
        config = config.copy(trafficLog = config.trafficLog.copy(enabled = true))
    }

    val expandedCacheDir = expandHome(config.cache.dir) ?: config.cache.dir
    val expandedHomeDir = expandHome(config.homeDir)
    val expandedBeaconId = expandHome(config.beacon.distinctIdFile) ?: config.beacon.distinctIdFile

    return config.copy(
        homeDir = expandedHomeDir,
        cache = config.cache.copy(dir = expandedCacheDir),
        beacon = config.beacon.copy(distinctIdFile = expandedBeaconId)
    )
}

private fun mergeConfigFromJson(base: ProxyConfig, obj: JsonObject): ProxyConfig {
    val json = Json { ignoreUnknownKeys = true }
    return base.copy(
        homeDir = obj["homeDir"]?.jsonPrimitive?.content ?: base.homeDir,
        scanIntervalMs = obj["scanIntervalMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: base.scanIntervalMs,
        allowHosts = obj["allowHosts"]?.jsonArray?.map { it.jsonPrimitive.content } ?: base.allowHosts,
        defaultServerId = obj["defaultServerId"]?.jsonPrimitive?.content ?: base.defaultServerId,
        upstreamTimeoutMs = obj["upstreamTimeoutMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: base.upstreamTimeoutMs,
        cache = obj["cache"]?.jsonObject?.let { c ->
            base.cache.copy(
                enabled = c["enabled"]?.jsonPrimitive?.booleanOrNull ?: base.cache.enabled,
                dir = c["dir"]?.jsonPrimitive?.content ?: base.cache.dir,
                ttlSeconds = c["ttlSeconds"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: base.cache.ttlSeconds
            )
        } ?: base.cache,
        trafficLog = obj["trafficLog"]?.jsonObject?.let { t ->
            base.trafficLog.copy(
                enabled = t["enabled"]?.jsonPrimitive?.booleanOrNull ?: base.trafficLog.enabled,
                redactFields = t["redactFields"]?.jsonArray?.map { it.jsonPrimitive.content } ?: base.trafficLog.redactFields
            )
        } ?: base.trafficLog,
        updates = obj["updates"]?.jsonObject?.let { u ->
            base.updates.copy(
                enabled = u["enabled"]?.jsonPrimitive?.booleanOrNull ?: base.updates.enabled,
                initialDelayMs = u["initialDelayMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: base.updates.initialDelayMs,
                intervalMs = u["intervalMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: base.updates.intervalMs,
                requestTimeoutMs = u["requestTimeoutMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: base.updates.requestTimeoutMs
            )
        } ?: base.updates,
        beacon = obj["beacon"]?.jsonObject?.let { b ->
            base.beacon.copy(
                enabled = b["enabled"]?.jsonPrimitive?.booleanOrNull ?: base.beacon.enabled,
                host = b["host"]?.jsonPrimitive?.content ?: base.beacon.host,
                apiKey = b["apiKey"]?.jsonPrimitive?.content ?: base.beacon.apiKey,
                timeoutMs = b["timeoutMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: base.beacon.timeoutMs,
                heartbeatIntervalMs = b["heartbeatIntervalMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: base.beacon.heartbeatIntervalMs,
                distinctIdFile = b["distinctIdFile"]?.jsonPrimitive?.content ?: base.beacon.distinctIdFile
            )
        } ?: base.beacon
    )
}

fun loadProxyVersion(): String {
    return try {
        val resource = object {}.javaClass.classLoader.getResourceAsStream("proxy-version.txt")
        resource?.bufferedReader()?.readText()?.trim() ?: "0.1.0"
    } catch (e: Exception) {
        "0.1.0"
    }
}
