/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

/**
 * Pinned IDE target for a given IntelliJ major release.
 *
 * [major] is the platform build-number major (e.g. "261", "262") and acts as
 * the per-major scope — every entry MUST be locked to one major, so a future
 * EAP cut (263) cannot silently take its place inside our verifier matrix.
 *
 * [version] is what the IntelliJ Platform Gradle Plugin / the downloader's
 * products-API resolver consumes. It may be:
 *
 *  - a stable version string (e.g. `"2026.1"`) — resolves to the latest 2026.1.x
 *    public release;
 *  - a per-major EAP snapshot tag (e.g. `"262-EAP-SNAPSHOT"`) — the user-mandated
 *    spelling that pins to "the latest 262 EAP, never sliding to 263";
 *  - a fully-pinned build number (e.g. `"262.6228.19"`) — strictest form.
 *
 * Rolling cross-major tags like `LATEST-EAP-SNAPSHOT` are rejected by
 * [validateTargets] because they would silently slide to a major we have not
 * tested yet.
 */
data class IdeTarget(
    val major: String,
    val version: String,
)

/**
 * Single source of truth for which IntelliJ majors the plugin builds against,
 * tests against, and the Plugin Verifier covers. Both the in-repo
 * `intellij-downloader` CLI and `ij-plugin/build.gradle.kts` consume this
 * object — the .kt file is shared into `buildSrc` via an extra source dir so
 * Gradle scripts can `import com.jonnyzzz.mcpSteroid.ideDownloader.McpSteroidIdeTargets`
 * at script-evaluation time without round-tripping through compiled artifacts.
 *
 * See `docs/262-EAP-PLAN.md` for the full rationale and the per-major
 * verification matrix that this object encodes.
 */
object McpSteroidIdeTargets {
    /**
     * The IDE major that the plugin is compiled against. There is exactly one
     * build target — the plugin .zip is single-target by design.
     */
    val buildTarget: IdeTarget = IdeTarget(major = "261", version = "2026.1")

    /**
     * IDE entries fed to `pluginVerification.ides { }` and to the in-repo
     * `intellij-downloader`'s `prepareLocalIdes` task. Order matters only for
     * deterministic Gradle task naming; the matrix-shape test
     * (`McpSteroidIdeTargetsTest`) enforces the per-major contract.
     *
     * 262 uses the named EAP tag explicitly so a future 263 EAP cut surfaces
     * as a missing-major test failure, not a silent slide.
     */
    val verifierTargets: List<IdeTarget> = listOf(
        IdeTarget(major = "261", version = "2026.1"),
        IdeTarget(major = "262", version = "262-EAP-SNAPSHOT"),
    )

    /** All IDE entries the build cares about, deduplicated. */
    val allTargets: List<IdeTarget> get() = (listOf(buildTarget) + verifierTargets).distinct()

    init {
        validateTargets(buildTarget, verifierTargets)
    }
}

/**
 * Defense-in-depth validation of the matrix shape. Called from the [McpSteroidIdeTargets]
 * `init {}` block and also covered by `McpSteroidIdeTargetsTest` so failures
 * surface at both class-load time and test-suite time. The primary safety net
 * is the test; this gate catches accidental edits that bypass the test.
 */
internal fun validateTargets(buildTarget: IdeTarget, verifierTargets: List<IdeTarget>) {
    require(verifierTargets.isNotEmpty()) {
        "verifierTargets must contain at least one entry"
    }

    // Per-entry checks first — each target must be individually well-formed
    // before we reason about the relationship between buildTarget and the
    // first verifier entry. Surfacing the per-entry problem first gives
    // the clearest error message for the most common edits.
    val deprecatedMajors = setOf("252", "253")
    val seenMajors = mutableSetOf<String>()
    for (target in verifierTargets) {
        require(target.major !in deprecatedMajors) {
            "IDE major '${target.major}' is deprecated. The plugin no longer " +
                "supports 252/253. Drop the entry from verifierTargets."
        }
        require(seenMajors.add(target.major)) {
            "Duplicate major '${target.major}' in verifierTargets."
        }
        require(!target.version.contains("LATEST", ignoreCase = false)) {
            "Rolling cross-major tags (LATEST-*) are forbidden in verifierTargets " +
                "— they would silently slide to a major we have not tested. " +
                "Got '${target.version}' for major ${target.major}. Use a named " +
                "per-major tag (e.g. '${target.major}-EAP-SNAPSHOT') or an exact " +
                "build number."
        }
        val isSnapshot = target.version.endsWith("-SNAPSHOT") ||
            target.version.endsWith("-EAP-SNAPSHOT")
        if (isSnapshot) {
            require(target.version.startsWith("${target.major}-")) {
                "EAP/snapshot tags must be per-major-scoped. Expected the version " +
                    "for major ${target.major} to start with '${target.major}-' " +
                    "(e.g. '${target.major}-EAP-SNAPSHOT'). Got '${target.version}'."
            }
        }
    }

    // Cross-cutting check last: the plugin is compiled against, and first
    // verified against, the same IDE. Caller bugs at the per-entry level
    // surface above with a clearer message; this fires for fixture errors
    // that put a different IDE first.
    val buildMatchesFirstVerifier = verifierTargets.first() == buildTarget
    require(buildMatchesFirstVerifier) {
        "buildTarget ($buildTarget) must match the first verifierTargets entry " +
            "(${verifierTargets.first()}); the plugin is compiled against, and " +
            "first verified against, the same IDE."
    }
}
