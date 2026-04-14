import org.gradle.api.attributes.Usage

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

val npxPackage by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "npx-package"))
    }
}

dependencies {
    npxPackage(project(path = ":npx", configuration = "npxPackageElements"))
    implementation(project(":ai-agents"))
    implementation(project(":agent-output-filter"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // opentest4j on the main classpath so AIAgentCompanion.create() can throw
    // TestAbortedException to skip tests when an API key is missing on CI.
    implementation("org.opentest4j:opentest4j:1.3.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    from(npxPackage) {
        rename { "mcp-steroid-npx.zip" }
    }
}
