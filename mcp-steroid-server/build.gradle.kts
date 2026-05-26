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
    implementation("org.slf4j:slf4j-api:2.0.13")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // MCP protocol types (McpTool, ToolCallContext, JsonRpc*, PromptsContextProvider)
    api(project(":mcp-core"))

    // Generated prompt articles for tool descriptions + FetchResourceToolHandler
    api(project(":prompts"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
