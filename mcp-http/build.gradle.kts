plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.slf4j:slf4j-api:2.0.13")

    val kotlinxSerialization = providers.gradleProperty("mcp.kotlinx.serialization.version").get()
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerialization")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:$kotlinxSerialization")

    // MCP protocol types, session manager, registries
    api(project(":mcp-core"))

    // Ktor server — the HTTP transport that speaks MCP Streamable HTTP
    val ktorVersion = "3.3.2"
    api("io.ktor:ktor-server-core:$ktorVersion")
    api("io.ktor:ktor-server-cio:$ktorVersion")
    api("io.ktor:ktor-server-sse:$ktorVersion")

    // Ktor client for the in-process MCP HTTP integration test
    testImplementation("io.ktor:ktor-client-core:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
