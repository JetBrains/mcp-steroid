pluginManagement {
    repositories {
        // TeamCity-only: buildSrc's buildscript classpath (kotlin-dsl -> the Gradle-distribution-
        // pinned Kotlin artifacts) is THE resolution that died with 429s on the TC Mac farm —
        // it resolves through the implicit Plugin Portal, which redirects to Maven Central.
        // Mirror of the root settings block; see gradle/jetbrains-cache-redirector.settings.gradle.kts.
        if (System.getenv("TEAMCITY_VERSION") != null) {
            maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2") {
                name = "GradlePluginPortalViaJetBrainsCacheRedirector"
            }
        }
        gradlePluginPortal()
    }
}

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

// TeamCity-only reroute of 429-throttled public Maven hosts for buildSrc's own projects.
apply(from = "../gradle/jetbrains-cache-redirector.settings.gradle.kts")
