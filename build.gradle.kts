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
val isGhBuild = parseBooleanProperty(
    propertyName = "mcp.gh.build",
    raw = providers.gradleProperty("mcp.gh.build").orElse("false").get()
)
val isJbBuild = parseBooleanProperty(
    propertyName = "mcp.jb.build",
    raw = providers.gradleProperty("mcp.jb.build").orElse("false").get()
)
val snapshotTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
version = when {
    isReleaseBuild -> "$baseVersion-$gitHash"
    isGhBuild -> "$baseVersion-SNAPSHOT-GH-$gitHash"
    isJbBuild -> "$baseVersion-SNAPSHOT-JB-$gitHash"
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
