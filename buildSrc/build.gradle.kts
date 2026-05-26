plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

// Pull in the shared IDE compatibility matrix from `:intellij-downloader`
// (single source of truth — see `docs/262-EAP-PLAN.md`). Both this buildSrc
// classpath and the `:intellij-downloader` runtime classpath compile the
// same .kt file; the shared folder is dependency-free so buildSrc can
// compile it without pulling additional libraries.
sourceSets.main {
    kotlin.srcDir("../intellij-downloader/src/buildsrc-shared/kotlin")
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation("com.squareup:kotlinpoet:2.2.0")
    // The buildsrc-shared/kotlin srcDir we pull from :intellij-downloader carries
    // the full IDE-resolution / download / unpack library (the matrix + the
    // LocalIdeProvisioner used by ij-plugin/build.gradle.kts). The transitive
    // deps below mirror :intellij-downloader/build.gradle.kts; both compilations
    // load the same source files but each maintains its own classpath.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.tukaani:xz:1.10")
    testImplementation(kotlin("test"))
}
