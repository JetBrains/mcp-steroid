# Downstream replication on the two new cases — negative, and the reason is in the instrument

Pre-registered in `DESIGN-DOWNSTREAM-3.md` (with amendment 1 written after the anchors and before the
note cells). 22 cells bought, ≈ $6. **The primary correlation could not be computed: every one of the
twelve note cells scored zero obligations.** What follows is the diagnosis, because a wave of zeros is
only useful if it says why.

## What was measured

| case | assertions | `baseline` | `oracle:gold` | notes (12 cells) |
|---|---|---|---|---|
| `oauth-grant-type` | 10 | 0, 0 | **10, 10** | **0 × 12** |
| `client-auth-method` | 9 | 0, 0 | 9, 0, 0, 0 | not bought |

`oauth-grant-type` passed all three gates: a floor of zero, a ceiling reached twice out of two, a gap of
ten. On that basis its twelve note cells were bought, exactly as the pre-registration says. All twelve
returned 0 of 10.

## Why every note cell scored zero

Not one of the twelve failed on an assertion. **All twelve failed to compile**, and the errors are the
agent's own code in `services` — no module-scope artefact, no harness fault:

| failure | cells |
|---|---|
| referenced a constant that does not exist, in a class of ANOTHER module — `OAuth2Constants.OFFLINE_REFRESH*`, `Errors.INVALID_GRANT` | 8 of 12 |
| wrong type at the delegation point (`incompatible types` returning the parent's response) | 7 of 12 |
| hallucinated package (`javax.xml.bind`, `javax.keycloak.models`, `org.mockito`) | 3 (incl. 2 of the 4 client-auth ceiling runs) |
| referenced `ServerStartupException`, which the tree does not have | 1 |

The first row is the interesting one, and it is a property of the CASE rather than of the agent. The
shipped precedent, `RefreshTokenGrantTypeFactory`, names its grant through
`OAuth2Constants.REFRESH_TOKEN_GRANT_TYPE` — a constant in the `core` module. A solver imitating the
precedent therefore writes `OAuth2Constants.OFFLINE_REFRESH_GRANT_TYPE` and must add that constant to a
second module, which the grading build (`-pl :keycloak-services`) does not recompile. The gold change
sidesteps this by declaring the URI as its own `public static final String` inside the new factory —
a legitimate choice, but one the repository's own idiom argues against.

So the case punishes the solver for following the precedent, and the note cannot rescue it: the gold
note is the only note that names the literal instead of the idiom, which is precisely why the two
ceiling cells compiled and the twelve note cells did not.

## What this does and does not say

- It does **not** contradict `RESULTS-DOWNSTREAM-2.md`. That round measured a real correlation
  (ρ = +0.668, ρ(U_note) = +0.825) on a case whose implementation is four files inside one module.
- It does **not** say the acquisition result is wrong. The curves of these two cases were measured on a
  research agent that never edits anything, and are unaffected.
- It **does** say the downstream instrument has a floor effect the previous round did not expose: below
  the assertion level sits one boolean — did `javac` succeed — and when the implementation is beyond the
  weak solver at the pre-registered budget, all N independent axes collapse into that single bit. The
  de-cascading done at the oracle level cannot reach it.
- It **does** say these two cases were designed for the wrong endpoint. They were built to make
  RESEARCH hard, and they succeed at that; nothing in their design was constrained to keep the
  IMPLEMENTATION within reach of a weak agent in twenty interactions. `cc-refresh-token` satisfied both
  by accident, not by construction.

## The three repairs, in the order they matter

1. **Grade compilation as its own axis.** A cell that does not compile should score its compile axis 0
   and its behavioural axes as *unmeasured*, not as failed. Averaging an unmeasured axis as zero is the
   same error as the `{0} ∪ {5..8}` scale of round 1, one level down.
2. **Widen the grading build to the modules the precedent's idiom touches** (`-pl :keycloak-services
   -am`, or an explicit second module), so that following the in-tree convention is not automatically
   a compile failure.
3. **Admit a case to the downstream family only after a gold-note ceiling of ≥ 3 replicates.** Two was
   too few: `client-auth-method` looked like 9/9 on its first ceiling run and is 9, 0, 0, 0 over four.

Until (1) and (2) are done, buying the remaining twelve `client-auth-method` cells would buy twelve more
zeros — its ceiling is 1 of 4 — so they were not bought. That decision is the pre-registration's own
stopping rule, applied.

## Cost

| wave | cells | spend |
|---|---|---|
| anchors (both cases) | 8 | $2.3 |
| `oauth-grant-type` notes | 12 | $3.3 |
| `client-auth-method` extra ceiling replicates | 2 | $0.4 |
| distillation of 24 notes (one wave lost to a judge refusal, re-seeded) | — | $4.6 |

Build ids in `RUN-IDS.md`. Every note of both cases is committed under
`test-experiments/src/test/resources/acquisition-notes/<caseId>/`, so the repaired instrument can re-run
this exact matrix without re-buying research or notes.
