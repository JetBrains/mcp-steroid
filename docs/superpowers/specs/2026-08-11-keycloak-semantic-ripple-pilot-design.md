# Keycloak semantic-ripple pilot — design

Date: 2026-08-11. Status: approved design, not yet implemented.
Not committed to git, per the repo rule against committing process artifacts.

## Why

The DPAIA arena does not separate the `mcp` arm from the `none` arm, and measurement now
explains why rather than guessing. Three task sources were measured against the four gates
from the semantic-pressure literature review:

| axis | DPAIA, best of 154 | SWE-Refactor, best of 1099 | Keycloak | gate |
|---|---|---|---|---|
| resolved refs on target symbol | 20 | n/a — one method, one class | 6786 | ≥15–25 |
| lexical ambiguity (text ÷ resolved) | 1.15 | n/a | 3.36 | ≥3 |
| hierarchy breadth | 3 | n/a | 952 | ≥8 |
| modules spanned | 6 | 2 | 62 | ≥3–5 |
| production files per task | 16 | 3 | n/a | ≥5 |

DPAIA's widest case and its only A/B winner have the same fan-out (78 vs 81 resolved refs), so
semantic pressure is a constant across that dataset, not a variable — there is no dose–response
to measure inside it, and no subset selection can create one. SWE-Refactor is narrower still:
six method-level refactoring kinds with no rename, no change-signature and no hierarchy work,
zero instances touching three files, and an unusable oracle for two thirds of its instances.

Both rejected sources were mined from commit history. That is the common cause: a developer
performing a wide symbol ripple does it with one IDE refactoring, so the commit records the
smallest describable action, and a ripple spread over several commits is discarded by purity
filters. Mining history systematically removes the phenomenon. The track therefore has to be
built from **controlled transformations over a large real repository**.

This document specifies the first such task, end to end, as a pilot.

## Scope

One transformation instance on Keycloak, wired through the existing arena A/B harness, with a
PSI post-condition oracle beside the existing FAIL_TO_PASS oracle. Deliberately **out** of scope:
the graded dose–response set, the MCP-readonly third arm, Success@B budget instrumentation, and
any second repository. Each is a separate change once the pilot shows the harness can separate
the arms at all.

## The task

**Pinned base commit: `60c4d5e9321ff5462a772ceb896f8cb2e639e04b`** (`main` tip, 2026-08-11
03:47 -0300, "Enforce membership permission when creating users"). Every number in this document is
measured at that commit and is meaningless at any other.

**Target.** `org.keycloak.admin.client.resource.RealmResource.roles()` returning `RolesResource`,
declared in the interface at `integration/admin-client-core/src/main/java/org/keycloak/admin/client/resource/RealmResource.java`,
module `keycloak-admin-client-core`. Measured at the pinned base commit: **445 resolved
references across 79 files and 6 modules, 0 overrides**.

The reference modules are `integration-arquillian-tests-base`, `keycloak-admin-v2-tests`,
`keycloak-authzen-tests-base`, `keycloak-test-framework-tests`, `keycloak-tests-base`,
`keycloak-tests-utils`. With the declaring module `keycloak-admin-client-core` that is the set of
**7** modules the compile gate covers.

The measurements were taken on a `--depth 1` clone. A full clone indexes the same working tree, so
the numbers should carry over unchanged — but step 1 re-captures them against the cache-cloned tree,
and the tripwires below turn any discrepancy into a hard failure rather than a silent shift.

**New name: `realmLevelRoles`.** Verified free — zero method and zero class declarations in the
project. The obvious first choice `realmRoles` is **already declared 5 times** and must not be
used; this is why the precondition check below is mandatory rather than advisory.

**Behaviour preservation is structural, not asserted.** The method carries `@Path("roles")`, so
the HTTP contract is defined by the annotation and not by the Java method name. Renaming the
method cannot change external behaviour, which removes the usual refactoring-oracle worry that
tests might be passing for the wrong reason.

**Decoy structure.** Sixteen other declarations share the simple name `roles`, accounting for
1048 further references (1493 same-name references in total, giving lexical ambiguity 3.36):

| declaration | returns | refs | files |
|---|---|---|---|
| `RealmResource` — target | `RolesResource` | 445 | 79 |
| `UserResource` | `RoleMappingResource` | 401 | 75 |
| `ClientResource` | `RolesResource` | 343 | 78 |
| `KeycloakSession` | `RoleProvider` | 144 | 54 |
| 13 others | various | 160 | — |

`ClientResource.roles()` returns the **same type** as the target and also carries a `@Path`
annotation, and the two appear in overlapping file sets (79 and 78 files). The target is
therefore not separable by name, by return type, or by annotation — only by resolving the
receiver's type. That is the construct the whole hypothesis is about.

All 445 references live in test modules. Editing test files is the task here, not tampering;
see "Interaction with tamper detection" below.

## Oracle

Success is the conjunction of four independent layers, none duplicating another:

```
Success = ScopedTestCompile(7 modules)
        ∧ HiddenConsumerTest
        ∧ P1 ∧ P2 ∧ P3 ∧ P4
```

- `ScopedTestCompile` covers the 445 call sites: for a rename, a missed site *is* a compile
  error, so compilation is a complete invariant over the ripple, not an approximation.
- `HiddenConsumerTest` covers the declaration.
- The PSI post-conditions cover what neither can see: a compatibility alias, and over-reach.

### PSI post-conditions

| id | assertion | failure it catches |
|---|---|---|
| P1 | `RealmResource` declares `realmLevelRoles()` returning `RolesResource`, and declares no method named `roles` | compatibility alias — old name kept as a forwarder, tests green, rename not performed |
| P2 | for every gold site, the number of resolved references to the new method at that site is at least the number the target had there before | recall — missed sites, including partially-converted ones |
| P3 | resolved-reference counts of all 16 decoy declarations are unchanged from the pre-agent capture | over-reach — `ClientResource.roles()` renamed along with the target |
| P4 | total resolved references to the new method equal the pre-agent gold count | conservation — neither loss nor invention |

P2 is **count-aware per site**, not presence-based: a site whose enclosing declaration held three
references to the target and now holds one converted reference plus two stale ones is a partial
failure, and a presence check would score it as success.

P3 is the assertion no existing benchmark makes: it penalises exactly the mistake a text search
produces. P1 is the assertion tests alone cannot make.

Metrics, defined over resolved references rather than over files:

```
recall    = converted references at gold sites / total gold references            (445)
precision = references to the new method that sit at gold sites
            / all references to the new method
f1        = harmonic mean of the two
```

Precision is deliberately not defined over "sites the agent edited" — the agent's edit set is not
observable to the oracle, whereas both reference sets are. Also reported as lists:
`missedSites` and `overReachedDecoys`.

The publishable comparison is ΔRecall between arms, not the binary pass/fail — that binary is what
hid the difference in DPAIA.

### Gold provenance

Gold is captured by a PSI query **inside the container, before the agent starts**, not from a
reference diff and not from a checked-in fixture:

- The harness holds `session.mcpSteroid` in both arms. IntelliJ runs in the `none` arm too —
  `mcpConnectionMode = None` only withholds MCP registration from the agent, it does not disable
  the IDE — so the harness can measure PSI in both arms without granting the shell arm any access.
- Gold from the PSI symbol graph, rather than from a gold patch, is what allows alternative correct
  implementations to pass. An exact-diff oracle would punish them.

**Gold-site key: `(file, enclosing declaration)`** — not line or offset, which shift as the agent
edits.

### Preconditions and tripwires

Checked before the agent runs; any failure aborts the run rather than producing a silent zero.
Without them an index failure yields an empty gold set and therefore 100% recall on nothing.

1. Gold site count equals the committed expected value **445**.
2. Decoy declaration count equals the committed expected value **16**.
3. The new name `realmLevelRoles` has zero declarations in the project.
4. The target declaration resolves, is an interface method, and carries `@Path("roles")`.

The expected counts are committed constants specific to the pinned base commit. Changing the
commit requires re-measuring them; a mismatch is a hard failure, which is the intended behaviour.

### Index freshness

The post-condition query runs after the agent has edited 79 files. The oracle script must refresh
the VFS and wait for smart mode before counting; otherwise it reports stale pre-edit numbers. This
is an explicit step in the script, not an assumption about IDE behaviour.

## Components

### `SemanticRippleCases.kt`

Builds the pilot `DpaiaTestCase` in code: `keycloak/keycloak`, pinned SHA, `testPatch` read from a
resource, `failToPass` naming the hidden consumer class, `problemStatement` from the deterministic
statement builder, `buildSystem = "maven"`, `isMaven = true`.

`DpaiaTestCase` is reused as the carrier even though this task is not from the DPAIA dataset. The
name is inaccurate here, and renaming the type to something neutral is **not** part of this change:
fifteen scenario tests and the prompt-contract tests depend on it, so that rename is a separate
change with its own risk. The inaccuracy is confined to this one file and explained in its KDoc.
Revisit only if the track survives the pilot.

Case config: `projectJdkVersion = "21"`, `agentTimeoutSeconds = 5400`, `projectReadyTimeoutMs =
3_600_000`. The prewarm timeout is a **guess to be calibrated by step 0**, not a measured value,
and is labelled as such in the code.

### `SemanticRippleOracle.kt`

Follows the split already used in this package (`ArenaOutputParsing.kt`, `parseSurefireXml`): the
Kotlin script executed in the IDE prints a stable text format, and pure functions parse it. This
keeps every predicate and metric unit-testable without Docker and without an IDE.

```
data class GoldSite(file: String, enclosingDeclaration: String)
data class SemanticGold(targetFqn, oldName, newName, sites: Set<GoldSite>, decoyCounts: Map<String, Int>)
data class SemanticPostconditionResult(
    p1NoAliasAndNewNameDeclared: Boolean,
    p2Recall: Double, p3DecoysUnchanged: Boolean, p4Conserved: Boolean,
    precision: Double, f1: Double,
    missedSites: List<GoldSite>, overReachedDecoys: List<String>,
)

captureGold(mcp, spec): SemanticGold
checkPostcondition(mcp, gold): SemanticPostconditionResult
parseGold(text): SemanticGold                       // pure
parsePostcondition(text, gold): SemanticPostconditionResult   // pure
```

### `SemanticRipplePrompt.kt`

A separate builder from the DPAIA one, with its own contract test.

Must contain: the target FQN and signature; the new name; the requirement to preserve behaviour and
the public HTTP contract; an explicit prohibition on renaming same-named methods of other types;
the `ARENA_FIX_APPLIED: yes` marker so the existing evaluation parser works unchanged; and the
environment paragraphs carried over from the DPAIA prompt (`JAVA_HOME`, wrapper-only, the `-am` ban)
— those are environment facts, not hints.

Must not contain: `steroid_`, `mcp-steroid://`, or the words find-usages, PSI, IDE, or refactoring —
the agent discovers the mechanism, mirroring the purity contract of
`DevrigRemoteDevelopmentKeycloakTypeHierarchyTest`. Must not contain a list of the 79 affected files
or the 16 decoy declarations: that would hand over the answer and reduce the measurement to
execution rather than navigation.

The declaration itself **is** named exactly. The benchmark does not test guessing the starting
point; it tests finding the whole ripple.

### `SemanticRippleKeycloakRolesTest`

A sibling of `DpaiaScenarioBaseTest`, not a subclass: the base class hardcodes dataset loading and a
whole-suite baseline, and neither applies here.

The measurement-and-reporting tail — token/test/decoded-log metric extraction, `RunRecord`,
`writeRunSummary`, the comparison CSV — is **extracted into a reusable function** shared by both
tests. That part must stay byte-identical across the two tracks or the pilot's numbers silently
stop being comparable with DPAIA's, which is the whole point of running it in this harness. Setup
and verification bodies stay owned by each test.

A full template-method refactor of `DpaiaScenarioBaseTest` with extension points for case source,
baseline, verification and prompt is **deferred** until a second ripple task exists and the real
shape of the seams is visible.

### Regression evidence

`baselineSnapshotAtBaseCommit` — a whole-suite run before and after the agent — is not viable on
Keycloak and is not used. Instead:

- Primary gate: `test-compile` over the 7 modules (6 containing references plus the declaring
  module), which is complete with respect to the ripple because no reference exists outside them.
- `-pl` without `-am` works because the harness prewarm already performs a full build
  (`compileProject = true`) outside the agent timer, leaving sibling artifacts in `~/.m2`. The
  `-am` ban from the DPAIA prompt is respected.

The base commit does not compile once the test patch is applied — the hidden consumer references a
method that does not exist yet — which is the same contract DPAIA already relies on
(`requireCleanCompile = false`). Compilation going from red to green is therefore itself the
FAIL→PASS signal.

### Hidden consumer

A plain JUnit test that asserts by reflection that `RealmResource` declares `realmLevelRoles` and
that `getMethod("roles")` throws `NoSuchMethodException`. No server, no Docker, milliseconds. It
encodes the no-alias policy as an executable test rather than only as a PSI check.

Placement constraints, resolved when the patch is authored rather than named here:

- a new file, so it cannot collide with any of the 79 the agent must edit;
- in a module that depends on `keycloak-admin-client-core` and is one of the 7 modules the compile
  gate covers, so a scoped `test-compile` actually builds it;
- reachable by the surefire selector the harness derives from `failToPass`.

`SemanticRippleCasesTest` asserts the first constraint mechanically and without needing gold: the
test patch must be **purely additive** — every file section creates a new file (`--- /dev/null`) and
none modifies an existing one. A purely additive patch cannot collide with any of the 79 files by
construction, which is a stronger check than intersecting against a gold set that only exists at
run time.

## Interaction with tamper detection

The harness distinguishes `failToPassTampered` (a file defining a FAIL_TO_PASS class changed — the
run is void) from `collateralTestFilesEdited` (informational). Here the 79 edited test files are
collateral by design and the hidden consumer is a new file, so the existing distinction holds
without modification. This must be asserted in the wiring test, not assumed.

## Testing

Non-Docker unit tests:

| test | covers |
|---|---|
| `SemanticRippleOracleTest` | parsing of recorded outputs; P1–P4 across four scenarios — alias left in place, site missed, decoy renamed too, gold empty (tripwire) |
| `SemanticRipplePromptContractTest` | required tokens present; forbidden tokens absent; no file or decoy enumeration; success marker present |
| `SemanticRippleCasesTest` | pinned SHA is 40 hex characters; test patch non-empty; `failToPass` non-empty; tripwire constants 445 and 16 present |
| existing `RunSummaryJsonTest` | guard on the reporting-tail extraction — must keep passing unchanged |

The Docker run is the integration test, one method at a time, never alongside another Docker test.

## Risks

1. **Prewarm cost is unknown.** A full Keycloak `install -DskipTests` in the container may exceed
   any timeout or exhaust memory. This is the only risk that can invalidate all the work, so it is
   resolved first, before any oracle code exists.
2. **`/repo-cache` has no Keycloak bare repo today** — it currently holds only `dpaia`. Warming is
   automatic (`intelliJ-factory` calls `IntelliJProject.warmRepoCache` → `BareRepoCache.ensureRepo`,
   which refreshes daily via `git remote update --prune`), but its default timeout is **300 s** and a
   first full bare clone of Keycloak will very likely exceed it. The arena's own fallback is a remote
   clone with a **120 s** timeout, which Keycloak certainly will not meet. Step 0 therefore pre-warms
   the bare repo once on the host; if the automatic path still times out on a cold agent, raising
   that timeout for large repositories is a small separate infra change, not part of this design.
   The pinned commit is the tip of `main`, so it is reachable in the bare repo after any warm.
3. **Floor for both arms.** If even the IDE arm cannot finish, nothing is learned about separation.
   Mitigated by the IDE arm's edit being O(1) — one `RefactoringFactory.createRename` call, per the
   documented recipe in `mcp-steroid://skill/coding-with-intellij-refactoring` — so the risk is the
   build, not the edit.
4. **Index staleness** after 79 edits; addressed by the explicit refresh step.
5. **The advantage may be safe-edit rather than navigation.** Accepted: results are worded as
   "benefit of the IDE bundle". The MCP-readonly arm separates the two later.
6. **No external validity.** One repository, one task. The pilot answers only whether the harness
   can separate the arms at all.

## Pre-registered outcomes

Fixed before the first run so the interpretation cannot be fitted afterwards.

- **Design failure:** both arms at 0, or both at 100 — the task is uncalibrated and carries no
  information.
- **Infrastructure failure:** prewarm, clone or compile does not fit — blocked, and says nothing
  about the hypothesis.
- **Informative success:** the IDE arm passes at least 1 of 3 runs *and* the shell arm is strictly
  lower on P2 recall, with the gap visible in semantic recall rather than only in the binary.
- **Headline numbers:** ΔRecall (P2) and precision (P3) per run.

Run volume: smoke first — Claude, both arms, one pass. Only if the smoke is clean, 3 passes × 2 arms
× 2 agents = 12 arm-runs.

## Order of work

Risk first, so nothing expensive is written against unverified infrastructure.

```
0  warm the Keycloak bare repo into /repo-cache; measure prewarm
   (import + full install) with NO agent and NO oracle             → go / no-go
1  captureGold script + pure parser + unit tests; re-verify 445/16
   against the cache-cloned full tree
2  hidden consumer patch, case factory, prompt, contract tests
3  post-conditions P1–P4 + unit tests
4  extract the reporting tail, wire the test, smoke one arm
5  paired smoke, then 3 passes
```

Step 0 produces no code that would be thrown away and clears risks 1 and 2 together.
