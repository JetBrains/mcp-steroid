# Keycloak ripple case family — design

Date: 2026-08-13. Status: approved design, not yet implemented.
Not committed to git, per the repo rule against committing process artifacts.

Predecessor: `2026-08-11-keycloak-semantic-ripple-pilot-design.md` (the pilot) and its plan. Read the
pilot first — this document only states what changes, and inherits everything it does not restate.

## Why

The pilot ran and produced a usable measurement on one transformation: a cross-module method rename
on Keycloak, graded by a PSI post-condition oracle beside a scoped compile gate and a hidden
reflection consumer. One task on one repository answers only whether the harness can separate the
arms at all. It cannot say **what kind of semantic pressure** the separation comes from, and it
cannot rule out that the separation is an artefact of that single task's shape.

This document specifies seven further cases that turn the pilot into a family.

Two things it deliberately does not do. It does not add a second repository: every infrastructural
cost on Keycloak — bare-repo cache, reactor install, JDK, compile gate, prewarm timing — is already
paid and debugged, and holding the repository fixed means a difference between cases cannot be
blamed on a different project. And it does not construct negative controls on Keycloak: the parity
anchor comes from measured DPAIA cases instead (below).

## Scope

**In:** seven new cases on Keycloak at the pilot's pinned commit, spanning four transformation kinds;
the generalisation of the pilot's single-target spec and oracle into a case family; a target-survey
tool that selects the targets by measurement; per-case TeamCity configurations.

**Out:** a second repository; the MCP-readonly third arm; Success@B budget instrumentation; changing
the pilot's prompt, agent set, or models.

## The seven cases

All on `keycloak/keycloak` at the pilot's pinned base commit. Seven slots over four kinds:

| # | kind | fan-out | oracle |
|---|---|---|---|
| 1 | rename type (interface/class) | wide | P1–P4, symbol = class |
| 2 | rename type | narrow | same |
| 3 | change signature (add parameter) | wide | P1–P4 + P5 arity |
| 4 | change signature | narrow | same |
| 5 | move class to another package | wide | P1' (new FQN resolves, old does not) + P2–P4 |
| 6 | move class | narrow | same |
| 7 | pull up method into a supertype | hierarchy depth | P1'' (declared on the supertype, absent from subtypes) + P2–P4 |

The wide/narrow pair inside each kind is a dose–response on fan-out at no extra slot cost: if the
arms separate on the wide member and not on the narrow one, fan-out is the operative variable; if
they separate on both, it is not.

**Wide** means at least 100 resolved references over at least 20 files and at least 3 modules — the
pilot measured 445 / 79 / 6, so wide members are the same order of magnitude as it. **Narrow** means
5 to 20 resolved references over at most 3 files. Both members of a pair must carry lexical
ambiguity — at least 3 other declarations sharing the simple name — so that the pair varies fan-out
and nothing else. Slot 7's hierarchy requirement is at least 8 implementing or extending types below
the supertype the method is pulled up to; a target with fewer does not exercise the axis rename
cannot reach. These thresholds are selection criteria for the survey, not post-hoc filters: a
candidate that misses them is not chosen, rather than being chosen and later excused.

### Parity anchor — no new code

Negative controls are not constructed. Two DPAIA cases already measured at parity serve as the
anchor, run on the same revision and in the same round as the ripple cases:

| case | control-arm cost | mcp/none | note |
|---|---|---|---|
| `Petclinic36` | $1.36 | 0.92× | `McpBenefit.LOW` a priori, at parity in fact |
| `TrainTicket31` | $1.11 | 1.12× | same |

(Round 14/14b, Claude, 36 pairs — `TEAMCITY-WHITEPAPER.md` §9c.) Their test classes and TeamCity
configurations exist. The caveat to state when publishing: they are graded by FAIL_TO_PASS, not by
P1–P4, so they anchor the *harness*, not the oracle.

### Target selection is measured, never guessed

The concrete targets are not named in this document because they are an output of measurement. The
pilot established the cost of guessing: the obvious new name `realmRoles` turned out to be declared
five times, which only a PSI query revealed.

A target qualifies when all four hold:

1. The destination name (or package, for a move) is free — zero declarations project-wide.
2. Every module holding a reference can be built by the scoped compile gate without `-am`.
3. Behaviour preservation is structural, not asserted: no string references to the symbol's name
   from configuration, `Class.forName`, or `META-INF` service files. For the pilot this was the
   `@Path("roles")` annotation carrying the HTTP contract; each kind states its own equivalent.
4. A module exists that can host the hidden consumer — it depends on the declaring module and is
   inside the compile gate.

`KeycloakRippleTargetSurveyTest` performs this selection: one Docker run, no agent, opening Keycloak
once and printing ranked candidates per kind with their measured numbers (resolved references,
files, modules, same-simple-name declarations, hierarchy depth) plus the freeness check for each
candidate destination name. Its output becomes the registry's pinned constants.

It is also the go/no-go per slot. If no pull-up target on Keycloak has useful hierarchy depth, slot 7
is reported empty rather than substituted with an easier kind.

## Architecture

### Case model

`SemanticRippleSpec` — today an `object` of `const val` for one target — becomes a `RippleCase` data
class plus a registry. The transformation kind is a sealed `RippleTarget` hierarchy:
`RenameMethod`, `RenameType`, `ChangeSignature`, `MoveClass`, `PullUp`. Each variant owns exactly
three things:

- its fragment of the gold-capture PSI script,
- its fragment of the post-condition PSI script,
- its kind-specific predicates and its half of the prompt's task section.

Everything else — reference sites, decoys, conservation, the compile gate, the reactor install, the
prompt's environment facts and success markers — is shared and lives once.

**The seam is extracted on the third case, not the first.** The pilot's design deferred a
template-method refactor "until a second ripple task exists and the real shape of the seams is
visible". Case 1 (rename type) is therefore built by duplicating the pilot's code; the seam is cut
when case 3 (change signature) lands and the differences between three kinds are visible rather than
inferred from one. The pilot and case 1 are then moved onto the seam, and their existing unit tests
must pass unchanged — that is the check that the abstraction did not change behaviour.

Rejected alternatives: per-case standalone scripts (the capture preamble — VFS refresh, `siteKey`,
site grouping — would be copied eight times, and drift between copies would be invisible); and a
single kind-agnostic oracle (change-signature and pull-up post-conditions cannot be expressed as
"the name was X and is now Y").

### Oracle format

The line-oriented `GOLD_*` / `POST_*` format and the pure parsers stay: they are covered by unit
tests that need neither Docker nor an IDE, and that property is worth preserving.

Kinds **add** lines rather than changing existing ones:

| kind | added lines |
|---|---|
| change signature | `POST_ARITY <n>` per site |
| move class | `POST_NEW_FQN <fqn>`, `POST_OLD_FQN <fqn>` |
| pull up | `POST_SUPER_DECLARES <bool>`, `POST_SUB_DECLARES <n>` |

P2 (per-site count-aware recall), P3 (decoy reference counts unchanged) and P4 (reference
conservation) apply to all four kinds unchanged. Only P1 and the optional P5 are kind-specific. The
gold tripwires keep the pilot's contract: a capture that does not match the pinned counts aborts the
run before the agent starts, because an index failure otherwise yields an empty gold set and scores
as perfect recall over nothing.

### Test shape

`KeycloakRenameRippleTest` currently holds the whole run in one class. TeamCity filters by
`-PtestFilter=*Class.<method>*`, so a case needs its own class to get its own configuration — the
shape DPAIA already uses (`DpaiaPetclinic36Test` and siblings).

The run logic moves into `RippleScenarioBaseTest`; each case is a thin subclass naming its
`RippleCase`. Eight classes, eight TeamCity configurations. The DSL lives in the separate
`~/Work/mcp-steroid-teamcity` repository and its build-configuration timeout must stay above the
180-minute per-method timeout the pilot established.

### Hidden consumers

Each case gets its own purely additive patch adding a reflection test that compiles only after the
transformation. Two kinds are harder than rename:

- **move class** — the consumer must import the *new* package, so it fails to resolve until the class
  moves; the assertion is that `Class.forName(oldFqn)` throws and the new FQN loads.
- **change signature** — the consumer calls the method with the new arity and asserts by reflection
  that no overload with the old parameter list survives, so an agent that adds an overload instead of
  changing the signature fails it.

The pilot's mechanical guard applies to every patch: purely additive (every file section is
`--- /dev/null`), so it cannot collide with a file the agent must edit.

## Testing

Non-Docker unit tests, all required green before any container starts:

| test | covers |
|---|---|
| oracle parser tests, per kind | parsing recorded outputs; P1–P5 across alias-left-behind, missed site, decoy over-reach, empty gold |
| prompt contract test, per case | required tokens present; mechanism and answer absent (no decoy names, no counts, no file lists) |
| case registry test | patches purely additive; hidden consumer inside the compile gate; pinned SHA; destination names free per the survey |
| survey-agreement test | registry constants equal the numbers the survey printed, so a drifted commit fails loudly |

The Docker runs are the integration tests, one method at a time, never alongside another Docker test.

## Run plan

```
0  survey run: candidates per kind, free destination names, gate modules   → go/no-go per slot
1  case 1 (rename type, wide), duplicating pilot code; smoke one pass
2  case 3 (change signature, wide); extract RippleCase / RippleTarget here
   and move the pilot and case 1 onto it — their unit tests unchanged
3  remaining five cases, one at a time, each with its hidden consumer
4  smoke: Claude x 2 arms x 1 pass per surviving case (7 builds)
5  full round: survivors x 2 agents x 3 passes (up to 42 builds), plus the
   Petclinic36 / TrainTicket31 anchor on the same revision
```

A case is dropped at step 4, before the full round, when: both arms score zero (uncalibrated), both
score 100 (no information), or the compile gate fails on the untouched tree (an environment failure,
which says nothing about any agent).

## Pre-registered outcomes

Fixed before the first run so the reading cannot be fitted afterwards.

- **Headline:** ΔRecall (P2) between arms, per transformation kind — not binary pass/fail, which is
  what hid the difference in DPAIA.
- **Alongside:** precision and P3 over-reach; cost and shell-call counts, so the numbers join up with
  Round 14's defensible claim (half the shell calls at equal correctness).
- **Informative success:** the IDE arm is strictly higher on P2 recall than the shell arm on the wide
  members of at least two different kinds, while the DPAIA parity anchor stays at parity in the same
  round.
- **Null result worth publishing:** the arms separate on the wide members and equally on the narrow
  ones — that would say the advantage is safe mass editing rather than navigation, which is a real
  finding about the tool and not a failed experiment.
- **Design failure:** every case lands at 0/0 or 100/100.

## Disclosure required when publishing

`ArenaTestRunner` carries three `withMcp`-gated guards that throw — removing the run from the sample
— when an MCP arm never exercised MCP, with no counterpart for the control arm
(`TEAMCITY-WHITEPAPER.md` §8). The surviving `mcp` population is therefore conditioned on "the agent
chose the IDE and it worked" while the `none` population is unconditioned.

This bias is *stronger* here than in DPAIA: a wide symbol ripple is exactly the task where declining
to use the IDE correlates with a poor outcome. Every per-case report must state how many runs each
guard discarded.

## Risks

1. **No viable pull-up target.** Slot 7 stays empty and is reported as such. Resolved by the survey
   before any oracle code exists for that kind.
2. **Hidden consumers for move and change-signature are harder to author** than the pilot's rename
   consumer, and a consumer that compiles *before* the transformation silently destroys the FAIL→PASS
   signal. Each one is verified by compiling the patched, untouched tree and asserting it fails.
3. **CI cost.** 42 builds of up to 180 minutes, comparable to Round 14's 48, but each ripple build is
   dearer than a DPAIA build because of Keycloak's reactor install. The smoke stage exists to keep a
   broken case from spending six builds.
4. **Premature abstraction.** Mitigated by cutting the seam on the third case, with the pilot's unit
   tests as the regression check.
5. **External validity is unchanged.** One repository, four kinds. The family answers what kind of
   pressure separates the arms, not whether it generalises across projects.
