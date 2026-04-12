/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Validates that the plugin compiles and packages against multiple IntelliJ Platform versions.
 *
 * Each test mounts the local project into a Docker container, cleans ignored files via
 * `git clean`, copies the clean tree to a build directory with persistent Gradle and
 * IntelliJ Platform caches, and runs `./gradlew :ij-plugin:buildPlugin`.
 *
 * When the target IDE bundles a newer Kotlin than the project default, a [kotlinVersion]
 * patch is applied to the root build.gradle.kts before the build.
 *
 * Run:
 *   ./gradlew :test-integration:test --tests '*PluginBuildCompatibilityTest*'
 */
class PluginBuildCompatibilityTest {

    companion object {
        private lateinit var buildImage: ImageDriver

        private val projectHome: File by lazy {
            ProjectHomeDirectory.requireProjectHomeDirectory().toFile()
        }
        private val gradleHomeDir: File by lazy {
            File(System.getProperty("test.integration.build.compat.gradle.home")
                ?: error("test.integration.build.compat.gradle.home not set"))
        }
        private val ijPlatformCacheDir: File by lazy {
            File(System.getProperty("test.integration.build.compat.ij.platform")
                ?: error("test.integration.build.compat.ij.platform not set"))
        }

        @JvmStatic
        @BeforeAll
        fun setUp() {
            buildImage = buildDockerImage(
                logPrefix = "build-compat",
                dockerfilePath = File(IdeTestFolders.dockerDir, "dev-build/Dockerfile"),
                timeoutSeconds = 600,
                quietly = true,
            )
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 2025_3`() =
        buildPluginWithVersion("2025.3")

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 2026_1`() =
        buildPluginWithVersion("2026.1", kotlinVersion = "2.4.0-Beta1")

    // 262-SNAPSHOT requires the nightly repo which is not publicly accessible.
    // Enable when 2026.2 EAP is published to the snapshots repo.
    // @Test
    // @Timeout(value = 30, unit = TimeUnit.MINUTES)
    // fun `build plugin with IntelliJ 262 nightly`() =
    //     buildPluginWithVersion("262-SNAPSHOT", kotlinVersion = "2.4.0-Beta1", useNightlyRepo = true)

    private fun buildPluginWithVersion(
        platformVersion: String,
        kotlinVersion: String? = null,
        useNightlyRepo: Boolean = false,
    ) = runWithCloseableStack { lifetime ->
        val container = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(buildImage)
                .logPrefix("build-${platformVersion.replace(".", "")}")
                .entryPoint("sleep", "infinity")
                .volumes(
                    ContainerVolume(projectHome, SRC_GUEST, "ro"),
                    ContainerVolume(gradleHomeDir, GRADLE_HOME_GUEST),
                    ContainerVolume(ijPlatformCacheDir, IJ_PLATFORM_GUEST),
                ),
        )

        // Copy project into container, clean ignored files, then copy clean tree to build dir with caches
        container.startProcessInContainer {
            this
                .args(
                    "bash", "-c",
                    """
                    cp -a $SRC_GUEST $PREBUILD_GUEST &&
                    cd $PREBUILD_GUEST &&
                    git clean -fdx &&
                    cp -a $PREBUILD_GUEST $BUILD_GUEST &&
                    ln -sfn $IJ_PLATFORM_GUEST $BUILD_GUEST/.intellijPlatform
                    """.trimIndent().replace('\n', ' '),
                )
                .description("Prepare clean build tree for $platformVersion")
                .timeoutSeconds(120)
        }.assertExitCode(0) { "Failed to prepare build tree: $stderr" }

        // Patch Kotlin version in root build.gradle.kts when the target IDE needs a newer compiler
        if (kotlinVersion != null) {
            container.startProcessInContainer {
                this
                    .args(
                        "sed", "-i",
                        "s/kotlin(\"jvm\") version \"[^\"]*\"/kotlin(\"jvm\") version \"$kotlinVersion\"/;" +
                            "s/kotlin(\"plugin.serialization\") version \"[^\"]*\"/kotlin(\"plugin.serialization\") version \"$kotlinVersion\"/",
                        "$BUILD_GUEST/build.gradle.kts",
                    )
                    .description("Patch Kotlin version to $kotlinVersion")
                    .timeoutSeconds(5)
            }.assertExitCode(0) { "Failed to patch Kotlin version: $stderr" }
        }

        // Add nightly repository for unreleased IDE versions (262-SNAPSHOT etc.)
        if (useNightlyRepo) {
            container.startProcessInContainer {
                this
                    .args(
                        "sed", "-i",
                        "s/defaultRepositories()/defaultRepositories()\\n        nightly()/",
                        "$BUILD_GUEST/ij-plugin/build.gradle.kts",
                    )
                    .description("Add nightly repo")
                    .timeoutSeconds(5)
            }.assertExitCode(0) { "Failed to add nightly repo: $stderr" }
        }

        // Build the plugin
        container.startProcessInContainer {
            this
                .workingDirInContainer(BUILD_GUEST)
                .args(
                    "./gradlew", ":ij-plugin:buildPlugin",
                    "-Pmcp.platform.version=$platformVersion",
                    "--no-daemon",
                    "--stacktrace",
                )
                .addEnv("GRADLE_USER_HOME", GRADLE_HOME_GUEST)
                .description("Build plugin for IntelliJ $platformVersion")
                .timeoutSeconds(1800)
        }.assertExitCode(0) { "buildPlugin failed for IntelliJ $platformVersion: $stderr" }

        // Verify plugin ZIP was produced
        container.startProcessInContainer {
            this
                .workingDirInContainer(BUILD_GUEST)
                .args("find", "ij-plugin/build/distributions", "-name", "*.zip", "-type", "f")
                .description("Verify plugin ZIP exists")
                .timeoutSeconds(10)
        }.assertExitCode(0) { "Plugin distributions directory missing: $stderr" }
            .assertOutputContains(".zip", message = "No plugin ZIP found for IntelliJ $platformVersion")
    }
}

private const val SRC_GUEST = "/mnt/project"
private const val PREBUILD_GUEST = "/tmp/prebuild"
private const val BUILD_GUEST = "/build"
private const val GRADLE_HOME_GUEST = "/cache/gradle-home"
private const val IJ_PLATFORM_GUEST = "/cache/ij-platform"
