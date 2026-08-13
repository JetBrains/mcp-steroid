/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * One case of the keycloak-semantic-ripple family: a [RippleTarget] plus everything measured about
 * it at [SemanticRippleSpec.baseCommit] and everything the harness needs to run and grade it.
 *
 * Every count here was measured by PSI at that commit and is meaningless at any other. They are
 * asserted as tripwires before the agent runs: without them an index failure yields an empty gold
 * set, which would score as 100% recall over nothing.
 *
 * The case is carried into the harness as a [DpaiaTestCase] because that is what container setup,
 * the agent runner, the verifier and reporting already consume, so a ripple case reuses all of it
 * unchanged. The type name is inaccurate for a task that has nothing to do with the dpaia dataset;
 * renaming it would touch fifteen scenario tests and the prompt-contract tests, so it is a separate
 * change and deliberately not part of this one.
 */
data class RippleCase(
    val instanceId: String,
    val target: RippleTarget,
    /** Resolved references to the target at [SemanticRippleSpec.baseCommit]. */
    val expectedGoldReferences: Int,
    /** Distinct files holding those references at [SemanticRippleSpec.baseCommit]. */
    val expectedGoldFiles: Int,
    /** Declarations sharing the target's simple name, excluding the target's own owner. */
    val expectedDecoyDeclarations: Int,
    /**
     * The declaring module plus every module holding a reference — complete w.r.t. the ripple.
     *
     * These are Maven artifactIds, verified against the poms at [SemanticRippleSpec.baseCommit].
     * Selecting them needs [compileGateSelectors], not these strings.
     */
    val compileGateModules: List<String>,
    /** The module declaring the target. */
    val declaringModuleArtifactId: String,
    /** The module owning the hidden consumer. */
    val consumerModuleArtifactId: String,
    val hiddenConsumerFqn: String,
    val patchResource: String,
    val createdAt: String,
) {

    /**
     * [compileGateModules] in the form Maven's `-pl` actually understands.
     *
     * A `-pl` token without a colon is read as a relative DIRECTORY PATH, not as an artifactId, so
     * the bare names answer `Could not find the selected project in the reactor` and the gate never
     * runs at all — measured in both arms of build 1028521545, where every reference module was
     * therefore left unchecked.
     */
    fun compileGateSelectors(): List<String> = compileGateModules.map { ":$it" }

    /**
     * Maven arguments for the compile gate. `test-compile` rather than `compile` because references
     * live in test sources as well as main ones and `test-compile` compiles both; `-pl` without
     * `-am` because the harness prewarm already installed the siblings, and `-am` OOM-kills the
     * container.
     */
    fun compileGateArgs(): List<String> = listOf(
        "test-compile",
        "-P", SemanticRippleSpec.reactorProfile,
        "-pl", compileGateSelectors().joinToString(","),
        "-DskipTests",
    )

    /**
     * The `-pl` scope of the grading run: the consumer's module plus the module DECLARING the target.
     *
     * Two separate reasons, both measured. Scoping at all is required because Keycloak's default
     * reactor cannot be built to completion — `keycloak-quarkus-dist` depends on a `:zip` artifact
     * only the `distribution` profile produces, so a root `mvn test` stops there and skips every test
     * module behind it. Including the declaring module is required because without it the consumer
     * compiles against whatever jar happens to sit in the shared local repository, which is a mutable
     * leftover of earlier runs rather than the tree under test.
     *
     * Note also that a module's directory and its artifactId can disagree (`tests/base` is
     * `keycloak-tests-base`), which is why the grading run identifies its modules by these selectors
     * and not by the patch's directory. Distinct, because the two modules can be the same one.
     */
    fun gradingScopeSelector(): String =
        listOf(declaringModuleArtifactId, consumerModuleArtifactId).distinct().joinToString(",") { ":$it" }

    fun testPatch(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(patchResource)) {
            "Patch resource not found on the test classpath: $patchResource"
        }.use { it.readBytes().decodeToString() }

    /**
     * The files the hidden consumer adds. Their references to the transformed symbol resolve only
     * after the transformation, so they can never appear in the gold set and must not be charged
     * against reference conservation — see [parseSemanticPostcondition].
     */
    fun hiddenConsumerFiles(): Set<String> = extractPatchFilePaths(testPatch())

    /**
     * `passToPass` is empty on purpose: regression evidence for this task is the scoped compile gate,
     * not a list of tests, because a whole-suite baseline is not viable on a project this size.
     */
    fun dpaiaCase(): DpaiaTestCase = DpaiaTestCase(
        instanceId = instanceId,
        issueNumbers = emptyList(),
        tags = listOf("Refactoring", "SemanticRipple"),
        repo = "${SemanticRippleSpec.repoOwnerAndName}.git",
        patch = "",
        testPatch = testPatch(),
        failToPass = listOf(hiddenConsumerFqn),
        passToPass = emptyList(),
        createdAt = createdAt,
        baseCommit = SemanticRippleSpec.baseCommit,
        problemStatement = problemStatement(),
        version = "1",
        isMaven = true,
        buildSystem = "maven",
        testArgs = "",
    )

    /**
     * The task half of the prompt, reused as the case's problem statement so both the prompt and the
     * statement are covered by the same purity contract test.
     */
    fun problemStatement(): String =
        buildRipplePrompt(this, projectDir = "<project>", withMcp = false)
            .substringAfter("## Task")
            .substringBefore("## Environment Facts")
            .trim()
}
