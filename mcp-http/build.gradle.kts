plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":mcp-core"))
    implementation("io.ktor:ktor-server-core:3.1.0")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:3.1.0")
    testImplementation("io.ktor:ktor-server-cio:3.1.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}
