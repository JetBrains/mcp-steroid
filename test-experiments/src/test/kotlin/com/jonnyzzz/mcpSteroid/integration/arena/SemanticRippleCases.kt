/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The semantic-ripple pilot case, built in code rather than loaded from the dpaia dataset.
 *
 * It is carried by [DpaiaTestCase] because that is what the whole arena harness — container setup,
 * agent runner, verifier, reporting — already consumes, so the pilot reuses all of it unchanged.
 * The type name is inaccurate for a task that has nothing to do with the dpaia dataset; renaming it
 * to something neutral would touch fifteen scenario tests and the prompt-contract tests, so it is a
 * separate change and deliberately not part of this one. This file is the only place the
 * inaccuracy is load-bearing.
 *
 * `passToPass` is empty on purpose: regression evidence for this task is the scoped compile gate,
 * not a list of tests, because a whole-suite baseline is not viable on a project this size.
 */
object SemanticRippleCases {

    const val instanceId: String = "ripple__keycloak__realm-roles-rename"

    const val hiddenConsumerFqn: String =
        "org.keycloak.admin.client.resource.RealmResourceRenameContractTest"

    private const val PATCH_RESOURCE = "arena-overlays/semantic-ripple-keycloak-roles.patch"

    fun testPatch(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(PATCH_RESOURCE)) {
            "Patch resource not found on the test classpath: $PATCH_RESOURCE"
        }.use { it.readBytes().decodeToString() }

    fun pilotCase(): DpaiaTestCase = DpaiaTestCase(
        instanceId = instanceId,
        issueNumbers = emptyList(),
        tags = listOf("Refactoring", "SemanticRipple"),
        repo = "${SemanticRippleSpec.repoOwnerAndName}.git",
        patch = "",
        testPatch = testPatch(),
        failToPass = listOf(hiddenConsumerFqn),
        passToPass = emptyList(),
        createdAt = "2026-08-11T00:00:00Z",
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
        buildSemanticRipplePrompt(projectDir = "<project>", withMcp = false)
            .substringAfter("## Task")
            .substringBefore("## Environment Facts")
            .trim()
}
