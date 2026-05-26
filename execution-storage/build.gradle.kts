plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // ExecCodeParams / FeedbackParams — the parameter records that drive the
    // execute_code and execute_feedback storage flows. Pulled from the
    // transport-agnostic server module so :execution-storage keeps zero
    // IntelliJ dependencies.
    api(project(":mcp-steroid-server"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
