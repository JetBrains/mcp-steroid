import org.gradle.api.attributes.Usage

plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.posthog:posthog-server:2.3.0")

    // MCP transport: framed/NDJSON parser + McpStdioServer (replaces the old
    // StdioServer in this module — kept compiled but no longer wired into main()).
    implementation(project(":mcp-stdio"))

    // McpSteroidTools — registers the steroid_* tool surface on an McpServerCore.
    // Brings :mcp-core and :prompts transitively.
    implementation(project(":mcp-steroid-server"))

    // SLF4J binding for the launcher. We use Logback (not slf4j-simple) so
    // operators can drop in a `logback.xml` to add appenders, change levels,
    // or route specific loggers — slf4j-simple has no real configuration
    // surface. The bundled `logback.xml` (in src/main/resources) routes
    // everything to stderr only — never stdout, which is reserved for MCP
    // NDJSON frames.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    applicationName = "mcp-steroid-proxy"
    mainClass.set("com.jonnyzzz.mcpSteroid.proxy.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

// Dedicated source set for stdio MCP server integration tests
// (CliMcpStdioIntegrationTest). The tests spawn `bin/mcp-steroid-proxy` from the
// `installDist` output as a subprocess and exchange JSON-RPC frames over stdio.
// They are NOT part of the default `:npx-kt:test` run — invoke explicitly via
// `./gradlew :npx-kt:integrationTest`.
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output +
            sourceSets["test"].compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets["test"].runtimeClasspath
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "integrationTestImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(kotlin("test-junit5"))

    // Docker harness: ContainerDriver + buildDockerImage + AISessionBase, plus
    // DockerClaudeSession / DockerCodexSession / DockerGeminiSession used by
    // the Cli{Claude,Codex,Gemini}IntegrationTest classes.
    "integrationTestImplementation"(project(":test-helper"))
    // StdioMcpCommand + the agent MCP-add arg builders.
    "integrationTestImplementation"(project(":ai-agents"))
}

val installDistTask = tasks.named<Sync>("installDist")

tasks.register<Test>("integrationTest") {
    description = "Stdio MCP server integration tests (subprocess-driven). Requires installDist."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    dependsOn(installDistTask)
    // Path to the launcher script produced by installDist. The application plugin
    // names the install dir after `application.applicationName`, not the project,
    // so resolve it via the task itself rather than hard-coding the directory.
    val launcherProvider = installDistTask.map { sync ->
        sync.destinationDir.resolve("bin/${application.applicationName}")
    }
    inputs.file(launcherProvider)
    doFirst {
        systemProperty("npx.kt.launcher", launcherProvider.get().absolutePath)
    }
}

val npxKtPackageElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "npx-kt-package"))
    }
}

artifacts {
    add(npxKtPackageElements.name, tasks.distZip)
}

tasks.named("assemble") {
    dependsOn(tasks.distZip)
}
