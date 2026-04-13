plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

// Resolvable configuration to get the plugin .zip from :ij-plugin subproject
val pluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

// Resolvable configuration to get the agent-output-filter executable distribution zip
val agentOutputFilterDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Resolvable configuration to get the NPX package zip from :npx subproject
val npxPackageDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "npx-package"))
    }
}

dependencies {
    pluginZip(project(":ij-plugin"))
    agentOutputFilterDist(project(path = ":agent-output-filter", configuration = "executableDistribution"))
    npxPackageDist(project(":npx"))

    // Shared infrastructure (containers, MCP client, drivers) lives in :test-integration's main source set.
    testImplementation(project(":test-integration"))
    testImplementation(project(":test-helper"))
    testImplementation(project(":agent-output-filter"))
    testImplementation(project(":ai-agents"))
    testImplementation(project(":intellij-downloader"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

// Docker images and test fixture projects live in :test-integration; we point at them via system property.
val sharedDockerDir = project(":test-integration").projectDir.resolve("src/test/docker")

/**
 * Applies shared configuration to any experimental integration test task:
 * classpath, logging, timeout, artifact dependencies, and common system properties.
 */
fun Test.configureExperimentalTest() {
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    // Forward claude.comparison.* system properties from the Gradle JVM to the test JVM.
    System.getProperties()
        .filterKeys { it.toString().startsWith("claude.comparison.") }
        .forEach { (key, value) -> systemProperty(key.toString(), value.toString()) }

    // Forward arena.test.* system properties (used by DpaiaArenaTest).
    System.getProperties()
        .filterKeys { it.toString().startsWith("arena.test.") }
        .forEach { (key, value) -> systemProperty(key.toString(), value.toString()) }

    dependsOn(pluginZip, agentOutputFilterDist, npxPackageDist)
    doFirst {
        delete(layout.buildDirectory.dir("test-results/${this@configureExperimentalTest.name}/binary"))
        val testOutDir = layout.buildDirectory
            .dir("test-logs/${this@configureExperimentalTest.name}").get().asFile
            .also { it.mkdirs() }

        val resolvedPluginZip = pluginZip.singleFile
        require(resolvedPluginZip.isFile) { "Plugin ZIP not found: ${resolvedPluginZip.absolutePath}" }

        systemProperty("test.integration.plugin.zip", resolvedPluginZip.absolutePath)
        systemProperty(
            "test.integration.ide.download.dir",
            layout.buildDirectory.dir("ide-download").get().asFile.absolutePath,
        )
        require(sharedDockerDir.isDirectory) {
            "Shared docker dir not found: ${sharedDockerDir.absolutePath}"
        }
        systemProperty("test.integration.docker", sharedDockerDir.absolutePath)
        systemProperty("test.integration.testOutput", testOutDir.absolutePath)
        systemProperty(
            "test.integration.agent.output.filter.zip",
            agentOutputFilterDist.singleFile.absolutePath,
        )
        systemProperty(
            "test.integration.npx.package.zip",
            npxPackageDist.singleFile.absolutePath,
        )
        systemProperty(
            "test.integration.repo.cache.dir",
            layout.buildDirectory.dir("repo-cache").get().asFile.absolutePath,
        )

        // Build-compatibility test: persistent caches so IDE downloads and Gradle state survive across runs
        val buildCompatDir = layout.buildDirectory.dir("build-compat").get().asFile
        systemProperty(
            "test.integration.build.compat.gradle.home",
            File(buildCompatDir, "gradle-home").also { it.mkdirs() }.absolutePath,
        )
        systemProperty(
            "test.integration.build.compat.ij.platform",
            File(buildCompatDir, "intellij-platform").also { it.mkdirs() }.absolutePath,
        )
    }
}

tasks.test {
    configureExperimentalTest()

    // Prevent this task from being silently triggered by root-level './gradlew test' aggregation.
    // Experimental integration tests require Docker, API keys, and IDE containers — invoke explicitly.
    //
    // Correct usage:
    //   ./gradlew :test-experiments:test --tests '*DebuggerDemoTest.claude*'
    //   ./gradlew :test-experiments:test --tests '*DpaiaArenaTest*' -Darena.test.instanceId=<id>
    onlyIf("Requires explicit :test-experiments: task invocation — not for root aggregation") {
        gradle.startParameter.taskNames.any { it.contains(":test-experiments:") }
    }
}
