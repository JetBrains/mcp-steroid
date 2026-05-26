plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.promptgen.MainKt")
}

dependencies {
    implementation("com.squareup:kotlinpoet:2.2.0")
    implementation(project(":prompts-api"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
