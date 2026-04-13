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
 * format matches "<baseVersion>-SNAPSHOT-(GH|JB)-<gitHash>-<counter>" so a misconfigured CI
 * fails fast instead of silently producing a wrongly-labelled artifact.
 */
val providedBuildVersion: String? = providers.gradleProperty("mcp.build.version")
    .orNull?.trim()?.takeIf { it.isNotEmpty() }

if (providedBuildVersion != null) {
    val expected = Regex(
        "^" + Regex.escape(baseVersion) + "-SNAPSHOT-(GH|JB)-" + Regex.escape(gitHash) + "-\\d+$"
    )
    require(expected.matches(providedBuildVersion)) {
        "mcp.build.version='$providedBuildVersion' does not match expected format " +
            "'${baseVersion}-SNAPSHOT-{GH|JB}-${gitHash}-<counter>'. " +
            "The build number must be composed upstream (GitHub Actions run_number or " +
            "TeamCity buildNumber service message) and passed in unchanged — this build " +
            "does not rewrite it."
    }
}

val snapshotTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
version = when {
    isReleaseBuild -> "$baseVersion-$gitHash"
    providedBuildVersion != null -> providedBuildVersion
    else -> "$baseVersion-SNAPSHOT-$snapshotTimestamp-$gitHash"
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
