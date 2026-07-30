// Foojay disco-api resolver so Gradle can auto-download a matching JDK when
// the daemon toolchain criteria in gradle/gradle-daemon-jvm.properties can't
// be satisfied from discovered local JDKs. Required by `updateDaemonJvm` in
// Gradle 9.4+, which fails with "Toolchain download repositories have not
// been configured" without a resolver plugin on the settings classpath.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mcp-steroid"

// Remote Gradle build cache — https://buildfetch.com/ — shared by GitHub Actions,
// TeamCity, and developer machines. `org.gradle.caching=true` (gradle.properties)
// switches caching on; this block only adds the remote node on top of the local one.
//
// Token: set BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN as an env var (CI) or in
// ~/.gradle/gradle.properties (best for mixed IDE & terminal use). Without a
// token the remote node stays disabled and builds fall back to the local cache
// only — contributors without credentials are never blocked.
buildCache {
    remote<HttpBuildCache> {
        url = uri("https://cache.eu-central-a.buildfetch.com/pOImKP/gradle/")

        credentials {
            username = "token-auth"
            // `takeIf isNotBlank`: on GH Actions, `${{ secrets.X }}` in a fork PR
            // resolves to an EMPTY string (not unset) — a blank password must mean
            // "no remote cache", not "authenticate with empty credentials".
            password = "BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN".let {
                providers.environmentVariable(it).orElse(providers.gradleProperty(it)).orNull
            }?.takeIf { it.isNotBlank() }
        }

        // BuildFetch recommends cache writes from CI only (reproducible environment).
        // GitHub Actions sets CI=true; TeamCity sets TEAMCITY_VERSION but not CI.
        isPush = providers.environmentVariable("CI").isPresent ||
                providers.environmentVariable("TEAMCITY_VERSION").isPresent

        isEnabled = credentials.password != null
    }
}

// On Windows hosts: pre-materialize the bundled 7-Zip Windows binaries before any
// project is configured, so LocalIdeProvisioner's config-phase .exe unpack has the
// extractor on disk via SevenZipLocator's system-property hook. Mac/Linux config
// phases unpack the IDE via .tar.gz / .dmg and never hit the .exe path.
if (System.getProperty("os.name").lowercase().contains("win")) {
    apply(from = "gradle/seven-zip-bootstrap.settings.gradle.kts")
}

include(":ai-agents")
include(":agent-output-filter")
include(":closeable-stack")

include(":prompt-generator")
include(":kotlin-cli")
include(":prompts-api")
include(":prompts")
include(":intellij-downloader")

include(":ij-plugin")
include(":mcp-core")
include(":mcp-http")
include(":mcp-stdio")
include(":mcp-steroid-server")
include(":execution-storage")

include(":ocr-common")
include(":ocr-tesseract")

include(":test-helper")
include(":test-integration")
include(":test-integration-agent-launch")
include(":test-experiments")

include(":npx")
include(":npx-kt")

include(":installer-gen")
include(":website-gen")

include(":experiments-report")
