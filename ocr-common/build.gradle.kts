plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    val kotlinxSerialization = providers.gradleProperty("mcp.kotlinx.serialization.version").get()
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerialization")
}

kotlin {
    jvmToolchain(25)
}
