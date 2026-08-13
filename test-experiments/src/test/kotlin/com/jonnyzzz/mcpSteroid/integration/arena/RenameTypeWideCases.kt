/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The wide rename-type case of the keycloak-semantic-ripple family, built in code rather than loaded
 * from the dpaia dataset.
 *
 * It is carried by [DpaiaTestCase] because that is what the whole arena harness — container setup,
 * agent runner, verifier, reporting — already consumes, so this case reuses all of it unchanged, the
 * same way [SemanticRippleCases] does.
 *
 * `passToPass` is empty on purpose: regression evidence for this task is the scoped compile gate,
 * not a list of tests, because a whole-suite baseline is not viable on a project this size.
 */
object RenameTypeWideCases {

    const val instanceId: String = "ripple__keycloak__rename-type-wide"

    const val hiddenConsumerFqn: String = "org.keycloak.validate.RenameTypeContractTest"

    private const val PATCH_RESOURCE = "arena-overlays/ripple-keycloak-rename-type-wide.patch"

    fun testPatch(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(PATCH_RESOURCE)) {
            "Patch resource not found on the test classpath: $PATCH_RESOURCE"
        }.use { it.readBytes().decodeToString() }

    /**
     * The files the hidden consumer adds. Their references to the new name resolve only after the
     * rename, so they can never appear in the gold set and must not be charged against reference
     * conservation — see [parseSemanticPostcondition].
     */
    fun hiddenConsumerFiles(): Set<String> = extractPatchFilePaths(testPatch())

    fun case(): DpaiaTestCase = DpaiaTestCase(
        instanceId = instanceId,
        issueNumbers = emptyList(),
        tags = listOf("Refactoring", "SemanticRipple"),
        repo = "${RenameTypeWideSpec.repoOwnerAndName}.git",
        patch = "",
        testPatch = testPatch(),
        failToPass = listOf(hiddenConsumerFqn),
        passToPass = emptyList(),
        createdAt = "2026-08-13T00:00:00Z",
        baseCommit = RenameTypeWideSpec.baseCommit,
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
        buildRenameTypeWidePrompt(projectDir = "<project>", withMcp = false)
            .substringAfter("## Task")
            .substringBefore("## Environment Facts")
            .trim()
}

/**
 * Fail the run before the agent starts when the captured world does not match the pinned
 * measurement. An index failure produces an empty gold set, which would otherwise score as a
 * perfect rename over nothing.
 *
 * A sibling of [SemanticGold.checkTripwires] rather than a call to it: that function's checks are
 * hardcoded against [SemanticRippleSpec]'s numbers, so calling it here would grade this case against
 * the pilot's target instead of this one. Duplicated on purpose — see the family's task-2 brief.
 */
fun SemanticGold.checkRenameTypeWideTripwires() {
    check(newNameDeclarations == 0) {
        "'${RenameTypeWideSpec.newName}' is already declared $newNameDeclarations times; the rename " +
            "target name must be free or the task is ill-posed"
    }
    check(totalReferences == RenameTypeWideSpec.expectedGoldReferences) {
        "Gold reference count is $totalReferences, expected " +
            "${RenameTypeWideSpec.expectedGoldReferences} at ${RenameTypeWideSpec.baseCommit}. Either " +
            "the commit moved or the index is incomplete."
    }
    check(files == RenameTypeWideSpec.expectedGoldFiles) {
        "Gold spans $files files, expected ${RenameTypeWideSpec.expectedGoldFiles}"
    }
    check(decoyReferences.size == RenameTypeWideSpec.expectedDecoyDeclarations) {
        "Found ${decoyReferences.size} decoy declarations named '${RenameTypeWideSpec.oldName}', " +
            "expected ${RenameTypeWideSpec.expectedDecoyDeclarations}"
    }
}
