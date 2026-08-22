plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

// Resolvable configuration to get the plugin .zip from :ij-plugin subproject
val pluginZip = configurations.create("pluginZip") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

// Resolvable configuration to get the agent-output-filter executable distribution zip
val agentOutputFilterDist = configurations.create("agentOutputFilterDist") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Resolvable configuration to get the Kotlin devrig CLI distribution zip from :npx-kt.
val devrigPackageDist = configurations.create("devrigPackageDist") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "devrig-package"))
    }
}

dependencies {
    pluginZip(project(":ij-plugin"))
    agentOutputFilterDist(project(path = ":agent-output-filter", configuration = "executableDistribution"))
    devrigPackageDist(project(":npx-kt"))

    // Shared infrastructure (containers, MCP client, drivers) lives in :test-integration's main source set.
    testImplementation(project(":test-integration"))
    testImplementation(project(":test-helper"))
    testImplementation(project(":agent-output-filter"))
    testImplementation(project(":ai-agents"))
    testImplementation(project(":intellij-downloader"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

// Docker images and test fixture projects live in :test-integration; we point at them via system property.
val sharedDockerDir = project(":test-integration").projectDir.resolve("src/test/docker")

/**
 * Applies shared configuration to any experimental integration test task:
 * classpath, logging, timeout, artifact dependencies, and common system properties.
 */
fun Test.configureExperimentalTest() {
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    // Forward claude.comparison.* system properties from the Gradle JVM to the test JVM.
    System.getProperties()
        .filterKeys { it.toString().startsWith("claude.comparison.") }
        .forEach { (key, value) -> systemProperty(key.toString(), value.toString()) }

    // Forward arena.test.* system properties (used by DpaiaArenaTest).
    System.getProperties()
        .filterKeys { it.toString().startsWith("arena.test.") }
        .forEach { (key, value) -> systemProperty(key.toString(), value.toString()) }

    // Forward exact model-selection / pass-labeling system properties (used by DockerClaudeSession,
    // DockerCodexSession, and arena pass reporting) — these are precise keys, not a prefix family.
    // `ripple.survey.phases` selects which measurements KeycloakRippleTargetSurveyTest performs, so a
    // locked slot is not re-measured at the cost of the IDE's whole budget.
    // `ripple.checkpoint.*` address ONE cell of the solution-readiness pilot's grid (arm x checkpoint x
    // replicate). They matter more than they look: the 50 probe builds differ ONLY by these three values,
    // so a key that fails to arrive does not fail a build — it silently re-measures one cell 50 times.
    // `test.integration.ide.vm.xmx` caps the guest IDE's heap (read in `intelliJ.kt`'s `generateVmOptions`).
    // The keycloak-semantic TC builds have been passing it in `gradleParams` since the 6g default let the
    // IDE and the agent's own Maven build exhaust the Docker VM — but it was never forwarded here, so the
    // guest IDE kept starting with the default heap and the OOM protection those builds document was
    // declarative only.
    // Each key also has an environment-variable spelling, because TeamCity's Gradle runner does NOT put
    // `system.*` build parameters on the Gradle command line for these builds: a `-Sclaude.model=…`
    // override is silently ignored and the run measures the DEFAULT model instead (verified on builds
    // 1032824130 / 1032824136, which reported `--model claude-opus-5` / `--model gpt-5.6-sol` under an
    // explicit `-S` override). `env.*` build parameters DO reach the step environment — that is how the
    // agent API keys already arrive — so an unattended TC run selects its model via `-E CLAUDE_MODEL=…`.
    // The system property wins when both are present, so a local `-Dclaude.model=…` keeps working.
    mapOf(
        "claude.model" to "CLAUDE_MODEL",
        "codex.model" to "CODEX_MODEL",
        "arena.pass.label" to "ARENA_PASS_LABEL",
        "ripple.survey.phases" to "RIPPLE_SURVEY_PHASES",
        "ripple.checkpoint.arm" to "RIPPLE_CHECKPOINT_ARM",
        "ripple.checkpoint.index" to "RIPPLE_CHECKPOINT_INDEX",
        "ripple.checkpoint.replicate" to "RIPPLE_CHECKPOINT_REPLICATE",
        // `understanding.*` address ONE cell of the repository-understanding experiment: the research
        // phase takes case + arm + budget + noteLimit + replicate, the downstream phase takes case +
        // condition + replicate. Same hazard as the checkpoint keys and a worse one — a research budget
        // that fails to arrive does not fail the build, it silently runs the cell with the DEFAULT
        // budget, and the resulting note would be published under a budget it never had.
        "understanding.case" to "UNDERSTANDING_CASE",
        "understanding.arm" to "UNDERSTANDING_ARM",
        "understanding.budget" to "UNDERSTANDING_BUDGET",
        "understanding.noteLimit" to "UNDERSTANDING_NOTE_LIMIT",
        "understanding.condition" to "UNDERSTANDING_CONDITION",
        "understanding.replicate" to "UNDERSTANDING_REPLICATE",
        // `acquisition.recompute.dir` points the offline re-reader at transcripts a paid run already
        // published. It buys nothing at run time and everything afterwards: when the instrument turns
        // out to have been wrong — as its token axis was, once — the curves are recomputed from the
        // files instead of from a new round of Opus trajectories.
        "acquisition.recompute.dir" to "ACQUISITION_RECOMPUTE_DIR",
        "test.integration.ide.vm.xmx" to "TEST_INTEGRATION_IDE_VM_XMX",
    ).forEach { (key, envName) ->
        val value = System.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
        value?.let { systemProperty(key, it) }
    }

    dependsOn(pluginZip, agentOutputFilterDist, devrigPackageDist)
    doFirst {
        delete(layout.buildDirectory.dir("test-results/${this@configureExperimentalTest.name}/binary"))
        val testOutDir = layout.buildDirectory
            .dir("test-logs/${this@configureExperimentalTest.name}").get().asFile
            .also { it.mkdirs() }

        val resolvedPluginZip = pluginZip.singleFile
        require(resolvedPluginZip.isFile) { "Plugin ZIP not found: ${resolvedPluginZip.absolutePath}" }

        systemProperty("test.integration.plugin.zip", resolvedPluginZip.absolutePath)
        // Root-shared (same convention as test.integration.dependency.cache.dir): the integration and
        // experiments suites reuse ONE IDE-archive cache instead of downloading per module.
        systemProperty(
            "test.integration.ide.download.dir",
            rootProject.layout.buildDirectory.dir("ide-download").get().asFile.absolutePath,
        )
        require(sharedDockerDir.isDirectory) {
            "Shared docker dir not found: ${sharedDockerDir.absolutePath}"
        }
        systemProperty("test.integration.docker", sharedDockerDir.absolutePath)
        systemProperty("test.integration.testOutput", testOutDir.absolutePath)
        systemProperty(
            "test.integration.agent.output.filter.zip",
            agentOutputFilterDist.singleFile.absolutePath,
        )
        systemProperty(
            "test.integration.devrig.package.zip",
            devrigPackageDist.singleFile.absolutePath,
        )
        systemProperty(
            "test.integration.repo.cache.dir",
            layout.buildDirectory.dir("repo-cache").get().asFile.absolutePath,
        )
        // Persisted container Maven (~/.m2) + Gradle (~/.gradle) caches, shared (root build dir) with
        // :test-integration — the two suites never run concurrently — so deps + sources download once and
        // are reused across runs. See IdeTestFolders.dependencyCacheVolumes.
        systemProperty(
            "test.integration.dependency.cache.dir",
            rootProject.layout.buildDirectory.dir("test-dependency-cache").get().asFile.absolutePath,
        )

        // Build-compatibility test: persistent caches so IDE downloads and Gradle state survive across runs
        val buildCompatDir = layout.buildDirectory.dir("build-compat").get().asFile
        systemProperty(
            "test.integration.build.compat.gradle.home",
            File(buildCompatDir, "gradle-home").also { it.mkdirs() }.absolutePath,
        )
        systemProperty(
            "test.integration.build.compat.ij.platform",
            File(buildCompatDir, "intellij-platform").also { it.mkdirs() }.absolutePath,
        )
    }
}

/**
 * The capture classes of the solution-readiness pilot, keyed by the case they record.
 *
 * The first two are the pilot's own: `feature-service-125` is what rounds 1 and 2 measured (its
 * solution is a set of independently landable parts, so readiness can rise along the trajectory, and
 * its Testcontainers oracle really runs in the arena container) and `rename-method-wide` is kept as the
 * already-measured second case, whose one atomic edit makes its curve a step function.
 *
 * The other six are round 3's, added because a curve measured on ONE case cannot be told apart from a
 * property of that case. They mirror `RippleCheckpointCases.ALL` — the registry the probe side resolves
 * an arm token through — and the keys are its `resourceDir` values.
 *
 * `RippleCheckpointCaptureFilterTest` loads every class and method named here by reflection, because a
 * rename on either side of this mapping would otherwise produce a filter matching nothing — and a
 * capture build that matches nothing does not fail, it runs whatever the configuration's own filter
 * selects.
 */
val checkpointCaptureClasses = mapOf(
    "feature-service-125" to "DpaiaFeatureService125CheckpointCaptureTest",
    "rename-method-wide" to "KeycloakRenameMethodWideCheckpointCaptureTest",
    "petclinic-71" to "DpaiaPetclinic71CheckpointCaptureTest",
    "petclinic-rest-37" to "DpaiaPetclinicRest37CheckpointCaptureTest",
    "petclinic-36" to "DpaiaPetclinic36CheckpointCaptureTest",
    "springboot3-1" to "DpaiaSpringboot31CheckpointCaptureTest",
    "jhipster-3" to "DpaiaJhipster3CheckpointCaptureTest",
    "feature-service-25" to "DpaiaFeatureService25CheckpointCaptureTest",
)

val checkpointCaptureMethods = listOf("hookPreflight", "captureMcpArm", "captureShellArm")

val checkpointCaptureMethodProperty = "ripple.checkpoint.capture.method"
val checkpointCaptureCaseProperty = "ripple.checkpoint.capture.case"

tasks.test {
    configureExperimentalTest()

    // Project-property-driven filter: `-PtestFilter=*MyTest` is equivalent to
    // `--tests '*MyTest'` but works reliably under TC's gradle runner where
    // `--tests` placed in `gradleParams` gets emitted BEFORE the task name and
    // detached from it. Applied programmatically so no CLI parsing is involved.
    project.findProperty("testFilter")?.toString()?.let { pattern ->
        filter { includeTestsMatching(pattern) }
    }

    // The capture side of the pilot is selected by ONE parameter, mapped to a filter here. Every
    // invalid value is a hard configuration failure rather than a default: a capture build costs a full
    // Opus run, and the failure mode this replaces is silent — Gradle ignores an unknown `-P`, so a
    // misspelled method used to leave the build running whatever else the configuration selected.
    //
    // The value is `<case>:<method>`, and a bare `<method>` still means the default case. The case
    // BELONGS in this parameter because it is the only one that arrives: the TeamCity configuration
    // templates its Gradle step as `-P<method property>=%…%` and nothing else, so round 3's first
    // twelve capture builds were started with `-P$checkpointCaptureCaseProperty` set as a TeamCity
    // parameter, saw it dropped on the way to Gradle, and all recorded the DEFAULT case instead of the
    // six they were queued for. Adding the coordinate to the forwarded parameter keeps the selection in
    // this repository, where it is unit-tested, instead of in a build configuration in another one.
    //
    // $checkpointCaptureCaseProperty is still honoured, so an operator running Gradle by hand can pass
    // it separately — but the two must AGREE. Disagreement is a configuration failure and never a
    // precedence rule: a silent winner here spends an Opus run on the wrong repository.
    project.findProperty(checkpointCaptureMethodProperty)?.toString()?.takeIf { it.isNotBlank() }
        ?.let { selector ->
            val separator = selector.indexOf(':')
            val method = if (separator < 0) selector else selector.substring(separator + 1)
            val selectedCase = if (separator < 0) null else selector.substring(0, separator)
            require(method in checkpointCaptureMethods) {
                "-P$checkpointCaptureMethodProperty=$selector selects the method '$method', " +
                    "which is not a capture method; expected one of $checkpointCaptureMethods, " +
                    "optionally prefixed with '<case>:'"
            }
            val propertyCase = project.findProperty(checkpointCaptureCaseProperty)?.toString()
                ?.takeIf { it.isNotBlank() }
            require(selectedCase == null || propertyCase == null || selectedCase == propertyCase) {
                "-P$checkpointCaptureMethodProperty=$selector selects the case '$selectedCase' but " +
                    "-P$checkpointCaptureCaseProperty=$propertyCase selects '$propertyCase'; " +
                    "pass one or make them agree"
            }
            val case = selectedCase ?: propertyCase ?: "feature-service-125"
            val captureClass = checkpointCaptureClasses[case]
                ?: error(
                    "-P$checkpointCaptureMethodProperty=$selector names the case '$case', which has " +
                        "no capture class; expected one of ${checkpointCaptureClasses.keys}"
                )
            filter { includeTestsMatching("*$captureClass.$method") }
        }

    // Prevent this task from being silently triggered by root-level './gradlew test' aggregation.
    // Experimental integration tests require Docker, API keys, and IDE containers — invoke explicitly.
    //
    // Correct usage:
    //   ./gradlew :test-experiments:test --tests '*DebuggerDemoTest.claude*'
    //   ./gradlew :test-experiments:test -PtestFilter='*DebuggerDemoTest.claude*'   (CI-friendly)
    //   ./gradlew :test-experiments:test --tests '*DpaiaArenaTest*' -Darena.test.instanceId=<id>
    onlyIf("Requires explicit :test-experiments: task invocation — not for root aggregation") {
        gradle.startParameter.taskNames.any { it.contains(":test-experiments:") }
    }
}
