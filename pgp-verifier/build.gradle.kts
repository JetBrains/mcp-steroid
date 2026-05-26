plugins {
    kotlin("jvm")
    application
}

group = "com.jonnyzzz.mcpSteroid"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.bouncycastle:bcpg-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test-junit5"))
}

application {
    applicationName = "pgp-verifier"
    mainClass.set("com.jonnyzzz.mcpSteroid.pgpVerifier.MainKt")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.installDist)

    doFirst {
        val installDir = tasks.installDist.get().destinationDir
        val launcher = installDir.resolve("bin").resolve(
            if (org.gradle.internal.os.OperatingSystem.current().isWindows) "pgp-verifier.bat" else "pgp-verifier"
        )
        systemProperty("pgpVerifier.test.launcher", launcher.absolutePath)
    }
}

// Expose the installed distribution to consumers via the same "install-dist"
// Usage attribute that :ocr-tesseract uses.
val installDistElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "install-dist"))
    }
}

artifacts {
    add(installDistElements.name, tasks.installDist.map { it.destinationDir }) {
        builtBy(tasks.installDist)
    }
}
