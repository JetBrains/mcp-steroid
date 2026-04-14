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

// Extract MSVC runtime DLLs from JavaCPP's javacpp-*-windows-x86_64.jar for bundling.
// Tess4J's libtesseract551.dll and lept4j's libleptonica1850.dll are MSVC-compiled and
// depend on these runtime DLLs. The target Windows machine may not have VC++ Redistributable
// installed, so we bundle the DLLs in the distribution's native/ directory.
// See: https://learn.microsoft.com/en-us/cpp/windows/determining-which-dlls-to-redistribute
val extractMsvcRuntime by tasks.registering(Copy::class) {
    group = "build"
    description = "Extract MSVC runtime DLLs from JavaCPP for Windows distribution"
    val javacppJar = configurations.runtimeClasspath.get().files.find {
        it.name.contains("javacpp") && it.name.contains("windows-x86_64")
    }
    if (javacppJar != null) {
        from(zipTree(javacppJar)) {
            include("org/bytedeco/javacpp/windows-x86_64/msvcp140*.dll")
            include("org/bytedeco/javacpp/windows-x86_64/vcruntime140*.dll")
            include("org/bytedeco/javacpp/windows-x86_64/ucrtbase.dll")
            include("org/bytedeco/javacpp/windows-x86_64/concrt140.dll")
            include("org/bytedeco/javacpp/windows-x86_64/vcomp140.dll")
            // api-ms-win-*.dll forwarding DLLs needed by ucrtbase.dll on older Windows
            include("org/bytedeco/javacpp/windows-x86_64/api-ms-win-*.dll")
            eachFile { path = name } // flatten directory structure
        }
    }
    into(layout.buildDirectory.dir("msvc-runtime"))
    includeEmptyDirs = false
}

// Include tessdata and MSVC runtime in the distribution
distributions {
    main {
        contents {
            from(downloadTessdata) {
                into("tessdata")
            }
            from(extractMsvcRuntime) {
                into("native")
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
