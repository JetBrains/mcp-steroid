package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer

/**
 * Compile the project via bash (not IntelliJ build).
 *
 * For Maven: `./mvnw test-compile -Dspotless.check.skip=true`
 * For Gradle: `./gradlew testClasses`
 * For NONE: skip
 *
 * Runs inside the container with the configured JAVA_HOME when [javaHomeVersion] is set.
 * This is a pre-agent warmup step — ensures all sources compile and deps are downloaded.
 */
@Deprecated("Implementation is not correct, use MCP Steroid and IntelliJ for that")
fun McpSteroidDriver.mcpCompileProject(buildSystem: BuildSystem, javaHomeVersion: String? = "21") {
    //TODO: the implementation is not correct, use MCP Steroid and IntelliJ for that

    if (buildSystem == BuildSystem.NONE) {
        //TODO: absolutely incorrect, and build is not executed!
        println("[COMPILE] Build system is NONE — skipping compilation")
        return
    }

    val javaHome = javaHomeVersion?.let { jdkVersion ->
        driver.startProcessInContainer {
            this.args(
                "bash",
                "-c",
                $$"""
                    for dir in /usr/lib/jvm/temurin-$$jdkVersion-* /usr/lib/jvm/java-$$jdkVersion-* /usr/lib/jvm/corretto-$$jdkVersion-*; do
                      if [ -x "$dir/bin/javac" ]; then
                        printf '%s\n' "$dir"
                        exit 0
                      fi
                    done
                    printf 'JDK $$jdkVersion not found under /usr/lib/jvm\n' >&2
                    exit 1
                    """.trimIndent(),
            )
                .timeoutSeconds(5)
                .description("Find JDK $jdkVersion path")
        }.awaitForProcessFinish().resolveJavaHomeLookup(jdkVersion)
    }
    println("[COMPILE] JAVA_HOME=$javaHome, buildSystem=$buildSystem")

    val command = when (buildSystem) {
        BuildSystem.MAVEN -> "./mvnw test-compile -DskipTests -Dspotless.check.skip=true -B -q"
        BuildSystem.GRADLE -> "./gradlew testClasses --console=plain -q"
        BuildSystem.NONE -> return
    }
    println("[COMPILE] Running: $command")

    val compileResult = driver.startProcessInContainer {
        this
            .args("bash", "-c", "${if (javaHome == null) "" else "export JAVA_HOME=$javaHome && "}$command")
            .workingDirInContainer(ijDriver.getGuestProjectDir())
            .timeoutSeconds(600)
            .description("Compile project ($buildSystem)")
    }.awaitForProcessFinish()

    if (compileResult.exitCode == 0) {
        println("[COMPILE] Compilation complete")
    } else {
        println("[COMPILE] WARNING: Compilation failed (exit=${compileResult.exitCode}) — continuing anyway")
        println("[COMPILE] stderr: ${compileResult.stderr.take(500)}")
        println("[COMPILE] stdout: ${compileResult.stdout.take(500)}")
    }
}
