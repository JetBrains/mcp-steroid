import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

val promptGeneratorClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Consume kotlinc distribution from kotlin-cli subproject
val kotlincDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "kotlinc-dist"))
    }
}

// Configuration to resolve intellij-downloader as a classpath for the download task
val ideDownloaderClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    promptGeneratorClasspath(project(":prompt-generator"))

    api(project(":prompts-api"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":test-helper"))
    testImplementation(project(":kotlin-cli"))
    testImplementation(project(":prompt-generator"))

    kotlincDist(project(":kotlin-cli"))
    ideDownloaderClasspath(project(":intellij-downloader"))
}

val generatedSources = layout.buildDirectory.dir("generated/kotlin/prompts")
val generatedTestSources = layout.buildDirectory.dir("generated/kotlin-test/prompts")

val generatePrompts by tasks.registering(JavaExec::class) {
    classpath = promptGeneratorClasspath
    mainClass.set("com.jonnyzzz.mcpSteroid.promptgen.MainKt")
    args(
        "--input-dir", layout.projectDirectory.dir("src/main/prompts").asFile.absolutePath,
        "--output-dir", generatedSources.get().asFile.absolutePath,
        "--test-output-dir", generatedTestSources.get().asFile.absolutePath,
    )
    inputs.dir(layout.projectDirectory.dir("src/main/prompts"))
    outputs.dir(generatedSources)
    outputs.dir(generatedTestSources)
}

kotlin.sourceSets.main {
    kotlin.srcDir(generatedSources)
}

kotlin.sourceSets.test {
    kotlin.srcDir(generatedTestSources)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generatePrompts)
}

// --- IDE download for KtBlock compilation tests ---

// Each entry: product ID, channel, system property name for the unpacked IDE home
data class IdeDownloadSpec(
    val product: String,
    val channel: String,
    val systemProperty: String,
)

val ideDownloadSpecs = listOf(
    IdeDownloadSpec("idea", "stable", "mcp.steroid.ide.home"),
    IdeDownloadSpec("idea", "eap", "mcp.steroid.ide.eap.home"),
    IdeDownloadSpec("rider", "stable", "mcp.steroid.rider.home"),
    IdeDownloadSpec("rider", "eap", "mcp.steroid.rider.eap.home"),
    IdeDownloadSpec("clion", "stable", "mcp.steroid.clion.home"),
    IdeDownloadSpec("clion", "eap", "mcp.steroid.clion.eap.home"),
    IdeDownloadSpec("pycharm", "stable", "mcp.steroid.pycharm.home"),
    IdeDownloadSpec("pycharm", "eap", "mcp.steroid.pycharm.eap.home"),
)

/**
 * Optional filter: `-Pmcp.prompts.ide.filter=idea` limits IDE downloads and tests
 * to matching products. On TeamCity each IDE group runs in its own build config.
 * Comma-separated, e.g. `idea,rider`. Empty or absent → all IDEs.
 */
val ideFilter = providers.gradleProperty("mcp.prompts.ide.filter")
    .orElse("")
    .get()
    .split(",")
    .map { it.trim().lowercase() }
    .filter { it.isNotEmpty() }
    .toSet()

val filteredIdeDownloadSpecs = if (ideFilter.isEmpty()) {
    ideDownloadSpecs
} else {
    ideDownloadSpecs.filter { it.product in ideFilter }
}

val ideDownloadTasks = filteredIdeDownloadSpecs.map { spec ->
    val dirSuffix = "${spec.product}-${spec.channel}"
    val downloadDir = layout.buildDirectory.dir("ide-download-$dirSuffix")
    val unpackDir = layout.buildDirectory.dir("ide-unpack-$dirSuffix")

    val task = tasks.register("downloadAndUnpack-${dirSuffix}", JavaExec::class) {
        group = "verification"
        description = "Download and unpack ${spec.product} (${spec.channel}) for KtBlock compilation tests"
        classpath = ideDownloaderClasspath
        mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
        args(
            "--product", spec.product,
            "--channel", spec.channel,
            "--os", "linux",
            "--output-dir", downloadDir.get().asFile.absolutePath,
            "--unpack-dir", unpackDir.get().asFile.absolutePath,
        )
        outputs.dir(unpackDir)
    }

    Triple(spec, unpackDir, task)
}

/** Gradle task that prints the ideDownloadSpecs list for TC settings validation. */
tasks.register("listIdeDownloadSpecs") {
    group = "verification"
    description = "Print ideDownloadSpecs as product:channel lines for TC settings validation"
    doLast {
        for (spec in ideDownloadSpecs) {
            println("${spec.product}:${spec.channel}")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // On TeamCity, run single-threaded to avoid overwhelming the agent with
    // parallel IDE downloads + kotlinc compilations. Locally, use 8 cores.
    maxParallelForks = if (System.getenv("TEAMCITY_VERSION") != null) 1 else 8

    for ((_, _, task) in ideDownloadTasks) {
        dependsOn(task)
    }
    dependsOn(kotlincDist)

    doFirst {
        for ((spec, unpackDir, _) in ideDownloadTasks) {
            val dir = unpackDir.get().asFile
            val home = dir.listFiles()?.firstOrNull { it.isDirectory }
            if (home != null) {
                systemProperty(spec.systemProperty, home.absolutePath)
            }
        }

        val kotlincHome = kotlincDist.singleFile
        systemProperty("mcp.steroid.kotlinc.home", kotlincHome.absolutePath)

        // ij-plugin source directory for McpScriptContext/McpScriptBuilder sources
        val ijSources = rootProject.layout.projectDirectory
            .dir("ij-plugin/src/main/kotlin").asFile.absolutePath
        systemProperty("mcp.steroid.ij.sources", ijSources)

        // Compilation cache directory
        val cacheDir = layout.buildDirectory.dir("ktblock-cache").get().asFile.absolutePath
        systemProperty("mcp.steroid.ktblock.cache.dir", cacheDir)
    }
}
