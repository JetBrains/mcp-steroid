/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

/**
 * Baseline IntelliJ build the managed-backend resolver may fall back to for any
 * `IdeProduct.knownProducts` entry. The ij-plugin's `sinceBuild` in
 * `ij-plugin/build.gradle.kts` must be `<=` this baseline so the plugin loads in
 * the oldest IDE the resolver could install.
 *
 * Updating this constant requires a matching update to `ij-plugin/build.gradle.kts`
 * (`pluginConfiguration.ideaVersion.sinceBuild`) — `PluginCompatibilityFloorTest`
 * enforces the pair.
 *
 * The value is the baseline (major) build number — "252" — not the full build
 * string like "252.28539.54". Pin the baseline only; the resolver picks the
 * latest release within that baseline.
 */
const val MANAGED_BACKEND_MIN_SUPPORTED_BUILD: String = "252"
