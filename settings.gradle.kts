// Foojay disco-api resolver so Gradle can auto-download a matching JDK when
// the daemon toolchain criteria in gradle/gradle-daemon-jvm.properties can't
// be satisfied from discovered local JDKs. Required by `updateDaemonJvm` in
// Gradle 9.4+, which fails with "Toolchain download repositories have not
// been configured" without a resolver plugin on the settings classpath.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mcp-steroid"

include(":ai-agents")
include(":agent-output-filter")

include(":prompt-generator")
include(":kotlin-cli")
include(":prompts-api")
include(":prompts")
include(":intellij-downloader")

include(":ij-plugin")

include(":ocr-common")
include(":ocr-tesseract")

include(":test-helper")
include(":test-integration")
include(":test-experiments")

include(":npx")
include(":npx-kt")
