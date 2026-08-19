# Keycloak Semantic-Ripple Pilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run one controlled cross-module rename on Keycloak through the existing arena A/B harness, graded by a PSI post-condition oracle, so the harness is shown to separate the `mcp` arm from the `none` arm on a task with real semantic pressure.

**Architecture:** The pilot task is a synthetic `DpaiaTestCase` built in code rather than loaded from the dpaia dataset, so the existing container/agent/reporting machinery is reused unchanged. A new oracle layer captures a gold reference set by PSI query before the agent runs and re-queries after, asserting four post-conditions that tests alone cannot express. Every PSI script prints a line-oriented text format parsed by pure functions, so all grading logic is unit-testable without Docker.

**Tech Stack:** Kotlin 2.3.20, JUnit 5 (`org.junit.jupiter`), Gradle 9.6.1, IntelliJ Platform PSI APIs via `steroid_execute_code`, Docker-in-test via `:test-experiments` infrastructure.

**Spec:** `docs/superpowers/specs/2026-08-11-keycloak-semantic-ripple-pilot-design.md` — read it before starting. Neither the spec nor this plan is committed to git.

## Global Constraints

- Base commit is pinned to `60c4d5e9321ff5462a772ceb896f8cb2e639e04b`. Every measured constant below is valid only at that commit.
- Rename target: `org.keycloak.admin.client.resource.RealmResource.roles()` returning `RolesResource`. New name: `realmLevelRoles`. Do not use `realmRoles` — it is already declared 5 times in the project.
- Expected gold: **445** resolved references, **79** files, **6** reference modules, **16** decoy declarations named `roles`.
- Compile-gate modules (7): `keycloak-admin-client-core`, `integration-arquillian-tests-base`, `keycloak-admin-v2-tests`, `keycloak-authzen-tests-base`, `keycloak-test-framework-tests`, `keycloak-tests-base`, `keycloak-tests-utils`.
- Every new file starts with the repo copyright header: `/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */`
- Package for all new Kotlin files: `com.jonnyzzz.mcpSteroid.integration.arena`.
- Banned in this repo: the `internal` modifier; `runCatching{}.onFailure{}`; empty or silent `catch`; returning a `(value, errorFlag)` pair; `@Suppress("DEPRECATION")`; `ProcessBuilder("mvn")` inside `steroid_execute_code`; `-am` in any Maven invocation.
- Never run two Docker tests concurrently. Any `:test-experiments` Docker test runs alone.
- Commit messages state what and why. Never mention AI or add an AI co-author.
- Unit tests run as `./gradlew :test-experiments:test --tests '<pattern>'` and must finish in seconds. Docker tests are invoked one method at a time with `--rerun-tasks`.

---

### Task 1: Keycloak prewarm probe — the go/no-go gate

This task exists to kill the project cheaply if the infrastructure cannot host Keycloak. It writes no code that a later task throws away: the probe stays as the smoke test for the track.

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRipplePrewarmProbeTest.kt`

**Interfaces:**
- Consumes: `IntelliJContainer.create`, `IntelliJContainerOpts`, `IntelliJProject.ProjectFromGitCommitAndPatch`, `waitForProjectReady`, `CloseableStackHost`, `McpSteroidDriver.mcpListProjects` — all existing.
- Produces: nothing consumed by later tasks. It reports two numbers to a human: bare-clone warm duration and project-ready duration.

- [ ] **Step 1: Pre-warm the bare repository on the host**

The repo cache currently holds only `dpaia`; a first full bare clone of Keycloak will exceed both the automatic 300 s warm timeout and the arena's 120 s remote-clone fallback. Warm it once, outside any test:

```bash
mkdir -p test-experiments/build/repo-cache/keycloak
git clone --bare https://github.com/keycloak/keycloak.git \
  test-experiments/build/repo-cache/keycloak/keycloak.git
date -u +%Y-%m-%d > test-experiments/build/repo-cache/keycloak/keycloak.git/last-update
```

The `last-update` file is the freshness marker `BareRepoCache` reads (`test-helper/src/main/kotlin/com/jonnyzzz/mcpSteroid/testHelper/git/BareRepoCache.kt`); writing today's date stops the next test run from triggering a `git remote update` before the clone is even used.

Verify the pinned commit is present:

```bash
git -C test-experiments/build/repo-cache/keycloak/keycloak.git \
  cat-file -t 60c4d5e9321ff5462a772ceb896f8cb2e639e04b
```

Expected output: `commit`. If it prints an error, the clone predates the commit — re-run `git -C <bare> remote update --prune` and check again.

- [ ] **Step 2: Write the probe test**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Infrastructure gate for the semantic-ripple track: proves a container can clone Keycloak from the
 * host bare-repo cache, import 189 Maven modules, and reach a state where PSI queries resolve — and
 * reports how long that takes, because the prewarm timeout in [SemanticRippleSpec] is a guess until
 * this test has run once.
 *
 * No agent, no oracle. When this fails the track is blocked on infrastructure, which is a different
 * finding from the hypothesis being wrong.
 */
class SemanticRipplePrewarmProbeTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    fun `keycloak opens and imports from the bare repo cache`() {
        val lifetime = CloseableStackHost()
        try {
            val startedAt = System.currentTimeMillis()
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "keycloak-prewarm-probe",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = "https://github.com/keycloak/keycloak.git",
                    repoOwnerAndName = "keycloak/keycloak",
                    baseCommit = "60c4d5e9321ff5462a772ceb896f8cb2e639e04b",
                    testPatch = "",
                    displayName = "keycloak-semantic-ripple-probe",
                    buildSystem = "maven",
                ),
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                timeoutMillis = 3_600_000L,
                projectJdkVersion = "21",
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                requireCleanCompile = false,
            )
            val readyMs = System.currentTimeMillis() - startedAt

            val projectDir = session.intellijDriver.getGuestProjectDir()
            val openProjects = session.mcpSteroid.mcpListProjects()
            assertTrue(openProjects.any { it.path == projectDir }) {
                "No IDE project open at $projectDir (open: ${openProjects.joinToString { it.path }})"
            }

            val probe = session.mcpSteroid.mcpExecuteCode(
                code = """
                    import com.intellij.psi.JavaPsiFacade
                    import com.intellij.psi.search.GlobalSearchScope
                    import com.intellij.psi.search.PsiShortNamesCache
                    import com.intellij.openapi.module.ModuleManager

                    println("MODULES " + readAction { ModuleManager.getInstance(project).modules.size })
                    smartReadAction(project) {
                        val scope = GlobalSearchScope.projectScope(project)
                        val decls = PsiShortNamesCache.getInstance(project).getMethodsByName("roles", scope)
                        println("ROLES_DECLS " + decls.size)
                        val jdk = JavaPsiFacade.getInstance(project)
                            .findClass("java.lang.String", GlobalSearchScope.allScope(project))
                        println("JDK_RESOLVES " + (jdk != null))
                    }
                """.trimIndent(),
                reason = "Probe whether Keycloak PSI resolves after import, for the semantic-ripple prewarm gate",
                taskId = "semantic-ripple-probe",
                timeout = 600,
            )

            println("[RIPPLE-PROBE] project ready in ${readyMs / 1000}s")
            println("[RIPPLE-PROBE] probe output:\n${probe.stdout}")

            assertTrue(probe.stdout.contains("JDK_RESOLVES true")) {
                "JDK symbols do not resolve after import; PSI counts would be wrong.\n${probe.stdout}"
            }
            assertTrue(probe.stdout.contains("ROLES_DECLS 17")) {
                "Expected 17 declarations named 'roles' at the pinned commit.\n${probe.stdout}"
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
```

- [ ] **Step 3: Run the probe**

Run: `./gradlew :test-experiments:test --tests '*SemanticRipplePrewarmProbeTest*' --rerun-tasks`

Expected: PASS, with `[RIPPLE-PROBE] project ready in <N>s` printed. Watch the console; if nothing is printed within ~60 s of `> Task :test-experiments:test`, follow the stuck-test recipe in `test-integration/CLAUDE.md` ("Debugging a stuck/hung Docker test") before killing anything.

- [ ] **Step 4: Report the measured prewarm time**

`SemanticRippleSpec` does not exist yet — it is created in Task 2 — so do **not** try to edit it here. Instead, report the measured `project ready in Ns` figure in this task's report file as `MEASURED_PREWARM_SECONDS: <N>`. Task 2 consumes that number: its `projectReadyTimeoutMs` is twice the measured value, rounded up to the next whole minute, with the measurement recorded in the KDoc so the next reader knows it is measured rather than assumed.

If the probe cannot pass — import times out, container is OOM-killed, or `ROLES_DECLS` differs from 17 — **stop here and report**. Tasks 2 through 7 are worthless without this gate.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRipplePrewarmProbeTest.kt
git commit -m "Add a Keycloak prewarm probe for the semantic-ripple track

The track needs a 189-module Maven project imported in a container before any
agent runs. This asserts the bare-repo clone, the import and PSI resolution all
work, and prints how long they take, so the case timeouts are measured rather
than guessed."
```

---

### Task 2: Pinned task specification and its tripwires

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleSpec.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleSpecTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `SemanticRippleSpec` — an object with `baseCommit: String`, `cloneUrl: String`, `repoOwnerAndName: String`, `targetClassFqn: String`, `oldName: String`, `newName: String`, `expectedGoldReferences: Int`, `expectedGoldFiles: Int`, `expectedDecoyDeclarations: Int`, `compileGateModules: List<String>`, `projectJdkVersion: String`, `agentTimeoutSeconds: Long`, `projectReadyTimeoutMs: Long`.

- [ ] **Step 1: Write the failing test**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleSpecTest {

    @Test
    fun `base commit is a full pinned sha`() {
        assertTrue(SemanticRippleSpec.baseCommit.matches(Regex("[0-9a-f]{40}"))) {
            "Base commit must be a full 40-character SHA, was '${SemanticRippleSpec.baseCommit}'"
        }
    }

    @Test
    fun `new name differs from old name and is not the already-taken realmRoles`() {
        assertNotEquals(SemanticRippleSpec.oldName, SemanticRippleSpec.newName)
        assertNotEquals("realmRoles", SemanticRippleSpec.newName) {
            "realmRoles is already declared 5 times in Keycloak and must not be the rename target name"
        }
    }

    @Test
    fun `expected counts are the measured ones`() {
        assertEquals(445, SemanticRippleSpec.expectedGoldReferences)
        assertEquals(79, SemanticRippleSpec.expectedGoldFiles)
        assertEquals(16, SemanticRippleSpec.expectedDecoyDeclarations)
    }

    @Test
    fun `compile gate covers the declaring module and every reference module`() {
        assertEquals(7, SemanticRippleSpec.compileGateModules.size)
        assertTrue(SemanticRippleSpec.compileGateModules.contains("keycloak-admin-client-core"))
        assertEquals(
            SemanticRippleSpec.compileGateModules.distinct().size,
            SemanticRippleSpec.compileGateModules.size,
        ) { "compileGateModules must not repeat a module" }
    }

    @Test
    fun `maven invocations never use also-make`() {
        assertFalse(SemanticRippleSpec.compileGateArgs().contains("-am")) {
            "-am walks the upstream graph and OOM-kills the container"
        }
        assertTrue(SemanticRippleSpec.compileGateArgs().contains("test-compile")) {
            "The gate must compile test sources — the 445 references live in test modules"
        }
    }
}
```

Add the missing import for `assertNotEquals`: `import org.junit.jupiter.api.Assertions.assertNotEquals`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleSpecTest*'`
Expected: compilation failure — `Unresolved reference: SemanticRippleSpec`.

- [ ] **Step 3: Write the implementation**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The pinned specification of the semantic-ripple pilot task.
 *
 * Every count here was measured by PSI at [baseCommit] and is meaningless at any other commit. They
 * are asserted as tripwires before the agent runs: without them an index failure yields an empty
 * gold set, which would score as 100% recall over nothing.
 *
 * The rename is behaviour-preserving by construction, not by assertion: the target method carries
 * `@Path("roles")`, so the HTTP contract is defined by the annotation and cannot change with the
 * Java method name.
 *
 * [newName] is deliberately not `realmRoles`, which is already declared 5 times in the project.
 */
object SemanticRippleSpec {

    const val cloneUrl: String = "https://github.com/keycloak/keycloak.git"
    const val repoOwnerAndName: String = "keycloak/keycloak"
    const val baseCommit: String = "60c4d5e9321ff5462a772ceb896f8cb2e639e04b"

    const val targetClassFqn: String = "org.keycloak.admin.client.resource.RealmResource"
    const val targetReturnTypeSimpleName: String = "RolesResource"
    const val oldName: String = "roles"
    const val newName: String = "realmLevelRoles"

    /** Resolved references to the target at [baseCommit]. */
    const val expectedGoldReferences: Int = 445

    /** Distinct files holding those references at [baseCommit]. */
    const val expectedGoldFiles: Int = 79

    /** Project declarations sharing the simple name [oldName], excluding the target itself. */
    const val expectedDecoyDeclarations: Int = 16

    /** The declaring module plus every module holding a reference — complete w.r.t. the ripple. */
    val compileGateModules: List<String> = listOf(
        "keycloak-admin-client-core",
        "integration-arquillian-tests-base",
        "keycloak-admin-v2-tests",
        "keycloak-authzen-tests-base",
        "keycloak-test-framework-tests",
        "keycloak-tests-base",
        "keycloak-tests-utils",
    )

    const val projectJdkVersion: String = "21"

    /** Same budget the heaviest DPAIA cases already carry. */
    const val agentTimeoutSeconds: Long = 5_400L

    /**
     * Twice the prewarm time measured by `SemanticRipplePrewarmProbeTest`, rounded up to the next
     * whole minute. Take the number from Task 1's report line `MEASURED_PREWARM_SECONDS: <N>` and
     * state it here, so the next reader can tell a measurement from a guess:
     *
     *   measured <N>s on 2026-08-11 → <2N rounded up> ms
     *
     * If Task 1's report is unavailable, stop and say so rather than keeping the placeholder below —
     * an invented timeout is how a slow import gets misread as a broken one.
     */
    const val projectReadyTimeoutMs: Long = 3_600_000L

    /**
     * Maven arguments for the compile gate. `test-compile` rather than `compile` because all 445
     * references live in test sources; `-pl` without `-am` because the harness prewarm already
     * installed the siblings, and `-am` OOM-kills the container.
     */
    fun compileGateArgs(): List<String> =
        listOf("test-compile", "-pl", compileGateModules.joinToString(","), "-DskipTests")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleSpecTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleSpec.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleSpecTest.kt
git commit -m "Pin the semantic-ripple task spec and its tripwire counts

The gold reference set is captured at run time by PSI, so a silent index failure
would produce an empty set and a perfect score over nothing. Pinning the measured
counts next to the commit they were measured at turns that into a hard failure."
```

---

### Task 3: Gold model, post-condition model, and the pure parsers

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleOracle.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleOracleTest.kt`

**Interfaces:**
- Consumes: `SemanticRippleSpec` from Task 2.
- Produces:
  - `data class GoldSite(val file: String, val enclosingDeclaration: String, val references: Int)`
  - `data class SemanticGold(val targetFqn: String, val oldName: String, val newName: String, val sites: List<GoldSite>, val decoyReferences: Map<String, Int>, val newNameDeclarations: Int)` with `val totalReferences: Int` and `val files: Int`
  - `data class SemanticPostconditionResult(...)` with `val allPassed: Boolean`
  - `fun parseSemanticGold(output: String): SemanticGold`
  - `fun parseSemanticPostcondition(output: String, gold: SemanticGold): SemanticPostconditionResult`
  - `fun SemanticGold.checkTripwires()` — throws `IllegalStateException` on any mismatch with `SemanticRippleSpec`

- [ ] **Step 1: Write the failing tests**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleOracleTest {

    /** Shape emitted by the capture script: three sites holding 4 references in total. */
    private val goldOutput = """
        GOLD_TARGET org.keycloak.admin.client.resource.RealmResource|roles|realmLevelRoles
        GOLD_SITE a/A.java|A#one|2
        GOLD_SITE a/A.java|A#two|1
        GOLD_SITE b/B.java|B#three|1
        GOLD_DECOY org.keycloak.admin.client.resource.ClientResource|343
        GOLD_DECOY org.keycloak.admin.client.resource.UserResource|401
        GOLD_NEWNAME_DECLS 0
        GOLD_END
    """.trimIndent()

    private fun gold() = parseSemanticGold(goldOutput)

    @Test
    fun `gold parses sites, totals and decoys`() {
        val g = gold()
        assertEquals("org.keycloak.admin.client.resource.RealmResource", g.targetFqn)
        assertEquals("realmLevelRoles", g.newName)
        assertEquals(3, g.sites.size)
        assertEquals(4, g.totalReferences)
        assertEquals(2, g.files)
        assertEquals(343, g.decoyReferences.getValue("org.keycloak.admin.client.resource.ClientResource"))
        assertEquals(0, g.newNameDeclarations)
    }

    @Test
    fun `truncated gold output fails loudly instead of parsing as empty`() {
        val truncated = goldOutput.lines().dropLast(1).joinToString("\n")
        val e = assertThrows(IllegalStateException::class.java) { parseSemanticGold(truncated) }
        assertTrue(e.message!!.contains("GOLD_END")) { "Message should name the missing terminator: ${e.message}" }
    }

    private val perfectPost = """
        POST_NEWNAME_DECLARED true
        POST_OLDNAME_ON_TARGET 0
        POST_SITE a/A.java|A#one|2
        POST_SITE a/A.java|A#two|1
        POST_SITE b/B.java|B#three|1
        POST_DECOY org.keycloak.admin.client.resource.ClientResource|343
        POST_DECOY org.keycloak.admin.client.resource.UserResource|401
        POST_TOTAL_NEW_REFS 4
        POST_END
    """.trimIndent()

    @Test
    fun `a complete rename passes every post-condition`() {
        val r = parseSemanticPostcondition(perfectPost, gold())
        assertTrue(r.p1NoAliasAndNewNameDeclared)
        assertTrue(r.p2AllSitesConverted)
        assertTrue(r.p3DecoysUnchanged)
        assertTrue(r.p4Conserved)
        assertEquals(1.0, r.recall)
        assertEquals(1.0, r.precision)
        assertEquals(1.0, r.f1)
        assertTrue(r.allPassed)
        assertTrue(r.missedSites.isEmpty())
        assertTrue(r.overReachedDecoys.isEmpty())
    }

    @Test
    fun `a compatibility alias fails P1 even when every site converted`() {
        val aliased = perfectPost.replace("POST_OLDNAME_ON_TARGET 0", "POST_OLDNAME_ON_TARGET 1")
        val r = parseSemanticPostcondition(aliased, gold())
        assertFalse(r.p1NoAliasAndNewNameDeclared)
        assertTrue(r.p2AllSitesConverted) { "Sites are converted; only the alias is wrong" }
        assertFalse(r.allPassed)
    }

    @Test
    fun `a partially converted site fails P2 and lowers recall`() {
        val partial = perfectPost
            .replace("POST_SITE a/A.java|A#one|2", "POST_SITE a/A.java|A#one|1")
            .replace("POST_TOTAL_NEW_REFS 4", "POST_TOTAL_NEW_REFS 3")
        val r = parseSemanticPostcondition(partial, gold())
        assertFalse(r.p2AllSitesConverted) {
            "A site that held 2 references and now holds 1 is a partial failure, not a success"
        }
        assertEquals(0.75, r.recall)
        assertEquals(listOf(GoldSite("a/A.java", "A#one", 2)), r.missedSites)
    }

    @Test
    fun `a missing site is reported and fails P2`() {
        val missing = perfectPost
            .lines().filterNot { it.startsWith("POST_SITE b/B.java") }.joinToString("\n")
            .replace("POST_TOTAL_NEW_REFS 4", "POST_TOTAL_NEW_REFS 3")
        val r = parseSemanticPostcondition(missing, gold())
        assertFalse(r.p2AllSitesConverted)
        assertEquals(listOf(GoldSite("b/B.java", "B#three", 1)), r.missedSites)
    }

    @Test
    fun `renaming a decoy fails P3 and names the decoy`() {
        val overReach = perfectPost
            .replace("POST_DECOY org.keycloak.admin.client.resource.ClientResource|343",
                     "POST_DECOY org.keycloak.admin.client.resource.ClientResource|340")
        val r = parseSemanticPostcondition(overReach, gold())
        assertFalse(r.p3DecoysUnchanged)
        assertEquals(listOf("org.keycloak.admin.client.resource.ClientResource"), r.overReachedDecoys)
        assertFalse(r.allPassed)
    }

    @Test
    fun `inventing references beyond the gold set fails P4 and lowers precision`() {
        val invented = perfectPost
            .replace("POST_SITE b/B.java|B#three|1", "POST_SITE b/B.java|B#three|1\nPOST_SITE c/C.java|C#four|2")
            .replace("POST_TOTAL_NEW_REFS 4", "POST_TOTAL_NEW_REFS 6")
        val r = parseSemanticPostcondition(invented, gold())
        assertFalse(r.p4Conserved)
        assertEquals(1.0, r.recall) { "Every gold site is still converted" }
        assertEquals(4.0 / 6.0, r.precision)
    }

    @Test
    fun `an empty gold set is rejected by the tripwires instead of scoring perfectly`() {
        val empty = """
            GOLD_TARGET org.keycloak.admin.client.resource.RealmResource|roles|realmLevelRoles
            GOLD_NEWNAME_DECLS 0
            GOLD_END
        """.trimIndent()
        val g = parseSemanticGold(empty)
        assertEquals(0, g.totalReferences)
        val e = assertThrows(IllegalStateException::class.java) { g.checkTripwires() }
        assertTrue(e.message!!.contains("445")) { "Message should state the expected count: ${e.message}" }
    }

    @Test
    fun `tripwires reject a pre-existing new name`() {
        val taken = goldOutput.replace("GOLD_NEWNAME_DECLS 0", "GOLD_NEWNAME_DECLS 5")
        val e = assertThrows(IllegalStateException::class.java) { parseSemanticGold(taken).checkTripwires() }
        assertTrue(e.message!!.contains(SemanticRippleSpec.newName))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleOracleTest*'`
Expected: compilation failure — `Unresolved reference: parseSemanticGold`.

- [ ] **Step 3: Write the implementation**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * One place a reference to the rename target lives, keyed so it survives the agent's edits.
 *
 * The key is `(file, enclosing declaration)` rather than a line or offset: line numbers shift as
 * soon as the agent touches the file, and an offset-keyed gold set would report false misses.
 */
data class GoldSite(
    val file: String,
    val enclosingDeclaration: String,
    val references: Int,
)

/** The pre-agent semantic state: what a correct rename must move, and what it must leave alone. */
data class SemanticGold(
    val targetFqn: String,
    val oldName: String,
    val newName: String,
    val sites: List<GoldSite>,
    /** Resolved-reference count per same-named declaration that is NOT the target. */
    val decoyReferences: Map<String, Int>,
    /** Declarations of [newName] already in the project — must be zero, or the task is ill-posed. */
    val newNameDeclarations: Int,
) {
    val totalReferences: Int get() = sites.sumOf { it.references }
    val files: Int get() = sites.map { it.file }.toSet().size

    /** Key used to match a post-agent site against this gold set. */
    fun keyOf(site: GoldSite): Pair<String, String> = site.file to site.enclosingDeclaration
}

/**
 * Grade of one run against [SemanticGold]. Every field is measured; none is inferred from the
 * agent's own claim.
 */
data class SemanticPostconditionResult(
    /** P1: the new name is declared on the target type and the old name is gone from it. */
    val p1NoAliasAndNewNameDeclared: Boolean,
    /** P2: every gold site now holds at least as many references to the new name as it held to the old. */
    val p2AllSitesConverted: Boolean,
    /** P3: every decoy declaration's reference count is unchanged. */
    val p3DecoysUnchanged: Boolean,
    /** P4: total references to the new name equal the gold total. */
    val p4Conserved: Boolean,
    val recall: Double,
    val precision: Double,
    val f1: Double,
    val missedSites: List<GoldSite>,
    val overReachedDecoys: List<String>,
) {
    val allPassed: Boolean
        get() = p1NoAliasAndNewNameDeclared && p2AllSitesConverted && p3DecoysUnchanged && p4Conserved
}

/**
 * Parse the capture script's output.
 *
 * Requires the `GOLD_END` terminator: without it a truncated or cancelled script would parse as a
 * smaller — or empty — gold set, and every downstream score would be computed against it silently.
 */
fun parseSemanticGold(output: String): SemanticGold {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "GOLD_END" }) {
        "Gold capture output has no GOLD_END terminator — the script was truncated or failed:\n$output"
    }
    val header = lines.firstOrNull { it.startsWith("GOLD_TARGET ") }
        ?: error("Gold capture output has no GOLD_TARGET line:\n$output")
    val headerParts = header.removePrefix("GOLD_TARGET ").split('|')
    check(headerParts.size == 3) { "Malformed GOLD_TARGET line: $header" }

    val sites = lines.filter { it.startsWith("GOLD_SITE ") }.map { line ->
        val parts = line.removePrefix("GOLD_SITE ").split('|')
        check(parts.size == 3) { "Malformed GOLD_SITE line: $line" }
        GoldSite(parts[0], parts[1], parts[2].toInt())
    }
    val decoys = lines.filter { it.startsWith("GOLD_DECOY ") }.associate { line ->
        val parts = line.removePrefix("GOLD_DECOY ").split('|')
        check(parts.size == 2) { "Malformed GOLD_DECOY line: $line" }
        parts[0] to parts[1].toInt()
    }
    val newNameDeclarations = lines.first { it.startsWith("GOLD_NEWNAME_DECLS ") }
        .removePrefix("GOLD_NEWNAME_DECLS ").toInt()

    return SemanticGold(
        targetFqn = headerParts[0],
        oldName = headerParts[1],
        newName = headerParts[2],
        sites = sites,
        decoyReferences = decoys,
        newNameDeclarations = newNameDeclarations,
    )
}

/**
 * Fail the run before the agent starts when the captured world does not match the pinned
 * measurement. An index failure produces an empty gold set, which would otherwise score as a
 * perfect rename over nothing.
 */
fun SemanticGold.checkTripwires() {
    check(totalReferences == SemanticRippleSpec.expectedGoldReferences) {
        "Gold reference count is $totalReferences, expected ${SemanticRippleSpec.expectedGoldReferences} " +
            "at ${SemanticRippleSpec.baseCommit}. Either the commit moved or the index is incomplete."
    }
    check(files == SemanticRippleSpec.expectedGoldFiles) {
        "Gold spans $files files, expected ${SemanticRippleSpec.expectedGoldFiles}"
    }
    check(decoyReferences.size == SemanticRippleSpec.expectedDecoyDeclarations) {
        "Found ${decoyReferences.size} decoy declarations named '${SemanticRippleSpec.oldName}', " +
            "expected ${SemanticRippleSpec.expectedDecoyDeclarations}"
    }
    check(newNameDeclarations == 0) {
        "'${SemanticRippleSpec.newName}' is already declared $newNameDeclarations times; the rename " +
            "target name must be free or the task is ill-posed"
    }
}

/** Parse the post-agent script's output and grade it against [gold]. */
fun parseSemanticPostcondition(output: String, gold: SemanticGold): SemanticPostconditionResult {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "POST_END" }) {
        "Post-condition output has no POST_END terminator — the script was truncated or failed:\n$output"
    }
    fun flag(prefix: String): String = lines.first { it.startsWith(prefix) }.removePrefix(prefix).trim()

    val newNameDeclared = flag("POST_NEWNAME_DECLARED ").toBooleanStrict()
    val oldNameOnTarget = flag("POST_OLDNAME_ON_TARGET ").toInt()
    val totalNewRefs = flag("POST_TOTAL_NEW_REFS ").toInt()

    val postSites = lines.filter { it.startsWith("POST_SITE ") }.map { line ->
        val parts = line.removePrefix("POST_SITE ").split('|')
        check(parts.size == 3) { "Malformed POST_SITE line: $line" }
        GoldSite(parts[0], parts[1], parts[2].toInt())
    }
    val postByKey = postSites.associate { (it.file to it.enclosingDeclaration) to it.references }

    val missed = gold.sites.filter { site ->
        (postByKey[gold.keyOf(site)] ?: 0) < site.references
    }
    val convertedAtGold = gold.sites.sumOf { site ->
        minOf(postByKey[gold.keyOf(site)] ?: 0, site.references)
    }

    val postDecoys = lines.filter { it.startsWith("POST_DECOY ") }.associate { line ->
        val parts = line.removePrefix("POST_DECOY ").split('|')
        check(parts.size == 2) { "Malformed POST_DECOY line: $line" }
        parts[0] to parts[1].toInt()
    }
    val overReached = gold.decoyReferences.filter { (owner, before) ->
        (postDecoys[owner] ?: 0) != before
    }.keys.sorted()

    val recall = if (gold.totalReferences == 0) 0.0 else convertedAtGold.toDouble() / gold.totalReferences
    val precision = if (totalNewRefs == 0) 0.0 else convertedAtGold.toDouble() / totalNewRefs
    val f1 = if (recall + precision == 0.0) 0.0 else 2 * recall * precision / (recall + precision)

    return SemanticPostconditionResult(
        p1NoAliasAndNewNameDeclared = newNameDeclared && oldNameOnTarget == 0,
        p2AllSitesConverted = missed.isEmpty(),
        p3DecoysUnchanged = overReached.isEmpty(),
        p4Conserved = totalNewRefs == gold.totalReferences,
        recall = recall,
        precision = precision,
        f1 = f1,
        missedSites = missed,
        overReachedDecoys = overReached,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleOracleTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleOracle.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleOracleTest.kt
git commit -m "Add the semantic-ripple gold model and its four post-conditions

Grading is split from execution: the IDE script prints a line format, pure
functions parse and score it, so alias-left-behind, partial conversion, missed
sites and decoy over-reach are all covered by unit tests without Docker."
```

---

### Task 4: The task prompt and its purity contract

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRipplePrompt.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRipplePromptContractTest.kt`

**Interfaces:**
- Consumes: `SemanticRippleSpec` from Task 2.
- Produces: `fun buildSemanticRipplePrompt(projectDir: String, withMcp: Boolean): String`

- [ ] **Step 1: Write the failing test**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRipplePromptContractTest {

    private val mcpPrompt = buildSemanticRipplePrompt("/work/keycloak", withMcp = true)
    private val nonePrompt = buildSemanticRipplePrompt("/work/keycloak", withMcp = false)
    private val both = listOf(mcpPrompt, nonePrompt)

    @Test
    fun `both arms name the declaration exactly`() {
        both.forEach { p ->
            assertTrue(p.contains("org.keycloak.admin.client.resource.RealmResource")) { p }
            assertTrue(p.contains("roles()")) { p }
            assertTrue(p.contains("realmLevelRoles")) { p }
        }
    }

    @Test
    fun `both arms forbid renaming same-named methods of other types`() {
        both.forEach { p ->
            assertTrue(p.contains("other types")) {
                "The prompt must state that same-named methods on other types keep their name:\n$p"
            }
        }
    }

    @Test
    fun `both arms carry the harness success marker`() {
        both.forEach { p -> assertTrue(p.contains("ARENA_FIX_APPLIED: yes")) { p } }
    }

    @Test
    fun `neither arm reveals the mechanism`() {
        val forbidden = listOf(
            "steroid_", "mcp-steroid://", "find usages", "findUsages", "Find Usages",
            "PSI", "IntelliJ", "refactor", "Refactor", "IDE",
        )
        both.forEach { p ->
            forbidden.forEach { token ->
                assertFalse(p.contains(token)) {
                    "Prompt leaks the mechanism via '$token'; the agent must discover it:\n$p"
                }
            }
        }
    }

    @Test
    fun `neither arm hands over the answer`() {
        both.forEach { p ->
            assertFalse(p.contains("ClientResource")) {
                "Naming a decoy tells the agent which matches to skip:\n$p"
            }
            assertFalse(p.contains("445")) {
                "Stating the reference count tells the agent when to stop searching:\n$p"
            }
            assertFalse(p.contains(".java")) {
                "The prompt must not enumerate affected files:\n$p"
            }
        }
    }

    @Test
    fun `both arms state the behaviour-preservation requirement`() {
        both.forEach { p ->
            assertTrue(p.contains("behaviour") || p.contains("behavior")) { p }
            assertTrue(p.contains("HTTP")) {
                "The prompt must say the HTTP contract stays unchanged:\n$p"
            }
        }
    }

    @Test
    fun `no maven invocation suggests also-make`() {
        both.forEach { p -> assertFalse(p.contains("-am")) { p } }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*SemanticRipplePromptContractTest*'`
Expected: compilation failure — `Unresolved reference: buildSemanticRipplePrompt`.

- [ ] **Step 3: Write the implementation**

Note the deliberate wording constraints: the word `IDE` never appears, `./mvnw` is named without the word Maven being required, and the JDK paragraph mirrors the DPAIA prompt's environment facts.

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The task brief for the semantic-ripple pilot.
 *
 * Deliberately states the declaration exactly — the benchmark does not test guessing the starting
 * point, it tests finding the whole ripple — while revealing nothing about how to find the call
 * sites. `SemanticRipplePromptContractTest` pins both halves of that: the required content, and the
 * absence of every mechanism hint and of the answer itself (no decoy names, no counts, no file list).
 */
fun buildSemanticRipplePrompt(projectDir: String, withMcp: Boolean): String = buildString {
    val jdkPrefix = "/usr/lib/jvm/temurin-${SemanticRippleSpec.projectJdkVersion}-jdk-"

    appendLine("You are working on a large multi-module Java project located at: `$projectDir`")
    appendLine()
    appendLine("**OUTPUT REQUIREMENT** (read now, apply at the end): when the task is complete and the")
    appendLine("project compiles, your LAST message MUST contain `ARENA_FIX_APPLIED: yes` on its own line.")
    appendLine("The harness detects only that exact string, not a build tool's own success output.")
    appendLine()
    appendLine("## Task")
    appendLine()
    appendLine("Rename the method")
    appendLine()
    appendLine("    ${SemanticRippleSpec.targetReturnTypeSimpleName} ${SemanticRippleSpec.oldName}()")
    appendLine()
    appendLine("declared on the interface `${SemanticRippleSpec.targetClassFqn}`")
    appendLine("to `${SemanticRippleSpec.newName}`, throughout the whole project.")
    appendLine()
    appendLine("Requirements:")
    appendLine()
    appendLine("1. Every place that calls this method must call it by its new name. When you are done, no")
    appendLine("   caller of this declaration may still use the old name.")
    appendLine("2. The old name must not survive on that interface in any form — not as a second method,")
    appendLine("   not as a deprecated forwarder, not as a default method.")
    appendLine("3. Methods that happen to share the same simple name but are declared on **other types**")
    appendLine("   are unrelated and MUST keep their current name. Changing one of them is a defect.")
    appendLine("4. External behaviour must not change. The HTTP contract of this endpoint is defined by")
    appendLine("   its annotation, not by the Java method name, so a correct rename leaves it untouched.")
    appendLine("5. The project must compile, test sources included, when you are finished.")
    appendLine()
    appendLine("## Environment Facts")
    appendLine()
    appendLine("- Use the project wrapper only: `./mvnw`")
    appendLine("- Configured project JDK version: **${SemanticRippleSpec.projectJdkVersion}**")
    appendLine("- Resolve a concrete path whose name starts with `$jdkPrefix`, then run")
    appendLine("  `JAVA_HOME=<that exact path> ./mvnw ...`. Do not use wildcard JAVA_HOME assignments and")
    appendLine("  do not try a lower JDK first.")
    appendLine("- This project has many modules. Build one module at a time with")
    appendLine("  `./mvnw <goal> -pl <module>`; never ask the build to also make upstream dependencies,")
    appendLine("  which exhausts the container's memory.")
    appendLine("- The dependencies of every module are already installed locally, so `-pl` works on its own.")
    if (withMcp) {
        appendLine("- Bash build commands must use the exact `Recommended JAVA_HOME` printed by your first")
        appendLine("  tool call, which starts with `$jdkPrefix`.")
    }
    appendLine()
    appendLine("## Success Markers")
    appendLine()
    appendLine("End your last message with exactly:")
    appendLine()
    appendLine("    ARENA_FIX_APPLIED: yes")
    appendLine()
    appendLine("If you could not complete the task, end with `ARENA_FIX_APPLIED: no` and one line saying why.")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :test-experiments:test --tests '*SemanticRipplePromptContractTest*'`
Expected: PASS, 7 tests. If the "mechanism" test fails on the word `IDE`, check for it inside a longer word — the assertion is a plain substring match by design, so `IDENTIFIER` would trip it. Reword the prose rather than weakening the assertion.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRipplePrompt.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRipplePromptContractTest.kt
git commit -m "Add the semantic-ripple task prompt with a purity contract

The declaration is named exactly, because the task is to find the whole ripple
rather than to guess where it starts. The contract test pins that the prompt
leaks neither the mechanism nor the answer: no decoy names, no counts, no files."
```

---

### Task 5: Hidden consumer patch and the synthetic case

**Files:**
- Create: `test-experiments/src/test/resources/arena-overlays/semantic-ripple-keycloak-roles.patch`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleCases.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleCasesTest.kt`

**Interfaces:**
- Consumes: `SemanticRippleSpec` (Task 2), `buildSemanticRipplePrompt` (Task 4), existing `DpaiaTestCase`, existing `extractPatchFilePaths` from `ArenaVerification.kt`.
- Produces: `object SemanticRippleCases` with `val hiddenConsumerFqn: String`, `fun testPatch(): String`, `fun pilotCase(): DpaiaTestCase`

- [ ] **Step 1: Write the patch resource**

The consumer is a new file in `keycloak-admin-client-core`'s test sources — a module in the compile gate that by definition depends on the interface. It asserts the rename by reflection, so it needs no server and runs in milliseconds.

```diff
diff --git a/integration/admin-client-core/src/test/java/org/keycloak/admin/client/resource/RealmResourceRenameContractTest.java b/integration/admin-client-core/src/test/java/org/keycloak/admin/client/resource/RealmResourceRenameContractTest.java
new file mode 100644
--- /dev/null
+++ b/integration/admin-client-core/src/test/java/org/keycloak/admin/client/resource/RealmResourceRenameContractTest.java
@@ -0,0 +1,29 @@
+package org.keycloak.admin.client.resource;
+
+import org.junit.Assert;
+import org.junit.Test;
+
+/**
+ * Pins the renamed realm-roles accessor on {@link RealmResource}.
+ *
+ * The new name must be present and the old one must be gone entirely — keeping the old name as a
+ * deprecated forwarder would leave every caller compiling against a name that no longer exists in
+ * the contract this test defines.
+ */
+public class RealmResourceRenameContractTest {
+
+    @Test
+    public void newNameIsDeclaredAndReturnsRolesResource() throws Exception {
+        Assert.assertEquals(
+                RolesResource.class,
+                RealmResource.class.getMethod("realmLevelRoles").getReturnType());
+    }
+
+    @Test
+    public void oldNameIsGone() {
+        try {
+            RealmResource.class.getMethod("roles");
+            Assert.fail("RealmResource still declares roles(); the rename left a compatibility alias");
+        } catch (NoSuchMethodException expected) {
+            // the old accessor is gone, which is what this test asserts
+        }
+    }
+}
```

Two things to verify by hand before continuing, because both are properties of the repository rather than of our code:

```bash
# 1. JUnit 4 is what this module's tests use — org.junit.Assert must resolve there.
grep -rn "org.junit.Test" \
  /tmp/sempress/repos/keycloak/integration/admin-client-core/src/test/java | head -3

# 2. The path does not already exist.
ls /tmp/sempress/repos/keycloak/integration/admin-client-core/src/test/java/org/keycloak/admin/client/resource/RealmResourceRenameContractTest.java
```

Expected: the first prints at least one hit (confirming JUnit 4 imports are right for this module); the second prints "No such file or directory". If the module has no `src/test/java` at all, pick the next module from `SemanticRippleSpec.compileGateModules` that does, and update the patch paths and the FQN together.

- [ ] **Step 2: Write the failing test**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleCasesTest {

    @Test
    fun `test patch resource is present and non-empty`() {
        assertTrue(SemanticRippleCases.testPatch().isNotBlank())
    }

    @Test
    fun `test patch is purely additive`() {
        val patch = SemanticRippleCases.testPatch()
        val fileSections = patch.lines().count { it.startsWith("diff --git ") }
        val newFileMarkers = patch.lines().count { it.trim() == "--- /dev/null" }
        assertEquals(fileSections, newFileMarkers) {
            "Every file section must create a new file. A patch that modifies an existing file could " +
                "collide with one of the ${SemanticRippleSpec.expectedGoldFiles} files the agent must edit."
        }
        assertFalse(patch.contains("deleted file mode")) { "The patch must not delete anything" }
    }

    @Test
    fun `hidden consumer lives in a compile-gate module`() {
        val paths = extractPatchFilePaths(SemanticRippleCases.testPatch())
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.contains("/src/test/java/") }) {
            "The consumer belongs in test sources so the scoped test-compile builds it: $paths"
        }
    }

    @Test
    fun `pilot case pins the commit and names the consumer as FAIL_TO_PASS`() {
        val case = SemanticRippleCases.pilotCase()
        assertEquals(SemanticRippleSpec.baseCommit, case.baseCommit)
        assertEquals("maven", case.buildSystem)
        assertTrue(case.isMaven)
        assertEquals(listOf(SemanticRippleCases.hiddenConsumerFqn), case.failToPass)
        assertTrue(case.passToPass.isEmpty()) {
            "Regression evidence here is the compile gate, not a PASS_TO_PASS list"
        }
    }

    @Test
    fun `pilot case problem statement is the purity-checked prompt task section`() {
        val case = SemanticRippleCases.pilotCase()
        assertTrue(case.problemStatement.contains(SemanticRippleSpec.newName))
        assertFalse(case.problemStatement.contains("ClientResource")) {
            "The statement must not name a decoy"
        }
    }

    @Test
    fun `instance id is stable and identifies the track`() {
        assertEquals("ripple__keycloak__realm-roles-rename", SemanticRippleCases.pilotCase().instanceId)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleCasesTest*'`
Expected: compilation failure — `Unresolved reference: SemanticRippleCases`.

- [ ] **Step 4: Write the implementation**

```kotlin
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleCasesTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add test-experiments/src/test/resources/arena-overlays/semantic-ripple-keycloak-roles.patch \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleCases.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleCasesTest.kt
git commit -m "Add the semantic-ripple pilot case and its hidden consumer

The consumer asserts the rename by reflection, so it needs no server and states
the no-alias rule as an executable test. The patch is asserted to be purely
additive, which is what keeps it from colliding with the files the agent edits."
```

---

### Task 6: Extract the arena reporting tail so both tracks report identically

Comparability with the DPAIA numbers depends on this code being the same in both tracks. Duplicating it would let the two drift apart silently, which is the one failure mode that would invalidate the pilot's headline comparison.

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaRunReporting.kt`
- Modify: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/DpaiaScenarioBaseTest.kt:211-250` (metric extraction and `RunRecord` construction) and `:375-403` (`writeRunSummary`)
- Test: existing `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RunSummaryJsonTest.kt` — must keep passing unchanged

**Interfaces:**
- Consumes: existing `extractTokenUsage`, `extractTestMetrics`, `extractDecodedLogMetrics`, `findRawNdjsonFile`, `findDecodedLogFile`, `DpaiaScenarioBaseTest.RunRecord`, `buildRunSummaryJson`, `appendComparisonCsv`.
- Produces:
  - `fun collectRunMetrics(runDir: java.io.File, agentName: String, fallbackStdout: String): ArenaRunMetrics`
  - `data class ArenaRunMetrics(val tokenUsage: TokenUsage?, val testMetrics: TestMetrics?, val decodedLogMetrics: DecodedLogMetrics?)`
  - `fun writeArenaRunSummary(instanceId: String, agentName: String, modeLabel: String, record: DpaiaScenarioBaseTest.RunRecord)`

- [ ] **Step 1: Record the current behaviour before touching it**

Run: `./gradlew :test-experiments:test --tests '*RunSummaryJsonTest*' --tests '*ArenaComparisonCsvTest*' --tests '*ExtractTokenUsageTest*' --tests '*ExtractTestMetricsTest*' --tests '*ExtractDecodedLogMetricsTest*'`
Expected: PASS. Note the test counts — the same tests must pass unchanged at the end of this task. This is a pure extraction: no behaviour changes.

- [ ] **Step 2: Write the extraction**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import java.io.File

/** The three metric groups every arena run reports, whatever produced the run. */
data class ArenaRunMetrics(
    val tokenUsage: TokenUsage?,
    val testMetrics: TestMetrics?,
    val decodedLogMetrics: DecodedLogMetrics?,
)

/**
 * Extract run metrics from the agent's own logs.
 *
 * Shared by every arena track on purpose: the DPAIA cases and the semantic-ripple track are compared
 * against each other, so a divergence in how their tokens, turns and tool calls are counted would
 * silently invalidate that comparison.
 *
 * Prefers the persisted UNFILTERED transcript — the captured process stdout is console-filtered and
 * drops the usage and result events these metrics parse — and falls back to [fallbackStdout].
 */
fun collectRunMetrics(runDir: File, agentName: String, fallbackStdout: String): ArenaRunMetrics {
    val decodedLogName = when (agentName) {
        "claude" -> "claude-code"
        "codex" -> "codex"
        "gemini" -> "gemini"
        else -> agentName
    }
    val rawOutput = findRawNdjsonFile(runDir, agentName = decodedLogName)?.readText() ?: fallbackStdout
    return ArenaRunMetrics(
        tokenUsage = extractTokenUsage(rawOutput),
        testMetrics = extractTestMetrics(rawOutput),
        decodedLogMetrics = findDecodedLogFile(runDir, agentName = decodedLogName)
            ?.let { extractDecodedLogMetrics(it.readText()) },
    )
}

/** Write the per-run JSON summary and append the comparison CSV row. */
fun writeArenaRunSummary(
    instanceId: String,
    agentName: String,
    modeLabel: String,
    record: DpaiaScenarioBaseTest.RunRecord,
) {
    val summaryFile = IdeTestFolders.testOutputDir
        .resolve("dpaia-arena-run-$instanceId-$agentName-$modeLabel.json")
    summaryFile.parentFile.mkdirs()
    summaryFile.writeText(buildRunSummaryJson(record).toString())
    println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")

    val csvFile = IdeTestFolders.testOutputDir.resolve("arena-comparison.csv")
    appendComparisonCsv(
        csvFile = csvFile,
        instanceId = instanceId,
        passLabel = System.getProperty("arena.pass.label", ""),
        claimedFix = record.claimedFix,
        durationS = record.agentDurationMs / 1000,
        tokens = record.tokenUsage,
        testMetrics = record.testMetrics,
        decoded = record.decodedLogMetrics,
        verification = record.verification,
    )
    println("[ARENA] Comparison CSV appended to: ${csvFile.absolutePath}")
}
```

- [ ] **Step 3: Rewire `DpaiaScenarioBaseTest` to call it**

In `runAgent`, replace the metric-extraction block (the `decodedLogName` `when`, `rawOutput`, `tokens`, `testMetrics`, `decodedLogMetrics` locals) with:

```kotlin
            val metrics = collectRunMetrics(
                runDir = session.runDirInContainer,
                agentName = agentName,
                fallbackStdout = result.agentResult.stdout,
            )
```

Then in the `RunRecord(...)` construction, replace the three metric arguments with `metrics.tokenUsage`, `metrics.testMetrics`, `metrics.decodedLogMetrics`. In the printing block below it, replace the local `tokens`, `testMetrics` and `decodedLogMetrics` references with `metrics.tokenUsage`, `metrics.testMetrics` and `metrics.decodedLogMetrics`.

Finally delete the private `writeRunSummary` method and change its single call site to:

```kotlin
            writeArenaRunSummary(testCase.instanceId, agentName, modeLabel, record)
```

Do not change any string, key name, file name or number in the process. This task must be a behaviour-preserving move.

- [ ] **Step 4: Verify nothing changed**

Run: `./gradlew :test-experiments:test --tests '*RunSummaryJsonTest*' --tests '*ArenaComparisonCsvTest*' --tests '*ExtractTokenUsageTest*' --tests '*ExtractTestMetricsTest*' --tests '*ExtractDecodedLogMetricsTest*'`
Expected: PASS, the same test counts as Step 1.

Then confirm the whole module still compiles, since fifteen scenario tests inherit the class you edited:

Run: `./gradlew :test-experiments:compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaRunReporting.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/DpaiaScenarioBaseTest.kt
git commit -m "Extract the arena metric collection and run reporting

A second arena track is coming, and the two are compared against each other, so
the code that counts tokens, turns and tool calls has to be one implementation.
Pure move: no key, file name or number changes."
```

---

### Task 7: The scoped compile gate

This is the layer that covers the 445 call sites. `ArenaVerifier.verify` only builds the module holding the FAIL_TO_PASS class, so without this task the references in the other six modules are never compiled and a missed site goes undetected.

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleCompileGate.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleCompileGateTest.kt`

**Interfaces:**
- Consumes: `SemanticRippleSpec` (Task 2); existing `ContainerDriver.startProcessInContainer` — the same shape `ArenaVerifier.bash` uses.
- Produces:
  - `fun buildCompileGateScript(projectDir: String): String`
  - `data class CompileGateResult(val exitCode: Int, val tail: String)` with `val passed: Boolean`
  - `fun runCompileGate(container: ContainerDriver, projectDir: String): CompileGateResult`

- [ ] **Step 1: Write the failing test**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleCompileGateTest {

    private val script = buildCompileGateScript("/work/keycloak")

    @Test
    fun `gate compiles test sources, not just main`() {
        assertTrue(script.contains("test-compile")) {
            "All 445 references live in test sources; `compile` alone would not see them:\n$script"
        }
    }

    @Test
    fun `gate covers every compile-gate module exactly once`() {
        SemanticRippleSpec.compileGateModules.forEach { module ->
            assertTrue(script.contains(module)) { "Module $module missing from the gate:\n$script" }
        }
        assertTrue(script.contains("-pl")) { script }
    }

    @Test
    fun `gate never uses also-make`() {
        assertFalse(script.contains("-am")) {
            "-am walks the upstream graph and OOM-kills the container:\n$script"
        }
    }

    @Test
    fun `gate pins JAVA_HOME to the configured JDK`() {
        assertTrue(script.contains("temurin-${SemanticRippleSpec.projectJdkVersion}-jdk-")) { script }
        assertFalse(script.contains("JAVA_HOME=*")) { "No wildcard JAVA_HOME assignments:\n$script" }
    }

    @Test
    fun `gate prefers the project wrapper and fails loudly when absent`() {
        assertTrue(script.contains("mvnw")) { script }
        assertTrue(script.contains("exit 1")) {
            "A missing wrapper must fail the gate, not silently fall through:\n$script"
        }
    }

    @Test
    fun `result passes only on a zero exit code`() {
        assertTrue(CompileGateResult(exitCode = 0, tail = "").passed)
        assertFalse(CompileGateResult(exitCode = 1, tail = "BUILD FAILURE").passed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleCompileGateTest*'`
Expected: compilation failure — `Unresolved reference: buildCompileGateScript`.

- [ ] **Step 3: Write the implementation**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.container.ContainerDriver

/** Outcome of the scoped compile gate. */
data class CompileGateResult(
    val exitCode: Int,
    /** Last lines of the build log, kept bounded — a full Keycloak build log is enormous. */
    val tail: String,
) {
    val passed: Boolean get() = exitCode == 0
}

/**
 * The bash script for the scoped compile gate, kept pure so its shape is unit-tested.
 *
 * For a behaviour-preserving rename, compilation is a COMPLETE invariant over the ripple rather than
 * an approximation: a call site the agent missed still names a method that no longer exists, which
 * is a compile error. Scoping to the declaring module plus every module holding a reference is
 * therefore complete — no reference exists outside that set.
 *
 * `test-compile` rather than `compile` because every reference is in test sources. `-pl` without
 * `-am` because the harness prewarm already installed the siblings, and `-am` OOM-kills the
 * container.
 */
fun buildCompileGateScript(projectDir: String): String {
    // Single source of truth for the goal and the module list — SemanticRippleSpec.compileGateArgs()
    // is what SemanticRippleSpecTest asserts against, so the script cannot drift from it.
    val mavenArgs = SemanticRippleSpec.compileGateArgs().joinToString(" ")
    val jdkPrefix = "/usr/lib/jvm/temurin-${SemanticRippleSpec.projectJdkVersion}-jdk-"
    return """
        set -o pipefail
        cd '$projectDir' || exit 1
        JAVA_HOME="${'$'}(ls -d $jdkPrefix* 2>/dev/null | head -1)"
        if [ -z "${'$'}JAVA_HOME" ]; then
          echo "No JDK found under $jdkPrefix*" >&2
          exit 1
        fi
        if [ ! -x ./mvnw ]; then
          echo "No executable ./mvnw wrapper in $projectDir" >&2
          exit 1
        fi
        export JAVA_HOME
        ./mvnw -o $mavenArgs
    """.trimIndent()
}

/**
 * Run the gate in the container and return its exit code with a bounded log tail.
 *
 * Uses the same `bash -lc` shape as `ArenaVerifier`, so it inherits the container's login
 * environment. Offline (`-o`) because the prewarm already populated `~/.m2`: a gate that reaches the
 * network could fail on a repository outage and read as a missed call site.
 */
fun runCompileGate(container: ContainerDriver, projectDir: String): CompileGateResult {
    val result = container.startProcessInContainer {
        this.args("bash", "-lc", buildCompileGateScript(projectDir))
            .timeoutSeconds(3_600)
            .description("Semantic-ripple scoped compile gate")
    }.awaitForProcessFinish()
    val tail = (result.stdout + "\n" + result.stderr).lines().takeLast(40).joinToString("\n")
    return CompileGateResult(exitCode = result.exitCode, tail = tail)
}
```

Confirm the `ContainerDriver` import path before compiling — it is whatever `ArenaVerification.kt` imports:

```bash
grep -n "import.*ContainerDriver" test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaVerification.kt
```

Use that exact import and delete the guessed one above if it differs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleCompileGateTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleCompileGate.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleCompileGateTest.kt
git commit -m "Add the scoped compile gate for the semantic-ripple track

The FAIL_TO_PASS verifier builds only the module holding its test class, so the
references in the other six modules would never be compiled. For a rename,
compilation is a complete invariant over the ripple, which makes this the layer
that actually covers all 445 call sites."
```

---

### Task 8: Wire the pilot run and take the smoke measurement

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleOracleScripts.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleKeycloakRolesTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–7 — including `runCompileGate` and `CompileGateResult` — plus existing `ArenaTestRunner.runTest`, `ArenaVerifier.verify`, `ArenaVerifier.snapshotTestFiles`, `McpSteroidDriver.mcpExecuteCode`.
- Produces: the pilot run and its printed grade. Nothing downstream.

- [ ] **Step 1: Write the capture and post-condition scripts**

Both scripts print the line format Task 3 already parses. Both refresh the VFS and wait for smart mode first — the post-condition one runs after the agent edited dozens of files, and stale counts would be indistinguishable from a failed rename.

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The two PSI scripts behind the semantic-ripple oracle, kept as plain strings so the parsing and
 * grading in [SemanticRippleOracle] stays pure and unit-tested.
 *
 * Both start by refreshing the VFS and waiting for smart mode. The post-condition script runs after
 * the agent has edited dozens of files, and a stale index would report a correct rename as a failed
 * one — or the reverse.
 *
 * The enclosing-declaration key is the nearest named parent of the reference. Line and offset keys
 * would shift with the agent's edits and produce false misses.
 */
object SemanticRippleOracleScripts {

    private val preamble = """
        import com.intellij.openapi.vfs.VirtualFileManager
        import com.intellij.psi.*
        import com.intellij.psi.search.GlobalSearchScope
        import com.intellij.psi.search.PsiShortNamesCache
        import com.intellij.psi.search.searches.ReferencesSearch
        import com.intellij.psi.util.PsiTreeUtil

        VirtualFileManager.getInstance().asyncRefresh()
        waitForSmartMode()

        fun siteKey(ref: PsiReference): String? {
            val file = ref.element.containingFile?.virtualFile?.path ?: return null
            val owner = PsiTreeUtil.getParentOfType(ref.element, PsiMethod::class.java, false)
                ?.let { m -> (m.containingClass?.qualifiedName ?: "?") + "#" + m.name }
                ?: PsiTreeUtil.getParentOfType(ref.element, PsiClass::class.java, false)
                    ?.qualifiedName
                ?: "<file>"
            return file + "|" + owner
        }
    """.trimIndent()

    /** Emits GOLD_* lines for the target, its reference sites, every decoy, and the new-name check. */
    fun capture(): String = preamble + "\n" + """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val all = cache.getMethodsByName("${SemanticRippleSpec.oldName}", scope).toList()
            val target = all.firstOrNull {
                it.containingClass?.qualifiedName == "${SemanticRippleSpec.targetClassFqn}"
            } ?: error("Target ${SemanticRippleSpec.targetClassFqn}#${SemanticRippleSpec.oldName} not found")
            check(target.containingClass?.isInterface == true) { "Target owner is not an interface" }
            check(target.annotations.any { it.text.contains("Path") }) {
                "Target has no @Path annotation; the rename would not be behaviour-preserving"
            }

            println("GOLD_TARGET ${SemanticRippleSpec.targetClassFqn}|${SemanticRippleSpec.oldName}|${SemanticRippleSpec.newName}")

            val refs = ReferencesSearch.search(target, scope).findAll()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("GOLD_SITE " + key + "|" + n) }

            for (decoy in all) {
                if (decoy === target) continue
                val owner = decoy.containingClass?.qualifiedName ?: continue
                println("GOLD_DECOY " + owner + "|" + ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("GOLD_NEWNAME_DECLS " +
                (cache.getMethodsByName("${SemanticRippleSpec.newName}", scope).size +
                 cache.getClassesByName("${SemanticRippleSpec.newName}", scope).size))
            println("GOLD_END")
        }
    """.trimIndent()

    /** Emits POST_* lines: the alias check, the new method's sites, and every decoy's count. */
    fun postcondition(): String = preamble + "\n" + """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val owner = JavaPsiFacade.getInstance(project)
                .findClass("${SemanticRippleSpec.targetClassFqn}", scope)
                ?: error("${SemanticRippleSpec.targetClassFqn} no longer exists")

            val renamed = owner.findMethodsByName("${SemanticRippleSpec.newName}", false).firstOrNull()
            val declaredWithRightType = renamed != null &&
                renamed.returnType?.presentableText == "${SemanticRippleSpec.targetReturnTypeSimpleName}"
            println("POST_NEWNAME_DECLARED " + declaredWithRightType)
            println("POST_OLDNAME_ON_TARGET " +
                owner.findMethodsByName("${SemanticRippleSpec.oldName}", false).size)

            val refs = renamed?.let { ReferencesSearch.search(it, scope).findAll() } ?: emptyList()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("POST_SITE " + key + "|" + n) }

            for (decoy in cache.getMethodsByName("${SemanticRippleSpec.oldName}", scope)) {
                val decoyOwner = decoy.containingClass?.qualifiedName ?: continue
                if (decoyOwner == "${SemanticRippleSpec.targetClassFqn}") continue
                println("POST_DECOY " + decoyOwner + "|" +
                    ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("POST_TOTAL_NEW_REFS " + refs.size)
            println("POST_END")
        }
    """.trimIndent()
}
```

`waitForSmartMode()` is a `McpScriptContext` helper — confirm it exists before running:

```bash
grep -rn "fun waitForSmartMode" ij-plugin/src/main/kotlin | head -3
```

If it does not exist, replace that line with `com.intellij.platform.backend.observation.Observation.awaitConfiguration(project)` and add the import to the preamble. Do not skip the wait.

- [ ] **Step 2: Write the pilot test**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The semantic-ripple pilot: one cross-module rename on Keycloak, run in both arms.
 *
 * A sibling of [DpaiaScenarioBaseTest] rather than a subclass — that class loads its case from the
 * dpaia dataset and takes a whole-suite regression baseline, and neither applies here. Regression
 * evidence is the scoped compile gate instead, which for a rename is a complete invariant: a missed
 * call site is a compile error by construction.
 *
 * Reporting goes through [collectRunMetrics] and [writeArenaRunSummary], the same code the DPAIA
 * cases use, so the two tracks' numbers stay comparable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticRippleKeycloakRolesTest {

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = runArm("claude", withMcp = true)

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = runArm("claude", withMcp = false)

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = runArm("codex", withMcp = true)

    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = runArm("codex", withMcp = false)

    private fun runArm(agentName: String, withMcp: Boolean) {
        val testCase = SemanticRippleCases.pilotCase()
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ripple-roles-$modeLabel",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = SemanticRippleSpec.cloneUrl,
                    repoOwnerAndName = SemanticRippleSpec.repoOwnerAndName,
                    baseCommit = testCase.baseCommit,
                    testPatch = testCase.testPatch,
                    displayName = testCase.instanceId,
                    buildSystem = testCase.buildSystem,
                ),
                aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
                mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                timeoutMillis = SemanticRippleSpec.projectReadyTimeoutMs,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                // The patched tree deliberately does not compile: the hidden consumer calls a method
                // that does not exist yet. The prewarm build is a warm-up, never an assertion.
                requireCleanCompile = false,
            )

            val projectDir = session.intellijDriver.getGuestProjectDir()

            // Gold BEFORE the agent. The IDE runs in both arms — withMcp only controls whether the
            // AGENT may reach it — so the shell arm is measured without being given any access.
            val goldOutput = session.mcpSteroid.mcpExecuteCode(
                code = SemanticRippleOracleScripts.capture(),
                reason = "Capture the pre-agent resolved reference set for the semantic-ripple oracle",
                taskId = "semantic-ripple-gold",
                timeout = 900,
            ).stdout
            val gold = parseSemanticGold(goldOutput)
            gold.checkTripwires()
            println("[RIPPLE] gold: ${gold.totalReferences} references, ${gold.files} files, " +
                "${gold.decoyReferences.size} decoys")

            val verifier = ArenaVerifier(session.scope, projectDir, testCase.buildSystem)
            val preAgentSnapshot = verifier.snapshotTestFiles(testCase.testPatch)

            val runner = ArenaTestRunner(container = session.scope, projectGuestDir = projectDir)
            val result = runner.runTest(
                testCase = testCase,
                agent = when (agentName) {
                    "claude" -> session.aiAgents.claude
                    "codex" -> session.aiAgents.codex
                    else -> error("Unknown agent: $agentName")
                },
                withMcp = withMcp,
                timeoutSeconds = SemanticRippleSpec.agentTimeoutSeconds,
                predeployedProjectDir = projectDir,
                logDir = session.runDirInContainer,
            )

            val postOutput = session.mcpSteroid.mcpExecuteCode(
                code = SemanticRippleOracleScripts.postcondition(),
                reason = "Grade the post-agent semantic state for the semantic-ripple oracle",
                taskId = "semantic-ripple-post",
                timeout = 900,
            ).stdout
            val grade = parseSemanticPostcondition(postOutput, gold)

            // The layer that covers all 445 call sites: a site the agent missed still names a method
            // that no longer exists, so it cannot compile.
            val gate = runCompileGate(session.scope, projectDir)

            val verification = verifier.verify(
                failToPass = testCase.failToPass,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                testPatch = testCase.testPatch,
                preAgentSnapshot = preAgentSnapshot,
                baseline = FullSuiteSnapshot(perClass = emptyList(), mavenExitCode = 0),
            )

            val metrics = collectRunMetrics(
                runDir = session.runDirInContainer,
                agentName = agentName,
                fallbackStdout = result.agentResult.stdout,
            )
            val record = DpaiaScenarioBaseTest.RunRecord(
                instanceId = testCase.instanceId,
                agentName = agentName,
                withMcp = withMcp,
                agentDurationMs = result.agentDurationMs,
                prewarmMs = 0L,
                exitCode = result.agentResult.exitCode,
                claimedFix = result.evaluation.agentClaimedFix,
                usedMcpSteroid = result.evaluation.usedMcpSteroid,
                summary = result.evaluation.agentSummary,
                tokenUsage = metrics.tokenUsage,
                testMetrics = metrics.testMetrics,
                decodedLogMetrics = metrics.decodedLogMetrics,
                verification = verification,
                runDirPath = session.runDirInContainer.absolutePath,
            )
            writeArenaRunSummary(testCase.instanceId, agentName, modeLabel, record)

            println("[RIPPLE] ════════════════════════════════════════")
            println("[RIPPLE] $agentName+$modeLabel — ${testCase.instanceId}")
            println("[RIPPLE]   P1 no alias:     ${grade.p1NoAliasAndNewNameDeclared}")
            println("[RIPPLE]   P2 all sites:    ${grade.p2AllSitesConverted}")
            println("[RIPPLE]   P3 decoys kept:  ${grade.p3DecoysUnchanged}")
            println("[RIPPLE]   P4 conserved:    ${grade.p4Conserved}")
            println("[RIPPLE]   recall:          ${"%.4f".format(grade.recall)}")
            println("[RIPPLE]   precision:       ${"%.4f".format(grade.precision)}")
            println("[RIPPLE]   f1:              ${"%.4f".format(grade.f1)}")
            println("[RIPPLE]   missed sites:    ${grade.missedSites.size}")
            println("[RIPPLE]   over-reached:    ${grade.overReachedDecoys}")
            println("[RIPPLE]   compile gate:    ${if (gate.passed) "PASS" else "FAIL (exit ${gate.exitCode})"}")
            println("[RIPPLE]   verified FTP:    ${verification.classesPassed}/${verification.classesTotal}")
            println("[RIPPLE]   agent time:      ${record.agentDurationMs / 1000}s")
            val success = gate.passed && verification.objectiveSuccess && grade.allPassed
            println("[RIPPLE]   SUCCESS:         $success")
            println("[RIPPLE] ════════════════════════════════════════")
            if (!gate.passed) {
                println("[RIPPLE] compile gate tail:\n${gate.tail}")
            }

            // The run is a measurement, not a pass/fail on the agent's competence: a shell arm scoring
            // 0.0 recall is the expected positive-control outcome, not a broken test. Only an invalid
            // MEASUREMENT fails the test.
            assertTrue(!verification.failToPassTampered) {
                "[$agentName+$modeLabel] the agent modified the FAIL_TO_PASS file, so the grade measures " +
                    "tests it rewrote. Run invalid."
            }
            if (withMcp) {
                assertTrue(result.evaluation.usedMcpSteroid) {
                    "[$agentName+mcp] never called steroid_execute_code, so this is not an mcp-arm run"
                }
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
```

- [ ] **Step 3: Compile before running anything in Docker**

Run: `./gradlew :test-experiments:compileTestKotlin`
Expected: BUILD SUCCESSFUL. Fix signature mismatches here — `ArenaVerifier.verify`'s and `FullSuiteSnapshot`'s exact parameter names are in `ArenaVerification.kt`, and a wrong one costs a whole container run to discover.

- [ ] **Step 4: Smoke the mcp arm**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleKeycloakRolesTest.claude with mcp*' --rerun-tasks`

Expected: the gold line prints `445 references, 79 files, 16 decoys`. If the tripwire throws instead, read its message — it names which count disagreed — and do not weaken the check; re-measure and update `SemanticRippleSpec` only if the disagreement is explained by the commit or the clone depth.

- [ ] **Step 5: Smoke the none arm**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleKeycloakRolesTest.claude without mcp*' --rerun-tasks`

Never start this while the previous run is still alive.

- [ ] **Step 6: Judge the smoke against the pre-registered outcomes**

From the spec, decided before the run and not to be revised now:

- both arms at 0, or both at 100 → the task is uncalibrated and carries no information; report that, and take the calibration question back to the spec rather than editing thresholds here;
- prewarm, clone or compile did not fit → blocked on infrastructure, which says nothing about the hypothesis;
- IDE arm passes and the shell arm is strictly lower on recall → informative; proceed to the full run.

- [ ] **Step 7: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleOracleScripts.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/SemanticRippleKeycloakRolesTest.kt
git commit -m "Wire the semantic-ripple pilot run on Keycloak

Gold is captured by PSI before the agent and re-queried after, in both arms: the
IDE is present either way, only the agent's access to it differs. Grades report
recall and precision per run, because a binary pass/fail is what hid the arm
difference on the dpaia cases."
```

- [ ] **Step 8: Full run, only if the smoke was informative**

Three passes per arm per agent, one at a time, labelling each pass so the CSV rows stay separable:

```bash
for pass in 1 2 3; do
  for method in 'claude with mcp' 'claude without mcp' 'codex with mcp' 'codex without mcp'; do
    ./gradlew :test-experiments:test \
      --tests "*SemanticRippleKeycloakRolesTest.$method*" \
      -Darena.pass.label="ripple-p$pass" --rerun-tasks
  done
done
```

`arena.pass.label` is what keeps three runs from collapsing into one row — see `CLAUDE.local.md`.

---

## Notes for the executor

- Tasks 2 through 7 are pure JVM work: no Docker, seconds per test cycle. Do them in order and commit each.
- Task 1 gates everything. If it fails, stop and report rather than proceeding.
- The four oracle layers map to code as: compile gate → Task 7 `runCompileGate`; hidden consumer → Task 5 patch, graded by `ArenaVerifier.verify`; P1–P4 → Task 3 parsers fed by Task 8's scripts. `success` in Task 8 is the conjunction the spec defines; nothing else may be called success.
- Tasks 4 and 5 are coupled through `problemStatement()`: the purity contract test covers the statement because it is a slice of the prompt. If you change the prompt's section headings, that slice breaks — the `SemanticRippleCasesTest` assertion on the statement will catch it.
- Do not add a whole-suite baseline to this track to "match DPAIA". The spec rejects it deliberately; `FullSuiteSnapshot(emptyList(), 0)` is passed because `usableAsBaseline` treats an empty-but-successful snapshot as valid, which yields "no regression evidence" rather than a false "zero regressions".
