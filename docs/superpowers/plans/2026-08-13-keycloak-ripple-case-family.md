# Keycloak Ripple Case Family Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the one-target Keycloak semantic-ripple pilot into a family of eight cases spanning four transformation kinds, so the arena measures *what kind* of semantic pressure separates the `mcp` arm from the `none` arm rather than only *whether* it can.

**Architecture:** A measurement-first pipeline. A survey tool queries Keycloak's PSI once and prints ranked, qualified targets per transformation kind; those numbers become pinned constants. The pilot's single-target `SemanticRippleSpec` becomes a `RippleCase` data class whose transformation kind is a sealed `RippleTarget` variant contributing three things — a gold-capture script fragment, a post-condition script fragment, and kind-specific predicates. Everything else (sites, decoys, conservation, compile gate, reactor install, prompt scaffolding, reporting) stays shared and unchanged. The seam is cut on the third case, not the first, so its shape is observed rather than guessed.

**Tech Stack:** Kotlin 2.3.20, JUnit 5 (`org.junit.jupiter`), Gradle 9.6.1, IntelliJ Platform PSI APIs via `steroid_execute_code`, Docker-in-test via `:test-experiments` infrastructure, TeamCity DSL in the separate `~/Work/mcp-steroid-teamcity` repository.

**Spec:** `docs/superpowers/specs/2026-08-13-keycloak-ripple-case-family-design.md` — read it before starting. Its predecessor `2026-08-11-keycloak-semantic-ripple-pilot-design.md` describes the pilot this extends. Neither spec nor this plan is committed to git.

## Global Constraints

- **Work in the existing worktree** `/Users/matvei.ludzskii/work/mcp-steroid/.claude/worktrees/semantic-ripple-pilot` (branch `worktree-semantic-ripple-pilot`). The pilot's 14 files live there and nowhere else. Do not create a new worktree.
- Base commit stays the pilot's: `60c4d5e9321ff5462a772ceb896f8cb2e639e04b` on `https://github.com/keycloak/keycloak.git`. Every measured constant is valid only at that commit.
- Package for all new Kotlin files: `com.jonnyzzz.mcpSteroid.integration.arena`.
- Every new file starts with the repo copyright header: `/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */`
- Banned in this repo: the `internal` modifier; `runCatching{}.onFailure{}`; empty or silent `catch`; returning a `(value, errorFlag)` pair; `@Suppress("DEPRECATION")`; `-am` in any Maven invocation; `ProcessBuilder("mvn")` inside `steroid_execute_code`.
- **Selection thresholds** (from the spec, applied by the survey, never relaxed afterwards): *wide* = ≥100 resolved references over ≥20 files and ≥3 modules; *narrow* = 5–20 resolved references over ≤3 files; both members of a pair need ≥3 other declarations sharing the simple name; the pull-up target needs ≥8 implementing or extending types below its supertype.
- Never run two Docker tests concurrently. Any `:test-experiments` Docker test runs alone, one method at a time, with `--rerun-tasks`.
- **Never run an agent-driven test locally.** Locally permitted: unit tests, `compileTestKotlin`, and agentless Docker runs — `aiMode = AiMode.NONE` with `mcpConnectionMode = McpConnectionMode.None`, which is what the survey and the prewarm probe are. Every run that launches Claude or Codex inside the container — every `*RippleTest.<agent> with/without mcp` method — is a TeamCity build and belongs to Task 8. A local arm burns API budget, competes with the IDE container for RAM, and yields numbers that are not comparable with the TC rounds the whitepaper is built from. Tasks 2–6 therefore verify with unit tests and `compileTestKotlin` only, and never invoke a run-test method.
- Unit tests run as `./gradlew :test-experiments:test --tests '<pattern>'` and must finish in seconds.
- Commit messages state what and why. Never mention AI or add an AI co-author.
- Do not push to any remote and do not trigger TeamCity builds without asking. Triggering is a separate, explicitly-approved step (Task 8).

---

### Task 1: The target survey — measurement before any case exists

This is the go/no-go for every slot. It writes no code a later task throws away: the survey stays as the tool that re-qualifies targets whenever the base commit moves.

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleTargetSurveyScripts.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleTargetSurvey.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleTargetSurveyTest.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/KeycloakRippleTargetSurveyTest.kt`

**Interfaces:**
- Consumes: `IntelliJContainer.create`, `IntelliJContainerOpts`, `IntelliJProject.ProjectFromGitCommitAndPatch`, `waitForProjectReady`, `CloseableStackHost`, `McpSteroidDriver.mcpExecuteCode`, `SemanticRippleSpec` — all existing.
- Produces:
  - `data class SurveyCandidate(val kind: String, val ownerFqn: String, val name: String, val references: Int, val files: Int, val modules: Int, val sameNameDeclarations: Int, val hierarchyBreadth: Int)`
  - `fun parseSurveyCandidates(output: String): List<SurveyCandidate>`
  - `fun SurveyCandidate.qualifiesAsWide(): Boolean`, `fun SurveyCandidate.qualifiesAsNarrow(): Boolean`, `fun SurveyCandidate.qualifiesForPullUp(): Boolean`
  - `object RippleTargetSurveyScripts` with `fun survey(): String`

- [ ] **Step 1: Write the failing parser test**

Create `RippleTargetSurveyTest.kt`:

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RippleTargetSurveyTest {

    private val output = """
        SURVEY_CANDIDATE rename-type|org.keycloak.a.Wide|Wide|312|41|5|4|0
        SURVEY_CANDIDATE rename-type|org.keycloak.a.Narrow|Narrow|8|2|1|5|0
        SURVEY_CANDIDATE pull-up|org.keycloak.a.Deep|handle|140|33|6|3|12
        SURVEY_CANDIDATE rename-type|org.keycloak.a.Unqualified|Unqualified|312|41|5|1|0
        SURVEY_END
    """.trimIndent()

    @Test
    fun `candidates parse with every measured field`() {
        val candidates = parseSurveyCandidates(output)
        assertEquals(4, candidates.size)
        val wide = candidates.first()
        assertEquals("rename-type", wide.kind)
        assertEquals("org.keycloak.a.Wide", wide.ownerFqn)
        assertEquals(312, wide.references)
        assertEquals(41, wide.files)
        assertEquals(5, wide.modules)
        assertEquals(4, wide.sameNameDeclarations)
        assertEquals(0, wide.hierarchyBreadth)
    }

    @Test
    fun `truncated output fails loudly instead of parsing as a short list`() {
        val truncated = output.lines().dropLast(1).joinToString("\n")
        val e = assertThrows(IllegalStateException::class.java) { parseSurveyCandidates(truncated) }
        assertTrue(e.message!!.contains("SURVEY_END")) { "Message must name the missing terminator: ${e.message}" }
    }

    @Test
    fun `wide requires fan-out AND lexical ambiguity`() {
        val byName = parseSurveyCandidates(output).associateBy { it.name }
        assertTrue(byName.getValue("Wide").qualifiesAsWide())
        assertFalse(byName.getValue("Unqualified").qualifiesAsWide()) {
            "A candidate with only 1 same-name declaration is not lexically ambiguous and must not qualify"
        }
        assertFalse(byName.getValue("Narrow").qualifiesAsWide())
    }

    @Test
    fun `narrow requires a small fan-out and still requires ambiguity`() {
        val byName = parseSurveyCandidates(output).associateBy { it.name }
        assertTrue(byName.getValue("Narrow").qualifiesAsNarrow())
        assertFalse(byName.getValue("Wide").qualifiesAsNarrow())
    }

    @Test
    fun `pull-up requires hierarchy breadth on top of a wide fan-out`() {
        val byName = parseSurveyCandidates(output).associateBy { it.name }
        assertTrue(byName.getValue("handle").qualifiesForPullUp())
        assertFalse(byName.getValue("Wide").qualifiesForPullUp()) {
            "A wide candidate with breadth 0 exercises no hierarchy and must not qualify"
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.RippleTargetSurveyTest'`
Expected: compilation failure — `Unresolved reference: parseSurveyCandidates`.

- [ ] **Step 3: Write the parser and the qualifiers**

Create `RippleTargetSurvey.kt`:

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * One transformation target the survey measured, with every number a case's tripwires will pin.
 *
 * [hierarchyBreadth] is 0 for kinds that do not use it; only pull-up candidates carry a real count.
 */
data class SurveyCandidate(
    val kind: String,
    val ownerFqn: String,
    val name: String,
    val references: Int,
    val files: Int,
    val modules: Int,
    /** Other project declarations sharing this simple name — the lexical-ambiguity axis. */
    val sameNameDeclarations: Int,
    val hierarchyBreadth: Int,
)

/**
 * Parse the survey script's output.
 *
 * Requires the `SURVEY_END` terminator for the same reason the gold parser does: a truncated script
 * would otherwise produce a shorter list that reads as a complete measurement, and a target would be
 * chosen from candidates the script never finished ranking.
 */
fun parseSurveyCandidates(output: String): List<SurveyCandidate> {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "SURVEY_END" }) {
        "Survey output has no SURVEY_END terminator — the script was truncated or failed:\n$output"
    }
    return lines.filter { it.startsWith("SURVEY_CANDIDATE ") }.map { line ->
        val parts = line.removePrefix("SURVEY_CANDIDATE ").split('|')
        check(parts.size == 8) { "Malformed SURVEY_CANDIDATE line: $line" }
        SurveyCandidate(
            kind = parts[0],
            ownerFqn = parts[1],
            name = parts[2],
            references = parts[3].toInt(),
            files = parts[4].toInt(),
            modules = parts[5].toInt(),
            sameNameDeclarations = parts[6].toInt(),
            hierarchyBreadth = parts[7].toInt(),
        )
    }
}

/** Lexical ambiguity is required of BOTH members of a wide/narrow pair, so the pair varies fan-out alone. */
private const val MIN_SAME_NAME_DECLARATIONS = 3

fun SurveyCandidate.qualifiesAsWide(): Boolean =
    references >= 100 && files >= 20 && modules >= 3 && sameNameDeclarations >= MIN_SAME_NAME_DECLARATIONS

fun SurveyCandidate.qualifiesAsNarrow(): Boolean =
    references in 5..20 && files <= 3 && sameNameDeclarations >= MIN_SAME_NAME_DECLARATIONS

fun SurveyCandidate.qualifiesForPullUp(): Boolean = qualifiesAsWide() && hierarchyBreadth >= 8
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.RippleTargetSurveyTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Write the survey PSI script**

Create `RippleTargetSurveyScripts.kt`. It reuses the pilot's preamble shape (VFS refresh, smart mode) and prints one `SURVEY_CANDIDATE` line per candidate. Candidate pools are deliberately bounded so the script cannot run for hours on a 189-module project: the 200 most-referenced project classes for type kinds, and the methods of the 50 most-referenced interfaces for method kinds.

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The PSI script that picks the family's targets by measurement.
 *
 * It exists because guessing a target is expensive to get wrong: the pilot's obvious destination name
 * `realmRoles` turned out to be declared five times, which only a PSI query revealed. Everything the
 * case registry pins — references, files, modules, same-name declarations, hierarchy breadth — is
 * printed here, so a registry constant can always be traced to a run of this script.
 *
 * Candidate pools are bounded on purpose. An exhaustive `ReferencesSearch` over every declaration in
 * a 189-module project does not finish inside any timeout this harness allows; the pools below are
 * the widest that do.
 */
object RippleTargetSurveyScripts {

    fun survey(): String = """
        import com.intellij.openapi.module.ModuleUtilCore
        import com.intellij.openapi.vfs.VirtualFileManager
        import com.intellij.psi.*
        import com.intellij.psi.search.GlobalSearchScope
        import com.intellij.psi.search.PsiShortNamesCache
        import com.intellij.psi.search.searches.ClassInheritorsSearch
        import com.intellij.psi.search.searches.ReferencesSearch

        VirtualFileManager.getInstance().asyncRefresh()
        waitForSmartMode()

        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)

            fun emit(kind: String, owner: String, name: String, element: PsiElement, breadth: Int, sameName: Int) {
                val refs = ReferencesSearch.search(element, scope).findAll()
                if (refs.isEmpty()) return
                val files = refs.mapNotNull { it.element.containingFile?.virtualFile?.path }.toSet()
                val modules = refs.mapNotNull {
                    it.element.containingFile?.virtualFile?.let { f -> ModuleUtilCore.findModuleForFile(f, project)?.name }
                }.toSet()
                println("SURVEY_CANDIDATE " + kind + "|" + owner + "|" + name + "|" +
                    refs.size + "|" + files.size + "|" + modules.size + "|" + sameName + "|" + breadth)
            }

            val classNames = cache.allClassNames.toList()
            for (simpleName in classNames) {
                val classes = cache.getClassesByName(simpleName, scope)
                if (classes.size < 2) continue      // no lexical ambiguity, skip early
                for (candidate in classes) {
                    val fqn = candidate.qualifiedName ?: continue
                    val breadth = if (candidate.isInterface)
                        ClassInheritorsSearch.search(candidate, scope, true).findAll().size else 0
                    emit("rename-type", fqn, simpleName, candidate, 0, classes.size - 1)
                    emit("move-class", fqn, simpleName, candidate, 0, classes.size - 1)
                    if (candidate.isInterface) {
                        for (method in candidate.methods) {
                            val sameName = cache.getMethodsByName(method.name, scope).size - 1
                            if (sameName < 3) continue
                            emit("change-signature", fqn, method.name, method, 0, sameName)
                            emit("pull-up", fqn, method.name, method, breadth, sameName)
                        }
                    }
                }
            }
            println("SURVEY_END")
        }
    """.trimIndent()
}
```

- [ ] **Step 6: Write the Docker survey test**

Create `KeycloakRippleTargetSurveyTest.kt`. It reuses the pilot's container setup verbatim — same clone URL, same commit, same JDK, same prewarm timeout — so a survey number is measured in the same environment a case will run in.

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
 * Selects the ripple family's targets by measuring Keycloak, and is the go/no-go for every slot: a
 * kind with no qualifying candidate is reported empty rather than filled with an easier target.
 *
 * No agent, no oracle, no grading — this run only prints. Its output is transcribed into the case
 * registry as pinned constants, and `RippleCaseRegistryTest` later asserts the registry matches what
 * was transcribed.
 */
class KeycloakRippleTargetSurveyTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `survey keycloak for ripple targets of every kind`() {
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ripple-target-survey",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = SemanticRippleSpec.cloneUrl,
                    repoOwnerAndName = SemanticRippleSpec.repoOwnerAndName,
                    baseCommit = SemanticRippleSpec.baseCommit,
                    testPatch = "",
                    displayName = "keycloak-ripple-survey",
                    buildSystem = "maven",
                ),
                aiMode = AiMode.NONE,
                mcpConnectionMode = McpConnectionMode.None,
                mountDockerSocket = false,
            )).waitForProjectReady(
                timeoutMillis = SemanticRippleSpec.projectReadyTimeoutMs,
                projectJdkVersion = SemanticRippleSpec.projectJdkVersion,
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
                requireCleanCompile = false,
            )

            val output = session.mcpSteroid.mcpExecuteCode(
                code = RippleTargetSurveyScripts.survey(),
                reason = "Survey Keycloak for qualifying ripple targets of every transformation kind",
                taskId = "ripple-target-survey",
                timeout = 3_600,
            ).stdout

            val candidates = parseSurveyCandidates(output)
            assertTrue(candidates.isNotEmpty()) { "The survey found no candidates at all:\n$output" }

            fun report(label: String, qualified: List<SurveyCandidate>) {
                println("[SURVEY] $label — ${qualified.size} qualifying")
                qualified.sortedByDescending { it.references }.take(10).forEach {
                    println("[SURVEY]   ${it.ownerFqn}#${it.name} refs=${it.references} files=${it.files} " +
                        "modules=${it.modules} sameName=${it.sameNameDeclarations} breadth=${it.hierarchyBreadth}")
                }
            }

            for (kind in listOf("rename-type", "change-signature", "move-class")) {
                val ofKind = candidates.filter { it.kind == kind }
                report("$kind WIDE", ofKind.filter { it.qualifiesAsWide() })
                report("$kind NARROW", ofKind.filter { it.qualifiesAsNarrow() })
            }
            report("pull-up", candidates.filter { it.kind == "pull-up" && it.qualifiesForPullUp() })
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
```

- [ ] **Step 7: Run the survey**

Run: `./gradlew :test-experiments:test --tests '*KeycloakRippleTargetSurveyTest*' --rerun-tasks`

Expected: PASS with `[SURVEY]` lines. Watch the console; if nothing prints within ~60 s of `> Task :test-experiments:test`, follow the stuck-test recipe in `test-integration/AGENTS.md` ("Debugging a stuck/hung Docker test") before killing anything.

If the script exceeds its 3600 s budget, do **not** widen the timeout first — narrow the pools (drop `move-class`, which measures the same references as `rename-type`, and re-run with type kinds only), then re-add kinds one at a time.

- [ ] **Step 8: Report the chosen targets**

Write the task's report with, for each of the seven slots, the chosen `ownerFqn`, name, and every measured number, in this exact shape so later tasks can copy it verbatim:

```
SLOT 1 rename-type WIDE    owner=<fqn> name=<simple> refs=<n> files=<n> modules=<n> sameName=<n>
SLOT 2 rename-type NARROW  ...
SLOT 3 change-signature WIDE ...
SLOT 4 change-signature NARROW ...
SLOT 5 move-class WIDE ...
SLOT 6 move-class NARROW ...
SLOT 7 pull-up ...        breadth=<n>
```

For every slot also state the chosen destination (new simple name, or new package for a move) and **the evidence that it is free** — the survey prints `sameName` for existing names, so a destination is free when it does not appear among `allClassNames` / `getMethodsByName`. If a slot has no qualifying candidate, write `SLOT n <kind> — NONE QUALIFYING` and say which threshold was missed by the best near-miss. Do not substitute an easier kind.

If **no** slot qualifies, stop and report: tasks 2 onward are worthless without targets.

- [ ] **Step 9: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleTargetSurvey.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleTargetSurveyTest.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleTargetSurveyScripts.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/KeycloakRippleTargetSurveyTest.kt
git commit -m "Select ripple targets by measuring Keycloak instead of guessing

The pilot's destination name was already taken five times over, which only a PSI
query revealed. This prints every number a case pins - references, files, modules,
same-name declarations, hierarchy breadth - so a registry constant can always be
traced back to a run, and a kind with no qualifying target is reported empty
rather than filled with an easier one."
```

---

### Task 2: Case 1 — rename type, wide fan-out

Built by duplicating the pilot's structure. The seam is deliberately NOT extracted here: with one example the shape of the seam is a guess, and the pilot's own design deferred the extraction until a second case exists.

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RenameTypeWideSpec.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RenameTypeWideOracleScripts.kt`
- Create: `test-experiments/src/test/resources/arena-overlays/ripple-keycloak-rename-type-wide.patch`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RenameTypeWideCases.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RenameTypeWidePrompt.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/KeycloakRenameTypeWideRippleTest.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RenameTypeWideCaseTest.kt`

**Interfaces:**
- Consumes: Task 1's SLOT 1 report line; `parseSemanticGold`, `parseSemanticPostcondition`, `SemanticGold`, `GoldSite`, `SemanticPostconditionResult`, `extractPatchFilePaths`, `runCompileGate`, `prepareAndProveGateEnvironment`, `collectRunMetrics`, `writeArenaRunSummary`, `ArenaVerifier`, `ArenaTestRunner`, `DpaiaTestCase` — all existing in the worktree.
- Produces: `object RenameTypeWideSpec` (same field names as `SemanticRippleSpec`, with `oldName`/`newName` holding the type's simple names and `targetClassFqn` the old FQN); `object RenameTypeWideCases` with `fun case(): DpaiaTestCase`, `fun hiddenConsumerFiles(): Set<String>`, `const val hiddenConsumerFqn`; `fun buildRenameTypeWidePrompt(projectDir: String, withMcp: Boolean): String`.

- [ ] **Step 1: Transcribe the SLOT 1 target into a spec**

Create `RenameTypeWideSpec.kt` as a copy of `SemanticRippleSpec.kt` with these substitutions, taking every number from Task 1's SLOT 1 line and nothing from memory:

- `targetClassFqn` = SLOT 1 `owner`
- `oldName` = SLOT 1 `name` (the type's simple name)
- `newName` = the free destination simple name from Task 1's report
- `expectedGoldReferences` = SLOT 1 `refs`; `expectedGoldFiles` = SLOT 1 `files`; `expectedDecoyDeclarations` = SLOT 1 `sameName`
- `compileGateModules` = the reference modules the survey listed, plus the declaring module
- `targetReturnTypeSimpleName` is dropped — a type has no return type. Delete the field rather than leaving it unused.
- Keep `cloneUrl`, `repoOwnerAndName`, `baseCommit`, `reactorProfile`, `projectJdkVersion`, `agentTimeoutSeconds`, `projectReadyTimeoutMs`, `compileGateSelectors`, `gradingScopeSelector`, `reactorInstallArgs()`, `compileGateArgs()` byte-identical to the pilot's, including their KDoc — those are properties of Keycloak and the harness, not of the target.

Replace the class KDoc's behaviour-preservation paragraph with the evidence for THIS target, from Task 1 step 8: the type's name must not appear in any configuration string, `Class.forName`, or `META-INF` service file. State where that was checked.

- [ ] **Step 2: Write the failing case test**

Create `RenameTypeWideCaseTest.kt`. Substitute the real numbers from the spec you just wrote for `<REFS>`, `<FILES>`, `<DECOYS>`, `<MODULES>`:

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenameTypeWideCaseTest {

    @Test
    fun `base commit is the pinned family commit`() {
        assertEquals(SemanticRippleSpec.baseCommit, RenameTypeWideSpec.baseCommit) {
            "Every case of the family measures the same tree; a per-case commit would make the cases " +
                "incomparable with each other"
        }
    }

    @Test
    fun `destination name differs from the old one`() {
        assertNotEquals(RenameTypeWideSpec.oldName, RenameTypeWideSpec.newName)
    }

    @Test
    fun `expected counts are the surveyed ones`() {
        assertEquals(<REFS>, RenameTypeWideSpec.expectedGoldReferences)
        assertEquals(<FILES>, RenameTypeWideSpec.expectedGoldFiles)
        assertEquals(<DECOYS>, RenameTypeWideSpec.expectedDecoyDeclarations)
    }

    @Test
    fun `the target qualifies as wide under the family thresholds`() {
        assertTrue(RenameTypeWideSpec.expectedGoldReferences >= 100)
        assertTrue(RenameTypeWideSpec.expectedGoldFiles >= 20)
        assertTrue(RenameTypeWideSpec.compileGateModules.size >= 4) {
            "Wide means references in at least 3 modules, plus the declaring module"
        }
        assertTrue(RenameTypeWideSpec.expectedDecoyDeclarations >= 3) {
            "Without lexical ambiguity this case measures search cost, not disambiguation"
        }
    }

    @Test
    fun `compile gate names each module once and never asks for also-make`() {
        assertEquals(
            RenameTypeWideSpec.compileGateModules.distinct().size,
            RenameTypeWideSpec.compileGateModules.size,
        )
        assertFalse(RenameTypeWideSpec.compileGateArgs().contains("-am"))
        assertTrue(RenameTypeWideSpec.compileGateArgs().contains("test-compile"))
    }

    @Test
    fun `test patch is purely additive`() {
        val patch = RenameTypeWideCases.testPatch()
        val fileSections = patch.lines().count { it.startsWith("diff --git ") }
        val newFileMarkers = patch.lines().count { it.trim() == "--- /dev/null" }
        assertEquals(fileSections, newFileMarkers) {
            "Every file section must create a new file, or the patch could collide with a file the " +
                "agent must edit"
        }
        assertFalse(patch.contains("deleted file mode"))
    }

    @Test
    fun `hidden consumer lives in test sources of a compile-gate module`() {
        val paths = extractPatchFilePaths(RenameTypeWideCases.testPatch())
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.contains("/src/test/java/") }) { "$paths" }
    }

    @Test
    fun `prompt names the target and leaks neither the mechanism nor the answer`() {
        val prompts = listOf(
            buildRenameTypeWidePrompt("/work/keycloak", withMcp = true),
            buildRenameTypeWidePrompt("/work/keycloak", withMcp = false),
        )
        prompts.forEach { p ->
            assertTrue(p.contains(RenameTypeWideSpec.targetClassFqn)) { p }
            assertTrue(p.contains(RenameTypeWideSpec.newName)) { p }
            assertTrue(p.contains("ARENA_FIX_APPLIED: yes")) { p }
            assertTrue(p.contains("other types")) {
                "The prompt must forbid renaming same-named types elsewhere:\n$p"
            }
            listOf("steroid_", "mcp-steroid://", "find usages", "findUsages", "Find Usages",
                   "PSI", "IntelliJ", "refactor", "Refactor", "IDE").forEach { token ->
                assertFalse(p.contains(token)) { "Prompt leaks the mechanism via '$token':\n$p" }
            }
            assertFalse(p.contains(".java")) { "The prompt must not enumerate affected files:\n$p" }
            assertFalse(p.contains("${RenameTypeWideSpec.expectedGoldReferences}")) {
                "Stating the reference count tells the agent when to stop searching:\n$p"
            }
            assertFalse(p.contains("-am")) { p }
        }
    }

    @Test
    fun `case pins the commit and names the consumer as FAIL_TO_PASS`() {
        val case = RenameTypeWideCases.case()
        assertEquals(RenameTypeWideSpec.baseCommit, case.baseCommit)
        assertEquals("maven", case.buildSystem)
        assertTrue(case.isMaven)
        assertEquals(listOf(RenameTypeWideCases.hiddenConsumerFqn), case.failToPass)
        assertTrue(case.passToPass.isEmpty())
        assertEquals("ripple__keycloak__rename-type-wide", case.instanceId)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*RenameTypeWideCaseTest*'`
Expected: compilation failure — `Unresolved reference: RenameTypeWideCases`.

- [ ] **Step 4: Write the prompt**

Create `RenameTypeWidePrompt.kt` as a copy of `SemanticRipplePrompt.kt` with only the `## Task` section rewritten; every other paragraph — the output requirement, environment facts, the do-not-end-your-turn rule, the do-not-run-the-whole-reactor rule, success markers — is copied byte-for-byte, because those are harness facts shared by the family:

```kotlin
    appendLine("## Task")
    appendLine()
    appendLine("Rename the type")
    appendLine()
    appendLine("    ${RenameTypeWideSpec.targetClassFqn}")
    appendLine()
    appendLine("to the simple name `${RenameTypeWideSpec.newName}`, keeping it in its current package,")
    appendLine("throughout the whole project.")
    appendLine()
    appendLine("Requirements:")
    appendLine()
    appendLine("1. Every place that names this type must name it by its new name — declarations, uses,")
    appendLine("   imports and the file it lives in. When you are done, no reference to this type may")
    appendLine("   still use the old name.")
    appendLine("2. The old name must not survive as an alias of any kind — not as a subtype, not as a")
    appendLine("   deprecated empty interface extending the new one.")
    appendLine("3. Types that happen to share the same simple name in **other** packages are unrelated")
    appendLine("   and MUST keep their current name. Changing one of them is a defect.")
    appendLine("4. External behaviour must not change: this type's name appears in no configuration")
    appendLine("   file, no reflective lookup and no service descriptor, so a correct rename is not")
    appendLine("   observable from outside the code.")
    appendLine("5. The project must compile, test sources included, when you are finished.")
```

- [ ] **Step 5: Write the hidden consumer patch**

Create `test-experiments/src/test/resources/arena-overlays/ripple-keycloak-rename-type-wide.patch`. It adds ONE new file, in `src/test/java` of a module inside `RenameTypeWideSpec.compileGateModules`, in the same package as the target so no import is needed. Substitute the real module path, package and names:

```diff
diff --git a/<module-dir>/src/test/java/<package-path>/RenameTypeContractTest.java b/<module-dir>/src/test/java/<package-path>/RenameTypeContractTest.java
new file mode 100644
--- /dev/null
+++ b/<module-dir>/src/test/java/<package-path>/RenameTypeContractTest.java
@@ -0,0 +1,25 @@
+package <package>;
+
+import org.junit.Assert;
+import org.junit.Test;
+
+/**
+ * Pins the renamed type. The new name must exist and the old one must be gone entirely — an alias
+ * left behind would let every caller keep compiling against a name the contract no longer has.
+ */
+public class RenameTypeContractTest {
+
+    @Test
+    public void newNameExists() throws Exception {
+        Assert.assertNotNull(Class.forName("<new-fqn>"));
+    }
+
+    @Test
+    public void oldNameIsGone() {
+        try {
+            Class.forName("<old-fqn>");
+            Assert.fail("<old-fqn> still resolves; the rename left an alias behind");
+        } catch (ClassNotFoundException expected) {
+            // the old type is gone, which is what this test asserts
+        }
+    }
+}
```

Before continuing, verify by hand — both are properties of the repository, not of our code:

```bash
# JUnit 4 is what this module's tests use, so org.junit.Assert resolves there
grep -rn "org.junit.Test" <worktree>/<module-dir>/src/test/java | head -3
# the path is free
ls <worktree>/<module-dir>/src/test/java/<package-path>/RenameTypeContractTest.java
```

Expected: the first prints at least one hit; the second prints "No such file or directory". If the module has no `src/test/java`, choose the next module from `compileGateModules` that does, and update the patch path, package and `hiddenConsumerFqn` together.

- [ ] **Step 6: Write the case factory**

Create `RenameTypeWideCases.kt` as a copy of `SemanticRippleCases.kt` with `instanceId = "ripple__keycloak__rename-type-wide"`, `PATCH_RESOURCE = "arena-overlays/ripple-keycloak-rename-type-wide.patch"`, `hiddenConsumerFqn` set to the FQN from step 5, and `problemStatement()` slicing `buildRenameTypeWidePrompt`.

- [ ] **Step 7: Run the case test to verify it passes**

Run: `./gradlew :test-experiments:test --tests '*RenameTypeWideCaseTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 8: Write the type-kind oracle scripts**

Create `RenameTypeWideOracleScripts.kt` as a copy of `SemanticRippleOracleScripts.kt` with three changes, and no others:

1. The target lookup uses `cache.getClassesByName(oldName, scope).firstOrNull { it.qualifiedName == targetClassFqn }` instead of `getMethodsByName`.
2. The precondition `check` on `@Path` is replaced by a check that the target resolves and is a project (not library) class.
3. `POST_NEWNAME_DECLARED` is computed from `JavaPsiFacade.findClass(newFqn, scope) != null`, and `POST_OLDNAME_ON_TARGET` from `JavaPsiFacade.findClass(oldFqn, scope)` being non-null (`1`) or null (`0`).

`GOLD_SITE`, `GOLD_DECOY`, `GOLD_NEWNAME_DECLS`, `POST_SITE`, `POST_DECOY`, `POST_TOTAL_NEW_REFS` and both terminators keep their exact shape, so `parseSemanticGold` and `parseSemanticPostcondition` are reused with no change at all.

- [ ] **Step 9: Write the run test**

Create `KeycloakRenameTypeWideRippleTest.kt` as a copy of `KeycloakRenameRippleTest.kt` with `SemanticRippleSpec` → `RenameTypeWideSpec`, `SemanticRippleCases` → `RenameTypeWideCases`, `SemanticRippleOracleScripts` → `RenameTypeWideOracleScripts`, and `consoleTitle = "ripple-rename-type-wide-$modeLabel"`. Keep all four agent methods, the 180-minute timeout and its comment, the MEASUREMENT-LOST branch, the tamper assertion and the `usedMcpSteroid` assertion unchanged.

Add to the class KDoc a sentence naming this as the second case of the family and pointing at `KeycloakRenameRippleTest` as the first, so the duplication is legible as deliberate and temporary — Task 3 removes it.

- [ ] **Step 10: Compile the module**

Run: `./gradlew :test-experiments:compileTestKotlin`
Expected: BUILD SUCCESSFUL. Do not run the Docker test here; the smoke round is Task 8.

- [ ] **Step 11: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RenameType*.kt \
        test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/KeycloakRenameTypeWideRippleTest.kt \
        test-experiments/src/test/resources/arena-overlays/ripple-keycloak-rename-type-wide.patch
git commit -m "Add the wide rename-type case to the ripple family

Type-level ripple travels through imports and file names rather than call sites,
so it exercises a different failure mode than the pilot's method rename while
reusing its gold format, its parsers and its compile gate untouched. Written as a
copy of the pilot on purpose: the shared seam is cut once a third kind shows what
is actually shared."
```

---

### Task 3: The seam — `RippleCase` and `RippleTarget`, extracted while adding change-signature

Two cases show what repeats; the third kind shows what cannot be shared. This task adds change-signature (wide) and extracts the seam in the same breath, then moves the pilot and Task 2's case onto it.

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCase.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleTarget.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCases.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleScenarioBaseTest.kt`
- Create: `test-experiments/src/test/resources/arena-overlays/ripple-keycloak-change-signature-wide.patch`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/KeycloakChangeSignatureWideRippleTest.kt`
- Modify: `KeycloakRenameRippleTest.kt`, `KeycloakRenameTypeWideRippleTest.kt` — reduced to subclasses
- Delete: `RenameTypeWideSpec.kt`, `RenameTypeWideCases.kt`, `RenameTypeWidePrompt.kt`, `RenameTypeWideOracleScripts.kt`, `RenameTypeWideCaseTest.kt`, `SemanticRippleCasesTest.kt`, `SemanticRippleSpecTest.kt` (all absorbed into the registry, the sealed variants and `RippleCaseRegistryTest`)
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCaseRegistryTest.kt`
- Modify: `SemanticRippleOracle.kt` — add the arity predicate

**Interfaces:**
- Consumes: Tasks 1 and 2; the existing oracle, gate, prompt and reporting functions.
- Produces:
  - `sealed interface RippleTarget` with `val kindId: String`, `fun captureFragment(): String`, `fun postconditionFragment(): String`, `fun promptTaskSection(): String`, `fun extraPredicates(output: String): Map<String, Boolean>`
  - variants `RenameMethod`, `RenameType`, `ChangeSignature` (the remaining two arrive in Tasks 5 and 6)
  - `fun buildRipplePrompt(case: RippleCase, projectDir: String, withMcp: Boolean): String` — the pilot's prompt builder with its `## Task` section delegated to `case.target.promptTaskSection()`; every other paragraph stays byte-identical and shared. `buildRenameTypeWidePrompt` is deleted in favour of it; `buildSemanticRipplePrompt` **stays** as a one-line delegate (`= buildRipplePrompt(RippleCases.renameMethodWide, projectDir, withMcp)`) so the pilot's prompt-contract test keeps passing unedited and stays a regression check on the extraction.
  - `data class RippleCase(val instanceId: String, val target: RippleTarget, val expectedGoldReferences: Int, val expectedGoldFiles: Int, val expectedDecoyDeclarations: Int, val compileGateModules: List<String>, val declaringModuleArtifactId: String, val consumerModuleArtifactId: String, val hiddenConsumerFqn: String, val patchResource: String)` with `fun testPatch(): String`, `fun hiddenConsumerFiles(): Set<String>`, `fun dpaiaCase(): DpaiaTestCase`, `fun compileGateArgs(): List<String>`, `fun gradingScopeSelector(): String`
  - `object RippleCases` exposing `val all: List<RippleCase>` and one `val` per case
  - `abstract class RippleScenarioBaseTest` with `abstract val case: RippleCase` and the four agent test methods
  - `fun parseSemanticPostcondition(output, gold, hiddenConsumerFiles, extraPredicates: Map<String, Boolean>)` — the existing overload keeps working by defaulting to an empty map

- [ ] **Step 1: Write the failing registry test**

Create `RippleCaseRegistryTest.kt`. It is the test that keeps eight cases honest at once — every property Task 2 asserted for one case is asserted here for all of them:

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class RippleCaseRegistryTest {

    @Test
    fun `instance ids are unique and identify the track`() {
        val ids = RippleCases.all.map { it.instanceId }
        assertEquals(ids.distinct().size, ids.size) { "Duplicate instance id in $ids" }
        assertTrue(ids.all { it.startsWith("ripple__keycloak__") }) { "$ids" }
    }

    @TestFactory
    fun `every case is well-formed`(): List<DynamicTest> = RippleCases.all.map { case ->
        DynamicTest.dynamicTest(case.instanceId) {
            val patch = case.testPatch()
            assertEquals(
                patch.lines().count { it.startsWith("diff --git ") },
                patch.lines().count { it.trim() == "--- /dev/null" },
            ) { "${case.instanceId}: the test patch must be purely additive" }
            assertFalse(patch.contains("deleted file mode")) { case.instanceId }

            val paths = extractPatchFilePaths(patch)
            assertTrue(paths.isNotEmpty()) { case.instanceId }
            assertTrue(paths.all { it.contains("/src/test/java/") }) { "${case.instanceId}: $paths" }

            assertTrue(case.compileGateModules.contains(case.declaringModuleArtifactId)) {
                "${case.instanceId}: the declaring module must be inside the compile gate"
            }
            assertTrue(case.compileGateModules.contains(case.consumerModuleArtifactId)) {
                "${case.instanceId}: the hidden consumer's module must be inside the compile gate"
            }
            assertEquals(
                case.compileGateModules.distinct().size, case.compileGateModules.size,
            ) { case.instanceId }
            assertFalse(case.compileGateArgs().contains("-am")) { case.instanceId }
            assertTrue(case.compileGateArgs().contains("test-compile")) { case.instanceId }

            assertTrue(case.expectedGoldReferences > 0) { case.instanceId }
            assertTrue(case.expectedGoldFiles > 0) { case.instanceId }
            assertTrue(case.expectedDecoyDeclarations >= 3) {
                "${case.instanceId}: without lexical ambiguity the case measures search cost only"
            }
        }
    }

    @TestFactory
    fun `every prompt names its target and leaks neither mechanism nor answer`(): List<DynamicTest> =
        RippleCases.all.map { case ->
            DynamicTest.dynamicTest(case.instanceId) {
                listOf(true, false).forEach { withMcp ->
                    val p = buildRipplePrompt(case, "/work/keycloak", withMcp)
                    assertTrue(p.contains("ARENA_FIX_APPLIED: yes")) { p }
                    assertTrue(p.contains("other types") || p.contains("other packages")) {
                        "${case.instanceId}: the prompt must forbid touching same-named declarations elsewhere"
                    }
                    listOf("steroid_", "mcp-steroid://", "find usages", "findUsages", "Find Usages",
                           "PSI", "IntelliJ", "refactor", "Refactor", "IDE").forEach { token ->
                        assertFalse(p.contains(token)) { "${case.instanceId} leaks '$token':\n$p" }
                    }
                    assertFalse(p.contains(".java")) { "${case.instanceId} enumerates files:\n$p" }
                    assertFalse(p.contains("${case.expectedGoldReferences}")) {
                        "${case.instanceId} states the reference count:\n$p"
                    }
                    assertFalse(p.contains("-am")) { case.instanceId }
                }
            }
        }

    @Test
    fun `every case shares the family base commit`() {
        assertTrue(RippleCases.all.all { it.dpaiaCase().baseCommit == SemanticRippleSpec.baseCommit })
    }
}
```

- [ ] **Step 2: Write the failing arity-predicate test**

Append to `SemanticRippleOracleTest.kt`:

```kotlin
    @Test
    fun `change-signature arity is a separate predicate and can fail on its own`() {
        val post = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE a/A.java|A#two|1
            POST_SITE b/B.java|B#three|1
            POST_DECOY org.keycloak.admin.client.resource.ClientResource|343
            POST_DECOY org.keycloak.admin.client.resource.UserResource|401
            POST_TOTAL_NEW_REFS 4
            POST_ARITY_EXPECTED 2
            POST_ARITY_MATCHING 3
            POST_END
        """.trimIndent()
        val r = parseSemanticPostcondition(
            post, gold(), emptySet(), extraPredicates = mapOf("P5_ARITY" to parseArityPredicate(post),)
        )
        assertTrue(r.p2AllSitesConverted) { "Every gold site is present; only the arity is wrong" }
        assertFalse(r.extraPredicates.getValue("P5_ARITY")) {
            "3 of 4 call sites carry the new arity, so the signature change is incomplete"
        }
        assertFalse(r.allPassed)
    }

    @Test
    fun `arity predicate passes when every call site carries the new arity`() {
        val post = """
            POST_NEWNAME_DECLARED true
            POST_OLDNAME_ON_TARGET 0
            POST_SITE a/A.java|A#one|2
            POST_SITE a/A.java|A#two|1
            POST_SITE b/B.java|B#three|1
            POST_DECOY org.keycloak.admin.client.resource.ClientResource|343
            POST_DECOY org.keycloak.admin.client.resource.UserResource|401
            POST_TOTAL_NEW_REFS 4
            POST_ARITY_EXPECTED 2
            POST_ARITY_MATCHING 4
            POST_END
        """.trimIndent()
        assertTrue(parseArityPredicate(post))
    }
```

- [ ] **Step 3: Run both tests to verify they fail**

Run: `./gradlew :test-experiments:test --tests '*RippleCaseRegistryTest*' --tests '*SemanticRippleOracleTest*'`
Expected: compilation failure — `Unresolved reference: RippleCases`, `parseArityPredicate`.

- [ ] **Step 4: Add the extra-predicate channel to the oracle**

In `SemanticRippleOracle.kt`, add a field to the result and a parameter to the parser, keeping every existing call site working:

```kotlin
data class SemanticPostconditionResult(
    // ... existing fields unchanged ...
    val excludedConsumerReferences: Int = 0,
    /**
     * Kind-specific predicates contributed by the [RippleTarget] variant — arity for a signature
     * change, FQN movement for a move, supertype ownership for a pull-up. Kept as a map rather than
     * as more boolean fields because P1–P4 are the family's shared contract and each kind adds at
     * most one or two of its own; a field per kind would make every case carry every other kind's
     * vocabulary.
     */
    val extraPredicates: Map<String, Boolean> = emptyMap(),
) {
    val allPassed: Boolean
        get() = p1NoAliasAndNewNameDeclared && p2AllSitesConverted && p3DecoysUnchanged &&
            p4Conserved && extraPredicates.values.all { it }
}

/**
 * True when every resolved call site carries the new arity.
 *
 * Presence of the renamed declaration is not enough for a signature change: an agent can add the
 * parameter to the declaration and leave callers passing the old argument list, which fails to
 * compile — but it can equally add an OVERLOAD, which compiles, keeps every call site resolving, and
 * satisfies P1–P4 while performing no signature change at all. This is the predicate that catches it.
 */
fun parseArityPredicate(output: String): Boolean {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "POST_END" }) {
        "Post-condition output has no POST_END terminator — the script was truncated or failed:\n$output"
    }
    fun field(prefix: String): Int =
        (lines.firstOrNull { it.startsWith(prefix) }
            ?: error("Post-condition output is missing the $prefix field:\n$output"))
            .removePrefix(prefix).trim().toInt()
    val total = field("POST_TOTAL_NEW_REFS ")
    return total > 0 && field("POST_ARITY_MATCHING ") == total
}
```

Add `extraPredicates: Map<String, Boolean> = emptyMap()` as the last parameter of `parseSemanticPostcondition` and pass it through to the returned result.

- [ ] **Step 5: Write the sealed target hierarchy**

Create `RippleTarget.kt`. Each variant owns its script fragments, its prompt task section and its predicates; the shared preamble, site grouping, decoy enumeration and terminators stay in the family-level script builder.

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The transformation a ripple case asks for.
 *
 * A variant contributes exactly three things and nothing else: how the target is found and checked
 * (its capture fragment), what is read back about it afterwards (its post-condition fragment), and
 * how the task is stated to the agent. Sites, decoys, conservation, the compile gate and every
 * environment paragraph are shared by the family and live outside this hierarchy — which is why P2,
 * P3 and P4 need no per-kind code at all.
 */
sealed interface RippleTarget {
    /** Stable id used in reports and instance ids. */
    val kindId: String

    /** The symbol whose references form the gold set, as a Kotlin expression yielding a PsiElement. */
    fun captureFragment(): String

    /** Reads back P1 and any kind-specific POST_ lines. */
    fun postconditionFragment(): String

    /** The `## Task` section of the prompt — the only part of the prompt a kind may change. */
    fun promptTaskSection(): String

    /** Kind-specific predicates, keyed by a stable id, computed from the post-condition output. */
    fun extraPredicates(output: String): Map<String, Boolean> = emptyMap()
}
```

Then add the three variants in the same file. `RenameMethod` and `RenameType` carry the fragments Task 2 and the pilot already have — moved, not rewritten. `ChangeSignature` carries the new ones:

```kotlin
/** The pilot's kind: a method keeps its owner and its signature, and changes its name. */
data class RenameMethod(
    val targetClassFqn: String,
    val oldName: String,
    val newName: String,
    val returnTypeSimpleName: String,
    /** What makes the rename structurally behaviour-preserving, stated for the reader of a failure. */
    val behaviourPreservationEvidence: String,
) : RippleTarget { /* fragments moved from SemanticRippleOracleScripts */ }

/** A type keeps its package and changes its simple name; the ripple travels through imports. */
data class RenameType(
    val oldFqn: String,
    val newSimpleName: String,
    val behaviourPreservationEvidence: String,
) : RippleTarget { /* fragments moved from RenameTypeWideOracleScripts */ }

/**
 * A method gains a parameter. Every call site must be updated with a real argument, which no textual
 * substitution can do — and an added overload, which compiles and would satisfy P1–P4, is caught by
 * the arity predicate.
 */
data class ChangeSignature(
    val targetClassFqn: String,
    val methodName: String,
    val addedParameterType: String,
    val addedParameterName: String,
    val newArity: Int,
    val behaviourPreservationEvidence: String,
) : RippleTarget {
    override val kindId: String get() = "change-signature"
    override fun extraPredicates(output: String): Map<String, Boolean> =
        mapOf("P5_ARITY" to parseArityPredicate(output))
    // captureFragment(): finds the method by name on targetClassFqn, checks its current arity is
    //   newArity - 1, and emits GOLD_TARGET / GOLD_SITE / GOLD_DECOY exactly as the pilot does.
    // postconditionFragment(): emits POST_NEWNAME_DECLARED (a method of this name with newArity
    //   parameters exists), POST_OLDNAME_ON_TARGET (count of same-named methods with the OLD arity
    //   still declared on the owner — non-zero means an overload was added instead of a change),
    //   the POST_SITE / POST_DECOY lines, POST_TOTAL_NEW_REFS, then POST_ARITY_EXPECTED and
    //   POST_ARITY_MATCHING (call sites whose argument count equals newArity).
}
```

Write out the full fragment bodies — this plan names their contract, and the implementer writes the Kotlin by moving the existing pilot / Task 2 fragments and following the comment above for `ChangeSignature`.

- [ ] **Step 6: Write the case model and the registry**

Create `RippleCase.kt` (the data class from **Interfaces**, with `testPatch()`, `hiddenConsumerFiles()`, `dpaiaCase()`, `compileGateArgs()`, `gradingScopeSelector()` moved verbatim from `SemanticRippleSpec` / `SemanticRippleCases`, parameterised on the case) and `RippleCases.kt` holding the pilot, Task 2's case, and the new change-signature-wide case built from Task 1's SLOT 3 line.

Keep the family-wide constants — `cloneUrl`, `repoOwnerAndName`, `baseCommit`, `reactorProfile`, `projectJdkVersion`, `agentTimeoutSeconds`, `projectReadyTimeoutMs`, `reactorInstallArgs()` — in `SemanticRippleSpec`, which becomes the family's environment object rather than a target spec. Delete from it every field that described the pilot's target; those now live in the pilot's `RippleCase`.

- [ ] **Step 7: Write the hidden consumer patch for change-signature**

Create `ripple-keycloak-change-signature-wide.patch`, same additive shape as Task 2 step 5, asserting by reflection that a method with the NEW parameter list exists and that no method of the same name with the OLD parameter list survives:

```java
+    @Test
+    public void newSignatureExists() throws Exception {
+        Assert.assertNotNull(<Owner>.class.getMethod("<method>", <newParamTypes>));
+    }
+
+    @Test
+    public void oldSignatureIsGone() {
+        try {
+            <Owner>.class.getMethod("<method>", <oldParamTypes>);
+            Assert.fail("<Owner> still declares the old signature; an overload is not a signature change");
+        } catch (NoSuchMethodException expected) {
+            // the old signature is gone, which is what this test asserts
+        }
+    }
```

Run the same two by-hand checks as Task 2 step 5 (JUnit 4 imports resolve in that module; the path is free).

- [ ] **Step 8: Extract the run test base class**

Create `RippleScenarioBaseTest.kt` by moving `KeycloakRenameRippleTest.runArm` into it verbatim, with `SemanticRippleSpec.<target field>` replaced by `case.<field>` and the oracle scripts taken from `case.target`. Keep every comment: the MEASUREMENT-LOST branch, the normalize-before-snapshot note, the reactor-install note and the "a run is a measurement, not a pass/fail" note all record measured incidents and must not be summarised away.

Then reduce the three run tests to:

```kotlin
class KeycloakRenameRippleTest : RippleScenarioBaseTest() {
    override val case: RippleCase get() = RippleCases.renameMethodWide
}
```

with their class KDoc kept, and add `KeycloakChangeSignatureWideRippleTest` in the same shape.

- [ ] **Step 9: Run the whole non-Docker suite**

Run: `./gradlew :test-experiments:test --tests '*Ripple*' --tests '*SemanticRipple*'`
Expected: PASS. **`SemanticRippleOracleTest` and `SemanticRipplePromptContractTest` must pass without being edited** — they cover the shared grading logic and the shared prompt scaffolding, which the extraction must not change; the only permitted edit is the two arity tests added in step 2. Keeping `buildSemanticRipplePrompt` as a delegate is what makes the prompt test a real regression check rather than a formality.

`SemanticRippleCasesTest` and `SemanticRippleSpecTest` are the exception, and they are **deleted, not weakened**: every assertion they make about the pilot's target data is now made for all cases at once by `RippleCaseRegistryTest`. Before deleting them, check each assertion off against a `RippleCaseRegistryTest` counterpart and name any that has none — an assertion with no counterpart means the registry test is missing coverage, and it gets added there rather than dropped.

Also verify the pilot still resolves in the reduced spec: `grep -rn "SemanticRippleSpec\." test-experiments/src/test` must return only the family-wide fields (`cloneUrl`, `repoOwnerAndName`, `baseCommit`, `reactorProfile`, `projectJdkVersion`, `agentTimeoutSeconds`, `projectReadyTimeoutMs`, `reactorInstallArgs`). A hit on a deleted target field means a call site was missed.

- [ ] **Step 10: Commit**

```bash
git add -A test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena \
          test-experiments/src/test/resources/arena-overlays
git commit -m "Cut the ripple seam on the third kind, and add change-signature

Three cases show what is shared and what cannot be: sites, decoys, conservation,
the gate and every environment paragraph are family-wide, while finding the target
and reading P1 back are per-kind. Change-signature is the kind that forced the
extra-predicate channel - an added overload satisfies P1 to P4 while performing no
signature change, and only per-site arity catches it. The pilot's unit tests pass
unedited, which is the evidence the extraction changed no behaviour."
```

---

### Task 4: Cases 2 and 4 — the narrow members of the two existing kinds

With the seam in place a case is data plus a patch. These two exist to vary fan-out and nothing else, so they must keep the lexical ambiguity of their wide twins.

**Files:**
- Create: `test-experiments/src/test/resources/arena-overlays/ripple-keycloak-rename-type-narrow.patch`
- Create: `test-experiments/src/test/resources/arena-overlays/ripple-keycloak-change-signature-narrow.patch`
- Modify: `RippleCases.kt` — add `renameTypeNarrow`, `changeSignatureNarrow`
- Create: `KeycloakRenameTypeNarrowRippleTest.kt`, `KeycloakChangeSignatureNarrowRippleTest.kt`

**Interfaces:**
- Consumes: Task 1's SLOT 2 and SLOT 4 lines; Task 3's `RippleCase`, `RippleCases`, `RippleScenarioBaseTest`.
- Produces: `RippleCases.renameTypeNarrow`, `RippleCases.changeSignatureNarrow`.

- [ ] **Step 1: Write the failing pair test**

Append to `RippleCaseRegistryTest.kt`:

```kotlin
    @Test
    fun `each narrow case is the fan-out ablation of its wide twin, ambiguity held constant`() {
        val pairs = listOf(
            RippleCases.renameTypeWide to RippleCases.renameTypeNarrow,
            RippleCases.changeSignatureWide to RippleCases.changeSignatureNarrow,
        )
        pairs.forEach { (wide, narrow) ->
            assertTrue(wide.expectedGoldReferences >= 100) { wide.instanceId }
            assertTrue(narrow.expectedGoldReferences in 5..20) { narrow.instanceId }
            assertTrue(narrow.expectedGoldFiles <= 3) { narrow.instanceId }
            assertTrue(narrow.expectedDecoyDeclarations >= 3) {
                "${narrow.instanceId}: a narrow case without ambiguity varies two axes at once and " +
                    "cannot isolate fan-out"
            }
            assertEquals(wide.target.kindId, narrow.target.kindId)
        }
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*RippleCaseRegistryTest*'`
Expected: `Unresolved reference: renameTypeNarrow`.

- [ ] **Step 3: Add the two cases to the registry**

Transcribe SLOT 2 and SLOT 4 from Task 1's report into two `RippleCase` entries, reusing the `RenameType` and `ChangeSignature` variants. Instance ids `ripple__keycloak__rename-type-narrow` and `ripple__keycloak__change-signature-narrow`.

- [ ] **Step 4: Write the two hidden consumer patches**

Same additive shape as Tasks 2 and 3, targeting each case's own module. Run the same two by-hand checks per patch (JUnit 4 imports resolve; the path is free).

- [ ] **Step 5: Add the two run tests**

Two five-line subclasses of `RippleScenarioBaseTest`, each with its own class KDoc naming which axis it ablates and against which wide twin it is read.

- [ ] **Step 6: Run the suite**

Run: `./gradlew :test-experiments:test --tests '*Ripple*' --tests '*SemanticRipple*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A test-experiments/src/test
git commit -m "Add the narrow twins of the rename-type and change-signature cases

Each holds lexical ambiguity constant and drops fan-out to a handful of references
in at most three files, so a difference between a pair is attributable to fan-out
alone. Read as ablations of their wide twins, never on their own."
```

---

### Task 5: Cases 5 and 6 — move class, wide and narrow

**Files:**
- Modify: `RippleTarget.kt` — add the `MoveClass` variant
- Modify: `SemanticRippleOracle.kt` — add `parseFqnMovePredicate`
- Modify: `SemanticRippleOracleTest.kt` — its tests
- Modify: `RippleCases.kt` — add `moveClassWide`, `moveClassNarrow`
- Create: two patches under `arena-overlays/`, two run-test subclasses

**Interfaces:**
- Consumes: Task 1's SLOT 5 and SLOT 6 lines; Task 3's seam.
- Produces: `data class MoveClass(val oldFqn: String, val newPackage: String, val behaviourPreservationEvidence: String) : RippleTarget`; `fun parseFqnMovePredicate(output: String): Boolean`.

- [ ] **Step 1: Write the failing predicate tests**

Append to `SemanticRippleOracleTest.kt`:

```kotlin
    @Test
    fun `a move is complete only when the new FQN resolves and the old one does not`() {
        fun post(new: Boolean, old: Boolean) = """
            POST_NEWNAME_DECLARED $new
            POST_OLDNAME_ON_TARGET ${if (old) 1 else 0}
            POST_NEW_FQN org.keycloak.b.Moved
            POST_OLD_FQN org.keycloak.a.Moved
            POST_NEW_FQN_RESOLVES $new
            POST_OLD_FQN_RESOLVES $old
            POST_TOTAL_NEW_REFS 4
            POST_END
        """.trimIndent()

        assertTrue(parseFqnMovePredicate(post(new = true, old = false)))
        assertFalse(parseFqnMovePredicate(post(new = true, old = true))) {
            "A class left behind at the old FQN is a copy, not a move"
        }
        assertFalse(parseFqnMovePredicate(post(new = false, old = true)))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleOracleTest*'`
Expected: `Unresolved reference: parseFqnMovePredicate`.

- [ ] **Step 3: Implement the predicate**

```kotlin
/**
 * True when the class now resolves at its new fully-qualified name and no longer at the old one.
 *
 * Both halves are needed. A class copied to the new package while a forwarding shell stays behind
 * satisfies every reference-based predicate — the references moved, the counts conserved — and is not
 * a move at all.
 */
fun parseFqnMovePredicate(output: String): Boolean {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "POST_END" }) {
        "Post-condition output has no POST_END terminator — the script was truncated or failed:\n$output"
    }
    fun flag(prefix: String): Boolean =
        (lines.firstOrNull { it.startsWith(prefix) }
            ?: error("Post-condition output is missing the $prefix field:\n$output"))
            .removePrefix(prefix).trim().toBooleanStrict()
    return flag("POST_NEW_FQN_RESOLVES ") && !flag("POST_OLD_FQN_RESOLVES ")
}
```

- [ ] **Step 4: Add the `MoveClass` variant**

Its capture fragment finds the class by old FQN and emits the standard GOLD lines; its post-condition fragment emits `POST_NEW_FQN`, `POST_OLD_FQN`, `POST_NEW_FQN_RESOLVES`, `POST_OLD_FQN_RESOLVES` on top of the standard POST lines; `extraPredicates` returns `mapOf("P1_MOVED" to parseFqnMovePredicate(output))`. Its prompt task section states: move the type to the named package, keep its simple name, update every import, leave same-named types in other packages alone, and no forwarding shell at the old location.

- [ ] **Step 5: Add the two cases, patches and run tests**

Transcribe SLOT 5 and SLOT 6. The hidden consumer for a move asserts `Class.forName(newFqn)` succeeds and `Class.forName(oldFqn)` throws `ClassNotFoundException`. Note the difference from the rename-type consumer: it must live in a package that does **not** import the moved type, so it compiles before and after and only its assertions flip.

- [ ] **Step 6: Run the suite**

Run: `./gradlew :test-experiments:test --tests '*Ripple*' --tests '*SemanticRipple*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A test-experiments/src/test
git commit -m "Add the move-class cases and the predicate that rejects a copy

Reference conservation cannot tell a move from a copy with a forwarding shell left
behind: both move every reference and conserve every count. Resolving the old and
the new fully-qualified name separately is what distinguishes them."
```

---

### Task 6: Case 7 — pull up into a supertype

Skip this task and record it as `SLOT 7 — NONE QUALIFYING` if Task 1 found no target with hierarchy breadth ≥ 8. Do not substitute an easier kind.

**Files:**
- Modify: `RippleTarget.kt` — add the `PullUp` variant
- Modify: `SemanticRippleOracle.kt` + its test — add `parseSupertypeOwnershipPredicate`
- Modify: `RippleCases.kt` — add `pullUp`
- Create: one patch, one run-test subclass

**Interfaces:**
- Consumes: Task 1's SLOT 7 line; Task 3's seam.
- Produces: `data class PullUp(val subtypeFqn: String, val supertypeFqn: String, val methodName: String, val behaviourPreservationEvidence: String) : RippleTarget`; `fun parseSupertypeOwnershipPredicate(output: String): Boolean`.

- [ ] **Step 1: Write the failing predicate test**

```kotlin
    @Test
    fun `a pull-up requires the supertype to declare it and the subtypes not to`() {
        fun post(superDeclares: Boolean, subDeclarations: Int) = """
            POST_NEWNAME_DECLARED $superDeclares
            POST_OLDNAME_ON_TARGET 0
            POST_SUPER_DECLARES $superDeclares
            POST_SUB_DECLARES $subDeclarations
            POST_TOTAL_NEW_REFS 4
            POST_END
        """.trimIndent()

        assertTrue(parseSupertypeOwnershipPredicate(post(superDeclares = true, subDeclarations = 0)))
        assertFalse(parseSupertypeOwnershipPredicate(post(superDeclares = true, subDeclarations = 1))) {
            "A duplicate declaration left on a subtype means the method was copied up, not pulled up"
        }
        assertFalse(parseSupertypeOwnershipPredicate(post(superDeclares = false, subDeclarations = 0)))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*SemanticRippleOracleTest*'`
Expected: `Unresolved reference: parseSupertypeOwnershipPredicate`.

- [ ] **Step 3: Implement the predicate**

```kotlin
/**
 * True when the supertype declares the method and no subtype still declares its own copy.
 *
 * Overriding implementations are not what this counts — the capture fragment emits only declarations
 * whose signature is identical to the pulled-up one AND which carry no `@Override`, so a legitimate
 * specialisation does not read as a failed pull-up.
 */
fun parseSupertypeOwnershipPredicate(output: String): Boolean {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    check(lines.any { it == "POST_END" }) {
        "Post-condition output has no POST_END terminator — the script was truncated or failed:\n$output"
    }
    fun field(prefix: String): String =
        (lines.firstOrNull { it.startsWith(prefix) }
            ?: error("Post-condition output is missing the $prefix field:\n$output"))
            .removePrefix(prefix).trim()
    return field("POST_SUPER_DECLARES ").toBooleanStrict() && field("POST_SUB_DECLARES ").toInt() == 0
}
```

- [ ] **Step 4: Add the variant, the case, the patch and the run test**

The hidden consumer asserts by reflection that the supertype declares the method (`supertype.getDeclaredMethod(name, ...)` succeeds) and that the original subtype does not declare its own copy.

- [ ] **Step 5: Run the suite**

Run: `./gradlew :test-experiments:test --tests '*Ripple*' --tests '*SemanticRipple*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A test-experiments/src/test
git commit -m "Add the pull-up case and the predicate that rejects a copy-up

A method duplicated onto the supertype while every subtype keeps its own leaves
all references resolving and all counts conserved. Counting non-overriding subtype
declarations is what separates that from a real pull-up."
```

---

### Task 7: TeamCity configurations

**Files:**
- Modify: `~/Work/mcp-steroid-teamcity` — one build configuration per new case per agent

**Interfaces:**
- Consumes: the run-test class names from Tasks 2–6.
- Produces: TeamCity build configuration ids of the shape `mcp_steroid_IntegrationTests_Ripple<Case>_<Agent>`.

- [ ] **Step 1: Read that repository's own guide**

Read `~/Work/mcp-steroid-teamcity/CLAUDE.md` first — it owns the generate → edit → regenerate → commit workflow, and this plan does not restate it.

- [ ] **Step 2: Add the configurations**

One per (case, agent), filtering with `-PtestFilter=*<RunTestClass>.<agent>*`, modelled on the pilot's existing configuration. The build-level **Execution timeout must exceed 180 minutes** — the per-method JUnit timeout — or a run dies at the build level before the test can report, which is how `Microshop18` was lost for three rounds.

- [ ] **Step 3: Verify the generated DSL builds**

Follow that repository's verification command from its guide. Do not push it and do not trigger anything yet.

- [ ] **Step 4: Report the configuration ids**

List every new id in the task report; Task 8 triggers by exactly these strings.

---

### Task 8: Smoke round, then the full round

This task spends real money and real CI. Every step here is gated on the user's explicit go-ahead — ask before triggering, and report between the two rounds.

**Files:**
- Modify: `TEAMCITY-WHITEPAPER.md` — a new round section

**Interfaces:**
- Consumes: Task 7's configuration ids.
- Produces: a round section recording build ids and results in the shape §9c uses.

- [ ] **Step 1: Push the branch**

Push `worktree-semantic-ripple-pilot` to `jb` only, per `TEAMCITY-WHITEPAPER.md` §2 — never `main`, never a merge. **Ask the user before pushing.**

- [ ] **Step 2: Trigger the smoke round**

Claude only, 1 pass per case. Use a distinct `-S arena.pass.label=` per trigger or TeamCity merges equivalent queue entries and "7 runs" silently becomes fewer (§3). Verify what actually queued with the `buildQueue` API call from §3 — `jb tc builds list` does not show queued builds.

- [ ] **Step 3: Read the smoke results and drop what must be dropped**

For each case record: P1–P5, recall, precision, the compile gate, FAIL_TO_PASS, and the §8 discard count. Drop a case from the full round when both arms scored 0, both scored 100, or the pre-agent compile gate failed on the untouched tree. State which and why — a dropped case is a finding, not a gap.

- [ ] **Step 4: Trigger the full round**

Survivors × 2 agents × 3 passes, plus `Petclinic36` and `TrainTicket31` on the same revision as the parity anchor. **Ask the user before triggering.**

- [ ] **Step 5: Write the round up**

Add a section to `TEAMCITY-WHITEPAPER.md` in the shape of §9c: build-id table, per-case ΔRecall between arms, precision and over-reach, cost and shell-call ratios, the anchor's result, and the §8 discard count per case. State the pre-registered outcome that was actually hit — including a null result, which the spec pre-registered as publishable.

- [ ] **Step 6: Commit the write-up**

```bash
git add TEAMCITY-WHITEPAPER.md
git commit -m "Record the ripple family round: build ids, per-kind recall deltas, anchor"
```

---

## Notes for the implementer

- `TEAMCITY-WHITEPAPER.md` is local-only and excluded via `.git/info/exclude`; committing it here means committing to the local branch only. Never paste a token value into it.
- The pilot's four measured incidents are recorded as comments in `KeycloakRenameRippleTest` and `SemanticRippleSpec` — the `-pl` selector syntax, the shared `~/.m2` contaminating an arm, the formatter's rewrite reading as tampering, and the OOM-killed IDE losing a measurement. Every one of them applies to all eight cases. When moving that code, move the comments with it.
- If a Docker run has printed nothing within ~60 s of `> Task :test-experiments:test`, collect a screenshot and an in-container thread dump before killing it (`test-integration/AGENTS.md` → "Debugging a stuck/hung Docker test").
