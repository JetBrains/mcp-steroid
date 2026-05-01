import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.bundling.Zip

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
    group = "npx"
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
    group = "npx"
    npmCommand.set(listOf("run", "build"))
    dependsOn(tasks.npmInstall)
    inputs.files(fileTree(projectDir.resolve("src")))
    inputs.file(projectDir.resolve("esbuild.config.mjs"))
    inputs.file(projectDir.resolve("package.json"))
    inputs.file(projectDir.resolve("package-lock.json"))
    inputs.file(projectDir.resolve("tsconfig.json"))
    outputs.dir(projectDir.resolve("dist"))
}

val npmBuildTest = tasks.register<NpmTask>("npmBuildTest") {
    group = "npx"
    npmCommand.set(listOf("run", "build:test"))
    dependsOn(tasks.npmInstall)
}

val npmTest = tasks.register<NpmTask>("npmTest") {
    group = "npx"
    npmCommand.set(listOf("run", "test"))
    dependsOn(tasks.npmInstall)
}

val npxPackageZip = tasks.register<Zip>("npxPackageZip") {
    group = "npx"
    description = "Build distributable NPX package for integration tests"
    dependsOn(npmBuild, preparePackageFiles)

    archiveBaseName.set("mcp-steroid-npx")
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

val npxPackageElements = configurations.create("npxPackageElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "npx-package"))
    }
}

artifacts {
    add(npxPackageElements.name, npxPackageZip)
}

tasks.named("assemble") {
    dependsOn(npxPackageZip)
}

tasks.named("check") {
    dependsOn(npmTest)
}
