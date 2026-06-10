/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

/**
 * Plugin↔devrig version-skew warning. Users upgrade devrig and the in-IDE plugin independently, so a
 * version difference is normal day-to-day; only a **version-base** difference (the `major.minor`
 * prefix — the same notion as `version-base` in the downloaded `version.json`, see
 * [DevrigVersionInfo.versionBase]) indicates a pairing the project does not test together.
 *
 * When the bases differ, devrig warns on **every `steroid_execute_code` tool call** it routes
 * (devrig scenario only — the in-IDE plugin has no peer to skew against). The warning goes to
 * **stderr**, never stdout — stdout is the JSON-RPC channel of the stdio MCP server.
 */
object BackendVersionSkew {
    private val VERSION_BASE = Regex("""^(\d+\.\d+)""")

    /**
     * Leading `major.minor` numeric prefix of a version string, e.g. `"0.100-409f23a2"` and
     * `"0.100.19999-SNAPSHOT-9b6783a6"` both yield `"0.100"`. Null when the string carries no
     * parseable base (blank, garbage) — such versions never trigger a skew warning.
     */
    fun versionBase(version: String): String? =
        VERSION_BASE.find(version.trim())?.groupValues?.get(1)

    /** True when both versions carry a parseable base and the bases differ. */
    fun isSkewed(pluginVersion: String, devrigVersion: String): Boolean {
        val pluginBase = versionBase(pluginVersion) ?: return false
        val devrigBase = versionBase(devrigVersion) ?: return false
        return pluginBase != devrigBase
    }

    /** Warn (stderr, every call — no de-dup) when [pluginVersion]'s base differs from devrig's. */
    fun warnOnExecCode(
        pid: Long,
        pluginVersion: String,
        devrigVersion: String = DevrigVersionMetadata.getDevrigVersion(),
    ) {
        if (!isSkewed(pluginVersion, devrigVersion)) return
        System.err.println(
            "WARN: devrig $devrigVersion routing exec_code to MCP Steroid $pluginVersion (pid $pid) — " +
                "version bases differ (${versionBase(devrigVersion)} vs ${versionBase(pluginVersion)}); " +
                "update devrig and the plugin to the same release.",
        )
    }
}
