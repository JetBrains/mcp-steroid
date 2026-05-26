import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.bundling.Zip
import java.util.SortedSet

plugins {
    base
    id("com.github.node-gradle.node")
}

node {
    download.set(true)
    version.set("20.20.0")
}

val packageVersion = version.toString()
val preparePackageFiles = tasks.register("preparePackageFiles") {
    group = "devrig"
    description = "Copy package.json and package-lock.json to build/package/ with patched version"
    dependsOn(tasks.npmInstall)

    val sourcePackageJson = projectDir.resolve("package.json")
    val sourceLockJson = projectDir.resolve("package-lock.json")
    val outputDir = layout.buildDirectory.dir("package")

    inputs.property("projectVersion", packageVersion)
    inputs.file(sourcePackageJson)
    inputs.file(sourceLockJson)
    outputs.dir(outputDir)

    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()

        // Patch package.json version
        val pkgJson = sourcePackageJson.readText()
        dir.resolve("package.json").writeText(
            pkgJson.replace(
                Regex(""""version"\s*:\s*"[^"]*""""),
                """"version": "$packageVersion""""
            )
        )

        // Patch package-lock.json version (appears in two places)
        val lockJson = sourceLockJson.readText()
        dir.resolve("package-lock.json").writeText(
            lockJson.replace(
                Regex(""""version"\s*:\s*"0\.0\.0-dev""""),
                """"version": "$packageVersion""""
            )
        )
    }
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    group = "devrig"
    npmCommand.set(listOf("run", "build"))
    dependsOn(tasks.npmInstall)
    inputs.files(fileTree(projectDir.resolve("src")))
    inputs.file(projectDir.resolve("esbuild.config.mjs"))
    inputs.file(projectDir.resolve("package.json"))
    inputs.file(projectDir.resolve("package-lock.json"))
    inputs.file(projectDir.resolve("tsconfig.json"))
    outputs.dir(projectDir.resolve("dist"))
}

val npmTest = tasks.register<NpmTask>("npmTest") {
    group = "devrig"
    npmCommand.set(listOf("run", "test"))
    dependsOn(tasks.npmInstall)
}

val devrigNpmPackageZip = tasks.register<Zip>("devrigNpmPackageZip") {
    group = "devrig"
    description = "Build distributable devrig npm package for integration tests"
    dependsOn(npmBuild, preparePackageFiles)

    archiveBaseName.set("devrig-npm")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(layout.buildDirectory.dir("package")) {
        include("package.json")
        include("package-lock.json")
    }
    from(projectDir.resolve("dist")) {
        into("dist")
    }
    from(projectDir) {
        include("LICENSE")
    }
}

val devrigNpmPackageElements = configurations.create("devrigNpmPackageElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "devrig-npm-package"))
    }
}

artifacts {
    add(devrigNpmPackageElements.name, devrigNpmPackageZip)
}

tasks.named("assemble") {
    dependsOn(devrigNpmPackageZip)
}

// Locks down what `devrigNpmPackageZip` ships so a stray `from(...)` block in this build script
// or a renamed dist artefact can't silently change what the npm devrig package installs.
// Modelled on :ij-plugin's `verifyBundledLibraries`. Update `expectedFiles` when the
// change is intentional.
val verifyPackageFiles = tasks.register("verifyPackageFiles") {
    group = "verification"
    description = "List and verify files bundled in the devrig npm package zip"
    dependsOn(devrigNpmPackageZip)
    doLast {
        val zip = devrigNpmPackageZip.get().outputs.files.singleFile

        val allFiles: SortedSet<String> = run {
            val collected = mutableListOf<String>()
            zipTree(zip).visit {
                if (!isDirectory) {
                    val path = relativePath.pathString
                    collected += if (permissions.user.execute) "$path:X" else path
                }
            }
            collected
        }.toSortedSet()

        val expectedFiles = sortedSetOf(
            "LICENSE",
            "package.json",
            "package-lock.json",
            "dist/index.js",
        ).toSortedSet()

        if (allFiles != expectedFiles) {
            val missing = expectedFiles - allFiles
            val unexpected = allFiles - expectedFiles
            throw GradleException(buildString {
                appendLine("Bundled files mismatch in :npx devrig package zip!")
                if (missing.isNotEmpty()) {
                    appendLine("Missing entries:")
                    missing.forEach { appendLine("  - $it") }
                }
                if (unexpected.isNotEmpty()) {
                    appendLine("Unexpected entries:")
                    unexpected.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("Actual entries:")
                allFiles.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Update `expectedFiles` in npx/build.gradle.kts if this change is intentional.")
            })
        }
    }
}

devrigNpmPackageZip.configure {
    finalizedBy(verifyPackageFiles)
}

tasks.named("check") {
    dependsOn(npmTest)
    dependsOn(verifyPackageFiles)
}
