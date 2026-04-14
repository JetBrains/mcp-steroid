package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
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
 * Build compatibility: validates the plugin compiles against multiple IntelliJ Platform versions.
 *
 * Run:
 *   ./gradlew :test-experiments:test --tests '*PluginBuildCompatibilityTest*'
 */
class PluginBuildCompatibilityTest {

    companion object {
        @JvmStatic @BeforeAll
        fun setUp() = BuildCompatInfra.setUp()
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 2025_3`() =
        BuildCompatInfra.buildPlugin("2025.3")

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 2026_1`() =
        BuildCompatInfra.buildPlugin("2026.1", patches = KOTLIN_2_4_PATCHES)

    // Reproduces mcp-steroid#18: 262 changed StatusBarEx.getBackgroundProcessModels()
    // return type from c.i.o.u.Pair to kotlin.Pair.
    // Requires nightly repo (internal JetBrains network).
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 262 nightly`() =
        BuildCompatInfra.buildPlugin("262-SNAPSHOT", patches = SNAPSHOT_262_PATCHES)
}

/**
 * Verification compatibility: the plugin is built against 253 (default), then the
 * Plugin Verifier checks the same binary against 253, 261, and 262.
 *
 * Strategy: build once against 253, verify the same binary against newer IDEs.
 * Catches API removals, signature changes (like mcp-steroid#18), and other
 * binary incompatibilities before they reach users.
 *
 * Run:
 *   ./gradlew :test-experiments:test --tests '*PluginVerificationTest*'
 */
class PluginVerificationTest {

    companion object {
        @JvmStatic @BeforeAll
        fun setUp() = BuildCompatInfra.setUp()
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `verify plugin against IntelliJ 2025_3`() =
        BuildCompatInfra.verifyPlugin("""ide(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")""")

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `verify plugin against IntelliJ 2026_1`() =
        BuildCompatInfra.verifyPlugin("""ide(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")""")

    // Reproduces mcp-steroid#18: verifier should flag the Pair type incompatibility.
    // Requires nightly repo (internal JetBrains network).
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `verify plugin against IntelliJ 262 nightly`() =
        BuildCompatInfra.verifyPlugin(
            ideEntry = """ide(IntelliJPlatformType.IntellijIdeaUltimate, "262-SNAPSHOT")""",
            extraPatches = NIGHTLY_REPO_PATCHES,
        )
}

private object BuildCompatInfra {
    private lateinit var buildImage: ImageDriver

    private val projectHome: File by lazy {
        ProjectHomeDirectory.requireProjectHomeDirectory().toFile()
    }
    private val gradleHomeDir: File by lazy {
        File(
            System.getProperty("test.integration.build.compat.gradle.home")
                ?: error("test.integration.build.compat.gradle.home not set")
        )
    }
    private val ijPlatformCacheDir: File by lazy {
        File(
            System.getProperty("test.integration.build.compat.ij.platform")
                ?: error("test.integration.build.compat.ij.platform not set")
        )
    }

    fun setUp() {
        if (::buildImage.isInitialized) return
        buildImage = buildDockerImage(
            logPrefix = "build-compat",
            dockerfilePath = File(projectHome, "docker/build/Dockerfile"),
            timeoutSeconds = 600,
            quietly = true,
        )
    }

    fun buildPlugin(platformVersion: String, patches: List<SedPatch> = emptyList()) =
        runWithCloseableStack { lifetime ->
            val container = prepareContainer(lifetime, "build-${platformVersion.replace(".", "")}")
            applyPatches(container, patches)

            container.startProcessInContainer {
                this
                    .workingDirInContainer(BUILD_GUEST)
                    .args(
                        "./gradlew", ":ij-plugin:buildPlugin",
                        "-Pmcp.platform.version=$platformVersion",
                        "--no-daemon", "--stacktrace",
                    )
                    .addEnv("GRADLE_USER_HOME", GRADLE_HOME_GUEST)
                    .description("Build plugin for IntelliJ $platformVersion")
                    .timeoutSeconds(1800)
            }.assertExitCode(0) { "buildPlugin failed for IntelliJ $platformVersion: $stderr" }

            container.startProcessInContainer {
                this
                    .workingDirInContainer(BUILD_GUEST)
                    .args("find", "ij-plugin/build/distributions", "-name", "*.zip", "-type", "f")
                    .description("Verify plugin ZIP exists")
                    .timeoutSeconds(10)
            }.assertExitCode(0) { "Plugin distributions dir missing: $stderr" }
                .assertOutputContains(".zip", message = "No plugin ZIP found for IntelliJ $platformVersion")
        }

    fun verifyPlugin(ideEntry: String, extraPatches: List<SedPatch> = emptyList()) =
        runWithCloseableStack { lifetime ->
            val container = prepareContainer(lifetime, "verify")
            applyPatches(container, extraPatches)

            // Replace the pluginVerification ides block with the target IDE
            container.startProcessInContainer {
                this
                    .args(
                        "perl", "-i", "-0pe",
                        """s/pluginVerification \{.*?ides \{.*?\}\s*\}/pluginVerification { ides { $ideEntry } }/s""",
                        "$BUILD_GUEST/ij-plugin/build.gradle.kts",
                    )
                    .description("Set verifier IDE to $ideEntry")
                    .timeoutSeconds(5)
            }.assertExitCode(0) { "Failed to patch pluginVerification: $stderr" }

            // Build against 253 (default), then verify against target IDE
            container.startProcessInContainer {
                this
                    .workingDirInContainer(BUILD_GUEST)
                    .args(
                        "./gradlew", ":ij-plugin:buildPlugin", ":ij-plugin:verifyPlugin",
                        "--no-daemon", "--stacktrace",
                    )
                    .addEnv("GRADLE_USER_HOME", GRADLE_HOME_GUEST)
                    .description("Build + verify plugin")
                    .timeoutSeconds(1800)
            }.assertExitCode(0) { "Build+verify failed: $stderr" }
        }

    private fun prepareContainer(lifetime: CloseableStack, logPrefix: String): ContainerDriver {
        val container = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest.Companion()
                .image(buildImage)
                .logPrefix(logPrefix)
                .entryPoint("sleep", "infinity")
                .volumes(
                    ContainerVolume(projectHome, SRC_GUEST, "ro"),
                    ContainerVolume(gradleHomeDir, GRADLE_HOME_GUEST),
                    ContainerVolume(ijPlatformCacheDir, IJ_PLATFORM_GUEST),
                ),
        )
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
                .description("Prepare clean build tree")
                .timeoutSeconds(120)
        }.assertExitCode(0) { "Failed to prepare build tree: $stderr" }
        return container
    }

    private fun applyPatches(container: ContainerDriver, patches: List<SedPatch>) {
        for (patch in patches) {
            container.startProcessInContainer {
                this
                    .args("sed", "-i", patch.expression, "$BUILD_GUEST/${patch.file}")
                    .description(patch.description)
                    .timeoutSeconds(5)
            }.assertExitCode(0) { "Failed to apply patch '${patch.description}': $stderr" }
        }
    }
}

private data class SedPatch(val file: String, val expression: String, val description: String)

private const val SRC_GUEST = "/mnt/project"
private const val PREBUILD_GUEST = "/tmp/prebuild"
private const val BUILD_GUEST = "/build"
private const val GRADLE_HOME_GUEST = "/cache/gradle-home"
private const val IJ_PLATFORM_GUEST = "/cache/ij-platform"

private val KOTLIN_2_4_PATCHES = listOf(
    SedPatch(
        file = "build.gradle.kts",
        expression = """s/kotlin("jvm") version "[^"]*"/kotlin("jvm") version "2.4.0-Beta1"/;""" +
            """s/kotlin("plugin.serialization") version "[^"]*"/kotlin("plugin.serialization") version "2.4.0-Beta1"/""",
        description = "Patch Kotlin version to 2.4.0-Beta1",
    ),
)

private val SNAPSHOT_262_PATCHES = KOTLIN_2_4_PATCHES + listOf(
    SedPatch(
        file = "build.gradle.kts",
        expression = """s/id("org.jetbrains.intellij.platform") version "[^"]*"/id("org.jetbrains.intellij.platform") version "2.14.0"/""",
        description = "Bump IntelliJ Platform Gradle Plugin to 2.14.0",
    ),
    SedPatch(
        file = "ij-plugin/build.gradle.kts",
        expression = """s/intellijIdeaUltimate(targetIdeVersion)/intellijIdeaUltimate(targetIdeVersion) { useInstaller = false }/""",
        description = "Disable installer mode for Maven snapshot resolution",
    ),
    SedPatch(
        file = "ij-plugin/build.gradle.kts",
        expression = """s/defaultRepositories()/defaultRepositories()\n        nightly()/""",
        description = "Add nightly repo for 262-SNAPSHOT",
    ),
)

private val NIGHTLY_REPO_PATCHES = listOf(
    SedPatch(
        file = "build.gradle.kts",
        expression = """s/id("org.jetbrains.intellij.platform") version "[^"]*"/id("org.jetbrains.intellij.platform") version "2.14.0"/""",
        description = "Bump IntelliJ Platform Gradle Plugin to 2.14.0",
    ),
    SedPatch(
        file = "ij-plugin/build.gradle.kts",
        expression = """s/defaultRepositories()/defaultRepositories()\n        nightly()/""",
        description = "Add nightly repo",
    ),
)