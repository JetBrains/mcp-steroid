# Generalization round: pre-registration

Written before any trajectory of this round exists. Nothing below may be edited after the first cell is
queued except by an **amendment** appended at the bottom, dated, and stating what it was decided from —
the same rule the downstream rounds followed (`DESIGN-DOWNSTREAM-2.md`).

## What is being generalized, and what is not

The result to generalize is the one `RESULTS.md` publishes:

> the semantic arm reaches a given level of a pre-registered architecture checklist in **fewer
> environment interactions** than the shell arm, and **not** in fewer model output tokens.

It rests on one case (`acquisition__keycloak__cc-refresh-token`), one repository, one shape of discovery
chain (an SPI implementation plus a shipped JSON profile), and four trajectories per arm. Three threats
follow, and this round addresses the first and the third:

| threat | addressed here | how |
|---|---|---|
| the effect is a property of that one case | yes | two NEW architecture cases in different Keycloak subsystems |
| the effect is a property of Keycloak | **no** | a second repository was scoped and deferred; see "Deferred" |
| the effect is only about wiring/registration facts, not architecture | yes | the per-category reading, plus two control cases that bound the scale |

Not under test in this round: downstream implementation success. No solving cells are bought. The
research endpoint is the only one purchased, which is what makes four cases affordable at all — a case
needs a statement and a checklist to produce `U(B)`; a hidden oracle is needed only to grade a solver.
`UnderstandingCase` therefore admits research-only cases, and the solving cell refuses them by
construction rather than reporting "0 of 0".

## Cases

Four cases, deliberately of three different shapes. The two controls exist because an effect has no
size until the ends of the scale are anchored.

| id | shape | endpoint | oracle |
|---|---|---|---|
| `acquisition__keycloak__rename-method-wide` | navigational **positive control** | `U(B)` | none (research-only) |
| `acquisition__keycloak__client-auth-method` | architecture, NEW | `U(B)` | none (research-only) |
| `acquisition__keycloak__oauth-grant-type` | architecture, NEW | `U(B)` | none (research-only) |
| `acquisition__keycloak__email-domain-mapper` | shallow "copy the neighbour" control | `U(B)` | inherited, unused this round |

### The controls, and why they are not filler

- **`rename-method-wide`** is the ripple family's wide method rename (`KeycloakContext#setRealm` →
  `bindRealm`), scored by an eleven-fact checklist of reference facts: the override family (three
  classes, one of them outside the `services` module), the fan-out (496 references, 109 files, 14
  reactor modules), the 37 same-name declarations and the 151 foreign call sites a textual rewrite would
  damage, the admin-console TypeScript setters of the same name, and the module that is only in the
  reactor under a non-default profile. Its statement NAMES the symbol — the only case in the family
  allowed to, because a rename brief without its symbol is a guessing game. This is what semantic
  navigation is for, so it should show the round's **largest** arm difference.
- **`email-domain-mapper`** is the previous round's case, whose chain is three hops and whose twenty-two
  sibling mappers sit in one directory. `FINDINGS.md` records its downstream ceiling reached at 2 000-
  character notes with no arm difference at any clean length. It should show the round's **smallest**
  arm difference.

### The two new cases

Both are small feature additions in Keycloak subsystems untouched by the first round, each requiring an
implementation plus **two** registration/integration mechanisms in different directories, and each
carrying an invariant a copy of the nearest neighbour breaks. Their evidence files —
`CASE-EVIDENCE.md` beside each `gold.patch` under
`test-experiments/src/test/resources/acquisition-cases/<id>/` — carry the discovery chain, the leakage
audit and the shell audit measured on the pinned checkout.

**Admission gates. A new case may not be queued until all four hold, and the numbers are recorded in its
evidence file:**

1. **No leakage.** No phrase of the statement, and no two-phrase conjunction a reader can form from it,
   returns any of the gold files. (The earlier rounds used a proxy — "every phrase must match at least
   twenty files, or it is a pointer" — and it is the wrong test: what makes a statement safe is not that
   its words are common but that their hit sets do not touch the gold. `client-auth-method` has phrases
   matching 1, 4, 7 and 8 files and would have been rejected by the proxy, while its narrowest
   conjunctions return ZERO files and no phrase reaches a gold file. Written down BEFORE the round is
   queued; the per-phrase counts are in each case's evidence file.)
2. **Research depth.** Three obvious `find`/`grep` commands issued from the statement reveal at most half
   the gold file set, and the entry points and runtime-flow files are not among what they reveal.
3. **Semantic non-triviality.** At least four checklist facts are of the categories no reference query
   answers (`FLOW`, `SECONDARY_INTEGRATION`, `INVARIANT`, `ENTRY_POINT`), enforced in code by
   `AcquisitionChecklist`.
4. **A real gold.** The reference implementation compiles at the pinned commit, and the patch applies to
   a pristine checkout.

**Measured on the recorded cheating trajectories** — ten shell commands per case, written by someone who
already knew the answer, committed under each case's `calibration/shell-simulation/` and recomputed
offline by `AcquisitionCalibrationTest`:

| case | `U_obs` after the 3 obvious commands | after all 10 targeted ones | facts still missing at 10 |
|---|---|---|---|
| `cc-refresh-token` (round 2) | 0.07 | 0.80 | A2, B1, B2 |
| `client-auth-method` | **0.00** | 0.80 | B2, E2, I1 |
| `oauth-grant-type` | **0.00** | 0.73 | A3, D1, E2, I1 |

Both new cases therefore leave a region for a curve to have a shape in, and neither sets a ceiling the
control arm cannot reach.

A case that fails a gate is dropped from the round, not weakened. `C4` (a new Authorization-Services
policy type) is the pre-declared replacement, and its known weakness — a broad `find` exposes 6 of 8
gold files — is why it is the reserve rather than a starter.

## Arms, budget and the unit of replication

Unchanged from the first round, deliberately:

- **arms**: `mcp` (semantic IDE access plus shell) and `none`/`shell` (shell only), same model, same
  statement, same pristine tree, research only — the agent may not edit the repository.
- **budget**: one trajectory of 40 environment interactions, sliced offline at B = 5, 10, 20, 40.
  Connecting to the IDE (`list_projects`, `fetch_resource`) does not consume the budget; it carries no
  checklist fact, whereas the shell arm's first command is already research.
- **degeneracy guard**: a semantic-arm cell that made no semantic call is rejected, not published. This
  is the defect that invalidated round 1 (the CLI hid MCP tools behind a tool-search call), and the
  guard is what keeps a regression of it from being averaged in.
- **unit of replication**: the trajectory. Three per arm per case in this round.

## Matrix

4 cases × 2 arms × 3 replicates = **24 research cells**. At round-2 prices (mcp $1.3–2.0, shell
$0.4–1.2 per cell) that is **≈ $25–30**; wall-clock is 25–35 min per cell, and cells may run in parallel
across TeamCity agents (one cell per agent — the serialization rule is a local-Docker rule).

## Endpoints

Primary, per case:

- `U_obs(B)` — the share of the checklist whose evidence appeared in some single tool result of the
  prefix, by **environment interactions**;
- `U_obs(tokens)` — the same score against cumulative model output tokens, counted over **every** model
  in `modelUsage`, not just the top one. (Round 2's published token axis read only the main model and so
  under-counted the three delegating shell trajectories by 60–70 %; the correction is part of this
  round's parser.)

Secondary:

- `P` — whether the correct architectural precedent (`A1`) was identified;
- the per-category profile: which KIND of fact each arm holds at B = 5 and B = 10;
- `U_actionable(B)` from the blind judge, on the checkpoints that get distilled.

## Predictions, in the order they will be tested

1. **Ordering (primary contrast).** ΔU(calls) at B = 10, largest to smallest:
   `rename-method-wide` > {`client-auth-method`, `oauth-grant-type`} > `email-domain-mapper`.
2. **Replication.** ΔU(calls) > 0 at B = 10 and B = 20 on both new architecture cases.
3. **The sign flip holds.** ΔU(tokens) ≈ 0 or favours the shell arm on every case.
4. **Category asymmetry.** The semantic arm's advantage at B = 5 is concentrated in `PRECEDENT` and
   `SECONDARY_INTEGRATION`; `INVARIANT` facts arrive late in both arms.

## Statistics

The unit is the trajectory, so a case with 3 v 3 has an exact permutation floor of p = 1/20 = 0.05 and
cannot on its own carry the round. The primary test is therefore **stratified by case**: permute arm
labels within each case, sum the case-level differences (12 v 12 across four cases), and report the
exact two-sided p. The ordering contrast is tested as a pre-registered rank correlation between the
predicted case order and the observed ΔU(calls) order.

Per-case numbers are reported with their permutation floor stated beside them, so no reader mistakes
p = 0.05 for a result that could have been smaller.

## Stopping rules

- If the positive control shows **no** arm difference, the round is void: the instrument, not the
  hypothesis, is what failed. Report that and stop.
- If both new cases show ΔU(calls) ≈ 0 while their admission gates hold, the generalization fails, and
  the report says so with the per-category profile as the diagnosis.
- If a signal appears, extend to 5 trajectories per arm **on the cases that showed it**, and only those.
- No case may be added to the round after the first cell is queued.

## Deferred, with the reason

A second repository was scoped and rejected for this round on measurements, not taste: Kill Bill's tests
are TestNG and need a database (no plain oracle, and its research phase would need a different grading
story); Apache Camel is 1 124 Maven modules and 26 537 Java files against Keycloak's 189 and 8 263 —
outside what the container indexes in the time a cell has. Apache Dubbo (119 modules, 4 050 Java files,
pure Maven, JUnit 5, module-scoped builds that work without installing the reactor) is the viable
candidate, and the next round on the repository axis should begin with a single prewarm probe cell — no
agent, no oracle — exactly as the ripple pilot's step 0 did.
