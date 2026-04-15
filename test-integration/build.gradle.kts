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

    // Infrastructure code lives in src/main/kotlin so it can be reused by :test-experiments.
    implementation(project(":test-helper"))
    implementation(project(":agent-output-filter"))
    implementation(project(":ai-agents"))
    implementation(project(":intellij-downloader"))

    implementation(platform("org.junit:junit-bom:5.11.4"))
    implementation("org.junit.jupiter:junit-jupiter-api")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

/**
 * Applies shared configuration to any integration test task:
 * classpath, logging, timeout, artifact dependencies, and common system properties.
 * Additional task-specific system properties are set in each task's own doFirst block.
 */
fun Test.configureIntegrationTest() {
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    dependsOn(pluginZip, agentOutputFilterDist, npxPackageDist)
    doFirst {
        delete(layout.buildDirectory.dir("test-results/${this@configureIntegrationTest.name}/binary"))
        val testOutDir = layout.buildDirectory
            .dir("test-logs/${this@configureIntegrationTest.name}").get().asFile
            .also { it.mkdirs() }

        val resolvedPluginZip = pluginZip.singleFile
        require(resolvedPluginZip.isFile) { "Plugin ZIP not found: ${resolvedPluginZip.absolutePath}" }

        systemProperty("test.integration.plugin.zip", resolvedPluginZip.absolutePath)
        systemProperty(
            "test.integration.ide.download.dir",
            layout.buildDirectory.dir("ide-download").get().asFile.absolutePath,
        )
        systemProperty(
            "test.integration.docker",
            layout.projectDirectory.dir("src/test/docker").asFile.absolutePath,
        )
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
    }
}

tasks.test {
    configureIntegrationTest()

    // Project-property-driven filter: `-PtestFilter=*MyTest` is equivalent to
    // `--tests '*MyTest'` but works reliably from environments where the CLI
    // option ordering is fragile (TC's gradle runner emits gradleParams
    // BEFORE task names, which detaches `--tests` from its task; the script
    // step similarly can't always preserve quoted spaces). The property is
    // applied programmatically via Test#filter so no CLI parsing is involved.
    project.findProperty("testFilter")?.toString()?.let { pattern ->
        filter { includeTestsMatching(pattern) }
    }

    // TEMPORARY DEBUG: print discovery state when a filter is set so we can
    // see why TC can't find tests that local builds find with the same args.
    doFirst {
        if (project.hasProperty("testFilter")) {
            println("[testFilter-debug] pattern: ${project.property("testFilter")}")
            println("[testFilter-debug] testClassesDirs: ${testClassesDirs.files}")
            println("[testFilter-debug] testClassesDirs exist: ${testClassesDirs.files.map { it.exists() }}")
            testClassesDirs.files.filter { it.exists() }.forEach { d ->
                val classes = d.walk().filter { it.isFile && it.name.endsWith(".class") }.toList()
                println("[testFilter-debug] $d has ${classes.size} .class files")
                classes.take(30).forEach { println("  ${it.relativeTo(d)}") }
            }
            println("[testFilter-debug] filter.includePatterns: ${filter.includePatterns}")
            println("[testFilter-debug] filter.excludePatterns: ${filter.excludePatterns}")
            println("[testFilter-debug] classpath has junit-platform-launcher: ${classpath.files.any { it.name.contains("junit-platform-launcher") }}")
            println("[testFilter-debug] classpath has junit-jupiter-engine: ${classpath.files.any { it.name.contains("junit-jupiter-engine") }}")
        }
    }

    // Prevent this task from being silently triggered by root-level './gradlew test' aggregation.
    // Integration tests require Docker, API keys, and IDE containers — they must be invoked explicitly.
    //
    // Correct usage:
    //   ./gradlew :test-integration:test --tests '*EapSmokeTest*'
    //   ./gradlew :test-integration:test -PtestFilter='*EapSmokeTest*'   (CI-friendly)
    //   ./gradlew :test-integration:testReleaseSmokeIdea
    //   ./gradlew ciIntegrationTests                 (CI aggregator, see root build.gradle.kts)
    //
    // Experimental / long-running / API-key-heavy tests now live in :test-experiments.
    onlyIf("Requires explicit :test-integration: or ciIntegrationTests task invocation — not for root aggregation") {
        val names = gradle.startParameter.taskNames
        names.any { it.contains(":test-integration:") } ||
            names.any { it == "ciIntegrationTests" || it.endsWith(":ciIntegrationTests") }
    }
}

/**
 * Release smoke matrix: [IDEA, PyCharm, GoLand, WebStorm] × [stable, EAP].
 *
 * Runs a curated set of integration tests that validate plugin compatibility
 * across IDE products and versions. Invoked by run-release-build-matrix.sh.
 *
 * Accepts Gradle properties forwarded by the script:
 *   -Ptest.integration.idea.stable.version=2025.3.1
 *   -Ptest.integration.pycharm.stable.version=2025.3.1
 *   -Ptest.integration.goland.stable.version=2025.3.1
 *   -Ptest.integration.webstorm.stable.version=2025.3.1
 *   -Ptest.integration.idea.eap.build=253.12345.678
 *   -Ptest.integration.pycharm.eap.build=253.12345.678
 *   -Ptest.integration.goland.eap.build=253.12345.678
 *   -Ptest.integration.webstorm.eap.build=253.12345.678
 *
 * These are forwarded as system properties so tests can select specific builds
 * via IdeDistribution when version-pinned distribution support is implemented.
 */
val testReleaseSmokeMatrix by tasks.registering(Test::class) {
    description = "Release smoke matrix: [IDEA, PyCharm, GoLand, WebStorm] × [stable, EAP]"
    group = "verification"

    configureIntegrationTest()

    filter {
        includeTestsMatching("*DialogKillerIntegrationTest*")
        includeTestsMatching("*IntelliJContainerTest*")
        includeTestsMatching("*InfrastructureTest*")
        includeTestsMatching("*WhatYouSeeTest*")
        includeTestsMatching("*PyCharmContainerIntegrationTest*")
        includeTestsMatching("*PyCharmMcpExecutionIntegrationTest*")
    }

    doFirst {
        // Forward per-product version overrides from Gradle properties to system properties.
        // Tests that support pinned distributions can read these to select the right IDE build.
        listOf("idea", "pycharm", "goland", "webstorm").forEach { product ->
            findProperty("test.integration.$product.stable.version")
                ?.let { systemProperty("test.integration.$product.stable.version", it.toString()) }
            findProperty("test.integration.$product.eap.build")
                ?.let { systemProperty("test.integration.$product.eap.build", it.toString()) }
        }
    }
}

/**
 * Creates a named smoke test task for a specific IDE product and channel.
 *
 * Sets test.integration.ide.product and test.integration.ide.channel system properties
 * so IdeDistribution.fromSystemProperties() selects the right IDE.
 *
 * Usage examples:
 *   ./gradlew :test-integration:testReleaseSmokeIdea --tests '*EapSmokeTest*'
 *   ./gradlew :test-integration:testReleaseSmokeGoLandEap --tests '*EapSmokeTest*'
 */
fun registerSmokeTask(taskName: String, product: String, channel: String) {
    tasks.register(taskName, Test::class) {
        description = "Smoke test for $product ($channel)"
        group = "verification"
        configureIntegrationTest()
        doFirst {
            systemProperty("test.integration.ide.product", product)
            systemProperty("test.integration.ide.channel", channel)
            // Forward optional version pin from Gradle property (e.g. -Ptest.integration.goland.eap.build=...)
            val versionPropKey = if (channel == "eap") "test.integration.$product.eap.build"
                                 else "test.integration.$product.stable.version"
            findProperty(versionPropKey)
                ?.let { systemProperty(versionPropKey, it.toString()) }
        }
    }
}

registerSmokeTask("testReleaseSmokeIdea",        product = "idea",     channel = "stable")
registerSmokeTask("testReleaseSmokeIdeaEap",     product = "idea",     channel = "eap")
registerSmokeTask("testReleaseSmokeGoLand",      product = "goland",   channel = "stable")
registerSmokeTask("testReleaseSmokeGoLandEap",   product = "goland",   channel = "eap")
registerSmokeTask("testReleaseSmokeWebStorm",    product = "webstorm", channel = "stable")
registerSmokeTask("testReleaseSmokeWebStormEap", product = "webstorm", channel = "eap")
