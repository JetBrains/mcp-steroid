@file:Suppress("HasPlatformType")

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("de.undercouch.download") version "5.6.0" apply false
    id("org.jetbrains.intellij.platform") version "2.11.0" apply false
    id("com.github.node-gradle.node") version "7.1.0" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
}

group = "com.jonnyzzz.intellij"
val baseVersion = file("VERSION").readText().trim()
val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.get().trim()

fun parseBooleanProperty(propertyName: String, raw: String): Boolean {
    return when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> error("Unsupported $propertyName value '$raw' (expected true/false or 1/0)")
    }
}

fun String.escapeForHtmlPreBlock(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

val isReleaseBuild = parseBooleanProperty(
    propertyName = "mcp.release.build",
    raw = providers.gradleProperty("mcp.release.build").orElse("false").get()
)

/**
 * CI-supplied version string (GitHub Actions or TeamCity). When provided, the build uses it
 * verbatim as the plugin version — the build NEVER rewrites it. Gradle only asserts that the
 * format matches "<baseVersionPrefix>.<counter>-(gh|jb)-<gitHash>" so a misconfigured CI
 * fails fast instead of silently producing a wrongly-labelled artifact.
 *
 * Accepts either -Pmcp.build.version=<version> (GitHub Actions path) or the BUILD_NUMBER
 * environment variable (TeamCity sets it automatically from %build.number%, which we wire
 * to the upstream "build number" build config's emitted buildNumber service message).
 */
val providedBuildVersion: String? =
    providers.gradleProperty("mcp.build.version").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.takeIf {
            // Only accept BUILD_NUMBER when it looks like a full version string, not a bare
            // counter. GitHub Actions exports its run_id there; that must go through
            // -Pmcp.build.version instead so a misconfig is caught loudly.
            it.isNotEmpty() && it.contains('-')
        }

if (providedBuildVersion != null) {
    // The CI-computed version keeps the VERSION file content (all components) intact
    // and appends the CI counter plus the -<ci>-<hash> suffix.
    // e.g. VERSION=0.92.0 + counter=441 + hash=abcdef1 → 0.92.0.441-jb-abcdef1
    val expected = Regex(
        "^" + Regex.escape(baseVersion) + "\\.\\d+-(gh|jb)-" + Regex.escape(gitHash) + "$"
    )
    require(expected.matches(providedBuildVersion)) {
        "mcp.build.version='$providedBuildVersion' does not match expected format " +
            "'${baseVersion}.<counter>-(gh|jb)-${gitHash}'. " +
            "The build number must be composed upstream (GitHub Actions run_number or " +
            "TeamCity buildNumber service message) and passed in unchanged — this build " +
            "does not rewrite it."
    }
}

// Local/dev builds — no CI counter available. Use the literal "19999-SNAPSHOT" in place
// of the CI counter so the shape stays <VERSION>.<counter>-...-<hash>, sorts after any
// realistic CI run, and is obviously not an official build. Keep the per-invocation
// timestamp so two successive local builds still get distinct version strings (useful
// when pushing local snapshots into a Maven cache or a plugin install dir).
val localBuildCounter = "19999-SNAPSHOT"
val snapshotTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
version = when {
    isReleaseBuild -> "$baseVersion-$gitHash"
    providedBuildVersion != null -> providedBuildVersion
    else -> "$baseVersion.$localBuildCounter-$snapshotTimestamp-$gitHash"
}
val releaseNotesVersion = providers.gradleProperty("mcp.release.notes.version").orElse(baseVersion).get()
val releaseNotesFile = layout.projectDirectory.file("release/notes/$releaseNotesVersion.md")
val releaseNotesText: Provider<String>? = if (isReleaseBuild) {
    providers.provider {
        val file = releaseNotesFile.asFile
        require(file.isFile) {
            "Release build requires release notes at ${file.absolutePath}"
        }
        "<pre>${file.readText().trim().escapeForHtmlPreBlock()}</pre>"
    }
} else {
    null
}

extra["isReleaseBuild"] = isReleaseBuild
extra["releaseNotesText"] = releaseNotesText

subprojects {
    group = rootProject.group
    version = rootProject.version
}

allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("mcp.steroid.test.projectHome", rootProject.layout.projectDirectory.asFile.absolutePath)
        maxHeapSize = "4g"
    }
}

/**
 * Root configuration that resolves the plugin .zip artifact produced by :ij-plugin.
 * Consumed by [buildPluginOnCI] to avoid reaching into `ij-plugin/build/distributions/` directly.
 */
val pluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

dependencies {
    pluginZip(project(":ij-plugin"))
}

/**
 * CI entry point that builds the plugin and publishes the resulting .zip(s) as build artifacts.
 *
 * Depends on :ij-plugin's plugin-zip configuration (so Gradle drives the buildPlugin task for us),
 * then emits one `##teamcity[publishArtifacts '<path>']` service message per resolved zip so
 * TeamCity uploads them as regular build artifacts — no need for TeamCity-side artifactRules.
 * The service messages are harmless no-ops on GitHub Actions, which keeps using its own
 * `actions/upload-artifact` step.
 *
 * Intended to be the single entry point for both CI systems:
 *   ./gradlew buildPluginOnCI -Pmcp.build.version=<base>-SNAPSHOT-(GH|JB)-<counter>-<hash>
 */
val buildPluginOnCI by tasks.registering {
    group = "ci"
    description = "Build the plugin distribution and publish its binaries via TeamCity service messages."
    inputs.files(pluginZip)
    outputs.upToDateWhen { false }

    doLast {
        val zips = pluginZip.files
        require(zips.isNotEmpty()) {
            "No plugin .zip resolved from :ij-plugin's plugin-zip configuration"
        }
        zips.forEach { zip ->
            require(zip.isFile) { "Plugin zip not a file: ${zip.absolutePath}" }
            logger.lifecycle("Plugin binary: ${zip.absolutePath} (${zip.length()} bytes)")
            // TeamCity service message — ignored by GitHub Actions runners.
            // See https://www.jetbrains.com/help/teamcity/service-messages.html#Publishing+Artifacts+while+the+Build+is+Still+in+Progress
            println("##teamcity[publishArtifacts '${zip.absolutePath}']")
        }
    }
}
