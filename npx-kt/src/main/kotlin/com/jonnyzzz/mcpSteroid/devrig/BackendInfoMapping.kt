/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.backendNameFor

// R3.3 — the shared backend_name formula (backendNameFor + backendNameForMarker) lives in
// mcp-steroid-server (com.jonnyzzz.mcpSteroid.server.BackendName) so the in-IDE plugin and devrig
// recompute the same id for the same input. The port variant below is devrig-only.

/** Port-discovered backend_name: keyed by the scanned port. */
fun backendNameForPort(port: Int, build: String?): String =
    backendNameFor(sourceKey = "port:$port", build = build)

/**
 * Strip a leading product-code prefix (letters + hyphen, e.g. `IU-`, `PC-`,
 * `GO-`) so marker builds (`IU-261.23567.138`) compare equal to `/api/about`
 * builds (`261.23567.138`). Returns `null` for null/blank input so callers
 * can use it as a Map key without further filtering.
 */
fun normaliseBuildForDedup(build: String?): String? {
    if (build.isNullOrBlank()) return null
    return build.replaceFirst(Regex("^[A-Z]+-"), "")
}
