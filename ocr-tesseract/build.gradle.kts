import de.undercouch.gradle.tasks.download.Download
import org.gradle.kotlin.dsl.register

plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("de.undercouch.download")
}

repositories {
    mavenCentral()
}

// Version constants - tessdata version should be compatible with tesseract version
val tess4jVersion = "5.17.0"
val lept4jVersion = "1.21.1"
val tesseractPlatformVersion = "5.5.1-1.5.12"
val leptonicaPlatformVersion = "1.85.0-1.5.12"
val tessdataVersion = "4.1.0" // tessdata 4.1.0 is compatible with Tesseract 4.x and 5.x

dependencies {
    implementation(project(":ocr-common"))
    implementation("net.sourceforge.tess4j:tess4j:$tess4jVersion") {
        exclude(group = "net.sourceforge.lept4j", module = "lept4j")
    }
    implementation("net.sourceforge.lept4j:lept4j:$lept4jVersion")
    implementation("org.bytedeco:tesseract-platform:$tesseractPlatformVersion")
    implementation("org.bytedeco:leptonica-platform:$leptonicaPlatformVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "ocr-tesseract"
    mainClass.set("com.jonnyzzz.mcpSteroid.ocr.app.OcrCliKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Run against the installed distribution — matches production deployment and
    // ensures native libraries (Tesseract/Leptonica) are in the correct directory
    // structure for JNA/JavaCPP to load them on all platforms.
    dependsOn(tasks.installDist)
    doFirst {
        val installDir = tasks.installDist.get().destinationDir
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val launcher = if (isWindows) {
            installDir.resolve("bin/ocr-tesseract.bat")
        } else {
            installDir.resolve("bin/ocr-tesseract")
        }
        systemProperty("ocr.test.launcher", launcher.absolutePath)
        systemProperty("ocr.test.install.dir", installDir.absolutePath)
    }
}

// Tessdata download configuration
val tessdataDownloadDir = layout.buildDirectory.dir("tessdata-download")
val tessdataDir = layout.buildDirectory.dir("tessdata-data")
val downloadConnectTimeoutMs = 30_000
val downloadReadTimeoutMs = 15 * 60_000
val downloadRetryCount = 5

fun Download.configureReliableDownload() {
    onlyIfModified(true)
    connectTimeout(downloadConnectTimeoutMs)
    readTimeout(downloadReadTimeoutMs)
    retries(downloadRetryCount)
}

// Download tessdata files
val downloadTessdata by tasks.registering {
    outputs.dir(tessdataDownloadDir)
}

listOf(
    "https://github.com/tesseract-ocr/tessdata/raw/$tessdataVersion/eng.traineddata",
    "https://github.com/tesseract-ocr/tessdata/raw/$tessdataVersion/osd.traineddata",
).forEach { url ->
    val task = tasks.register<Download>("download_" + url.substringAfterLast("/").substringBefore(".")) {
        src(url)
        dest(tessdataDownloadDir)
        configureReliableDownload()
    }
    downloadTessdata.configure { dependsOn(task) }
}

// Include tessdata in the distribution
distributions {
    main {
        contents {
            //dependeny to the task behind   tessdataDownloadDir
            from(downloadTessdata) {
                into("tessdata")
            }
        }
    }
}

// Create a configuration that exposes the installed distribution
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
