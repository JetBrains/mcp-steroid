// buildSrc is a separate build with its own settings. Without this file it keeps
// using Gradle's default local build cache even when the root settings.gradle.kts
// disables all caching for release builds (-Pmcp.release.build=true, see
// release/release-instructions.md Stage 6). Mirror just the release kill-switch
// here — buildSrc never uses the remote BuildFetch node, so only the local
// cache needs the switch. Same strict value parsing as the root build script's
// parseBooleanProperty.
val isReleaseBuild = when (val raw = providers.gradleProperty("mcp.release.build").orNull?.trim()?.lowercase()) {
    null, "0", "false", "no", "off" -> false
    "1", "true", "yes", "on" -> true
    else -> error("Unsupported mcp.release.build value '$raw' (expected true/false or 1/0)")
}

buildCache {
    local {
        isEnabled = !isReleaseBuild
    }
}
