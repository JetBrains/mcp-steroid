# Round 7 — a second case for the `U`-to-outcome relation: pre-registration

Written before a single cell of this round was queued and before any number below was measured. It
changes nothing on the acquisition side: `U(B)`, the checklist, the budget rules, the degeneracy guard
and the unit of replication are untouched, and round 6's reading is not recomputed. What this document
fixes in advance is **which case may carry the replication, and by what measurement a candidate is
refused**.

## Why this round exists

`RESULTS-DOWNSTREAM-6-WAVE.md` measured the relation the family was built for — ρ(`U_note`,
downstream) = +0.62, p = 0.0074, and +0.85 on the thirteen notes whose replicates both built, against
ρ(`U_obs`) = +0.12 — on **one** case. `cc-refresh-token` is the only case its own anchors admit. The
other two were refused by the floor rule, and the reason is structural rather than accidental: round 4
step 6 measured that writing the implementation alone moves every existing oracle from 1 to 7 or 8 out
of 9 or 10, so ~80 % of each endpoint grades "did you write compiling code that does the thing" and
about 20 % grades the knowledge a hand-off note carries.

A second case is therefore not a robustness nicety. It is what separates "the relation holds" from
"the relation held on the one case that survived".

## What the second case must satisfy

Four requirements, three of them already machine-enforced by `AcquisitionDownstreamAdmission`:

1. eighteen notes with a computed `U` — i.e. a per-case fact checklist and six research trajectories;
2. an endpoint the unaided weak solver does **not** already satisfy (the floor rule, `BASELINE_SLACK`);
3. a ceiling the gold note reaches reproducibly, three rollouts of three;
4. weight on **discovery** rather than implementation — the requirement round 4 step 6 argued for and
   no existing oracle meets.

And one preference, stated by the project rather than by the instrument: a case where the **arms are at
parity**, so that "note quality predicts the outcome" is readable independently of any claim about
tools. Parity costs the primary analysis nothing, because the `U` spread the correlation is measured
over comes from the checkpoint ladder (0.13…0.80 in round 6's wave), not from the arms.

Excluded by prior use in the write-up: `cc-refresh-token` and `rename-method-wide`.

## The mechanism that makes this a measurement rather than a choice

The ripple family looks like the natural source of a parity case with a discovery-weighted endpoint. It
is, and the same property that gives it parity may also make it unmeasurable here: **on that family the
ripple is recoverable from compiler feedback.** Evidence, all of it already bought:

- across the 277 graded arms of series v3 the `f1 < 1.0` count is **0** — the site-level metric is
  saturated everywhere;
- `SemanticRippleOracle.kt` records that both arms of `rename-type-wide` scored *identically*, recall
  0.9798 and precision 0.9798 (194 of 198);
- `RippleCases.moveClassWide`'s own evidence states that the fully-qualified name it changes appears in
  no non-`.java` file;
- the only ripple case whose OUTCOME varies is `change-signature-wide`: the shell arm fails the gate
  8 of 10 (Claude) and 7 of 10 (Codex) against 10 of 10 for the semantic arm, and the knowledge there
  is which argument each call site must pass — which a compiler cannot supply.

If an endpoint is fully compiler-recoverable then its floor sits at its ceiling, a note has nothing to
buy, and the case fails requirement 2 no matter how attractive its parity is. That is a property of the
case, measurable **without an agent**, and it is what step 0a measures.

## Step 0a — the desk measurement, agentless

For each candidate, at the pinned base commit `60c4d5e9321ff5462a772ceb896f8cb2e639e04b`, classify
every obligation of the change as

- **compiler-visible** — omitting it makes `test-compile` of the case's `compileGateModules` fail;
- **compiler-invisible** — the tree still compiles with the obligation unmet.

The compiler-invisible families, and where each is counted:

| family | how it is counted |
|---|---|
| reflective references by string | `Class.forName` / `getMethod` / `getDeclaredMethod` mentions of the target's name in `.java` |
| the fully-qualified name in non-Java files | resources, service descriptors, JSON/XML/properties/theme messages |
| over-reach onto same-name declarations | the case's pinned `expectedDecoyDeclarations`: a wrongly changed decoy still compiles |
| runtime-only behaviour | what the hidden consumer asserts and no compilation observes |

**Decision rule, fixed here:** a candidate proceeds to step 0b only if it has **at least three
independently gradable compiler-invisible families**. Below three the endpoint is effectively binary,
and round 6 measured the within-note SD at 0.61 — a two-point scale cannot resolve notes at that noise.
A candidate refused at 0a costs no agent money and is reported with its counts.

## Step 0b — the floor probe, agentful

Only for a candidate that passed 0a. The weak solver (`claude-haiku-4-5`), no note, the case's own
ripple grade, **three repeats on one revision** — the family's own rule against publishing n = 1
(`TEAMCITY-WHITEPAPER.md` §9f.7).

Two harness facts that shape how this is run, both verified rather than assumed:

- the model **cannot** be selected at trigger time. TeamCity's Gradle runner maps only `-PtestFilter`,
  so `-Dclaude.model` never reaches the test JVM; the model is whatever the built revision's
  `DockerClaudeSession.DEFAULT_MODEL` says, and the probe therefore pins the weak model **in test
  code**, the way `RippleCheckpointProbeTest` and `AcquisitionDownstreamTest` already do;
- the account can queue builds but not cancel or reorder them, and cancelling one build of a round
  kills the round through its snapshot dependency. Probes are queued one at a time.

**Refusal:** all three repeats pass the gate with f1 ≥ 0.98. **Admission to Phase 1:** at least one
repeat fails the gate or reads f1 < 0.98.

A refusal is not an invitation to wall the allowance until a gap appears. Round 5 measured what that
costs: every extra interaction is handed to the no-note arm too, and on two cases it was worth more to
the arm with nothing to read than the note was to the arm with one — "the allowance was being raised to
pay for a defect".

## Candidate order, fixed in advance

| # | case | why it is in this order |
|---|---|---|
| 1 | `ripple__keycloak__move-class-wide` | the cleanest parity of series v3 (9/9 vs 10/10 Claude, 10/10 vs 10/10 Codex); 145 references, 50 files, 4 decoys |
| 2 | `ripple__keycloak__rename-type-wide` | the second parity case; 198 references, 41 files, 3 decoys, 74 foreign same-name call sites |
| 3 | `ripple__keycloak__change-signature-wide` | the declared fallback: the one ripple case with outcome variance, and the one where a note plausibly carries what the compiler cannot; 104 references, 49 files, 1018 decoy declarations, and **not** a parity case |

If both parity candidates are refused, the round proceeds on the fallback without further discussion,
and the write-up states that the parity case was refused **by measurement**, naming the counts — not
chosen against.

## Deviation, recorded on the day it was taken

Step 0a refused both parity candidates by the rule above. The pre-registration said the round would
then proceed on `change-signature-wide`. It does not: the same 0a criterion, applied to the feature
cases at no cost, shows `oauth-grant-type` carrying four compiler-invisible families, and its eighteen
notes are already bought — so the round moves there, and the fallback stays available and un-refused.

The deviation is written here rather than folded into the text above, because the honest record is that
the candidate order was fixed in advance, the measurement refused two candidates, and the third choice
was made **after** seeing a number. What was NOT changed after seeing a number is the refusal rule
itself.

## The re-weighted endpoint, pre-registered before it is built

Per-axis results for round 6's anchors were recovered from build logs (see `RESULTS-DOWNSTREAM-7.md`)
and reproduce every published total. The `oracle-v2` axis set for `oauth-grant-type` is fixed here:

**Retained (6):** the four axes on which the gold note and the no-note solver actually differ —
`theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt`, `theGrantAppearsInThePublishedGrantTypesSupported`,
`anOrdinaryInteractiveCredentialIsRefusedBeforeAnyTokenIsMinted`,
`anUnreadableCredentialIsAProtocolErrorNotAServerError` — plus the two trap axes that a tree which
changed nothing already satisfies, `theTokenContextShortCodeIsGloballyUnique` and
`theShippedGrantsAreUnchanged`. The traps are kept deliberately: they define the floor instead of
inflating it, the same way `cc-refresh-token`'s blast-radius assertion makes its floor 1 rather than 0.
Only one of the two is true of a pristine tree, and that was checked in the oracle's own code rather
than assumed: the uniqueness axis reaches `factoryUnderTest()`, which FAILS when no new grant factory
exists, so a tree that changed nothing scores the conservation axis and nothing else. The no-note
solver scores both, because it does write a grant — which is why its recomputed floor is 2 and sits
exactly on the `BASELINE_SLACK` limit rather than comfortably inside it.

**Dropped (4):** `aNewGrantImplementationIsCompiledIntoTheModule`, `theGrantTypeUriAClientWouldSendIsExposed`,
`aRequestWithoutACredentialIsRefusedAsAProtocolError`,
`aLongLivedCredentialIsAcceptedAndHandedToTheShippedRenewalPath` — every one of them satisfied by the
unaided solver in every replicate, which is what "the endpoint grades implementation" means when read
per axis rather than as a ladder aggregate.

Predictions, written before the first cell:

| rung / condition | predicted under `oracle-v2` | isolates |
|---|---|---|
| pristine tree | **1 of 6** — the conservation trap alone | the floor itself |
| implementation-only (the two gold `.java` files) | **4 of 6** | A2 + A5, the registration hop |
| naive-shortcut (`partial-naive-shortcut.patch`) | **5 of 6** | A4, the uniqueness invariant |
| gold | **6 of 6** | nothing — the ceiling |
| gold note, weak solver, three rollouts | **6 of 6, three of three** | reachability |
| no note, weak solver | **≤ 2 of 6** (pristine + `BASELINE_SLACK`) | the floor rule |

The recomputation of round 6's anchors under this subset — the same trees, the same axes, only a
different set aggregated — reads gold 6/6 three times and no-note 2/6 twice. **That recomputation is
not the validation**: it is the same data that chose the axes. It is recorded as the prediction, and the
fresh anchors below are what may confirm or refute it.

**One pre-registered rule is replaced, and the replacement is argued rather than assumed.** Round 4
step 6 asked that an implementation-only tree take under half the scale. That proxy cannot decide this
endpoint, for a reason visible in the ladder's own construction: a rung isolates ONE axis on purpose,
so every rung of this case scores N−1 by design — implementation-only loses exactly A2 and A5, the
naive-shortcut tree loses exactly A4. Reading "under half" off such a rung would refuse every
well-built ladder there is.

The direct criterion the proxy stood for is available per axis and is used instead:

> **No axis may be retained that the unaided, no-note solver passes in every replicate**, and at least
> two retained axes must be compiler-invisible wiring or discovery obligations.

Both halves hold for the set above: the four discriminating axes were failed by the no-note solver in
2 of 2 replicates that compiled, and A2 and A5 are the `META-INF/services` hop and the published
capability metadata. The two trap axes are exempt by their definition — they are retained *because* a
tree that changed nothing satisfies them, which is what makes them a floor rather than a score.

The ladder is still bought, for the thing it is actually good at: showing each retained axis is
independently reachable and independently loseable. Predicted rungs are in the table above.

Selection of axes from measured anchors is a real risk of fitting the endpoint to the answer, and the
guard is stated in advance: the retained set is **validated on fresh anchors**, three gold and three
no-note rollouts bought after the oracle exists, and the wave is not queued unless those reproduce the
predicted floor and ceiling. Two baseline cells are not a floor; they are what chose the hypothesis.

## The resolution risk, pre-registered because it is knowable now

Two pairs of the retained six have never been observed to move apart: A2 and A5 flip together on the
ServiceLoader line, and A7 and A9 fail together in every tree that lost either — the delegating tree
and both no-note anchors. So the six-point scale may behave as three groups plus two traps, and a
coarser endpoint resolves fewer distinct notes.

This is not a reason to inflate the axis count. It is registered as a limit on what the wave can claim,
with two consequences fixed in advance:

- the analysis reports how many DISTINCT outcome values the 36 cells actually produced, next to ρ. A
  correlation over three levels is reported as a correlation over three levels;
- if the reading is limited by resolution rather than by noise, the fix is the missing tree — one that
  checks the credential kind and still leaks a parse failure, separating A7 from A9 — and not a
  re-weighting chosen after seeing ρ.

## What the wave will and will not be allowed to say

- Primary: ρ(`U_note`, obligations) over eighteen notes × two replicates, with the permutation p, and
  the three confound checks round 6 ran — ρ(`U_note`, compiled), ρ(checkpoint, outcome), and the
  relation within each checkpoint and within each arm.
- A null result is a result: |ρ| < 0.3 on this case, with an instrument whose within-note SD is known,
  is published as a failure to replicate on a second case, not quietly dropped.
- The arm reading stays **secondary** here as in round 6. On this case the arms are far apart in
  acquisition (+0.40 at B=5, +0.38 at B=10), so a raw arm difference in the outcome is expected and is
  not evidence about tools beyond what the acquisition round already reports; the within-arm ρ is the
  reading that matters.

## What this round does not do

- It does not take a new codebase. That re-buys the instrument — pin, harness, statements, leakage
  audit, checklist, oracle, ladder, anchors — and rounds 3 through 5 were spent entirely on instrument
  defects on a codebase that already worked. External validity is worth buying after the relation holds
  on a second case, not instead of it.
- It does not touch round 6's reading, and it recomputes no `U`: `U(B)` is a property of the trajectory
  and the checklist and never depended on an oracle.
- It does not weaken any oracle, narrow any decoy set, or change any allowance to produce a difference.

## Amendment: the judge, and what to do about the notes it will not grade

Written after the judge run and **before any round-7 cell outcome was read** — the only round-7 logs
fetched at this point are the nine anchors already published in the results, and the wave cells are
untouched. It is an amendment rather than part of the original registration because the thing it
handles could not be known until the judge had run.

**The judge.** Both cases were judged in one pass by `anthropic/claude-opus-5`, pinned rather than
resolved, so the two cases share an instrument and the second case is not confounded by a different
or unknown judge. The run seeded the 54 already-committed notes first, so nothing was re-distilled:
the graded texts are exactly the ones the solvers received. On the cc-refresh-token side that also
fixes the scope — round 6's curve covers eight trajectories but only six carry committed notes, so
`mcp-b40-l2000-r10` and `none-b40-l2000-r4` are dropped rather than distilled fresh, because a note
no solver ever read cannot appear in a reading about what solvers did with notes.

**Three notes have no `U_note` and never will from this instrument.** On the oauth case the judge
answered `stop_reason=refusal` for `none-b40-l2000-r1@5`, `none-b40-l2000-r2@5` and
`none-b40-l2000-r2@10`. The harness records a refusal as a hole and not a zero, which is right — a
zero would sink a real note to the bottom of the ranking — but it declines to retry on the argument
that a refusal is about the content. That argument was an assumption, so it was measured: each of the
three prompts was replayed twice more, same model, same text, same budget, first parseable verdict to
win. Nine of nine refused. The holes are a property of those three note texts.

That is missingness correlated with the predictor, not random dropout: all three sit in the `none`
arm at the two lower checkpoints, which is where the low end of the `U_note` scale lives, so dropping
them shortens the range the correlation is measured over. Hence, registered here in advance:

- The primary ρ is reported over the **fifteen** oauth notes that have a `U_note`, with the three
  named.
- It is additionally reported twice as a **bound**: once with the three missing notes imputed at the
  lowest observed `U_note` and once at the highest. Bounds, not a point imputation — a single guessed
  value would launder an unknown into a number, whereas the pair answers the question that actually
  matters, which is whether the hole decides the result. If the sign and the |ρ| ≥ 0.3 verdict survive
  both bounds, the reading stands on the fifteen; if they do not, the result is bound-dependent and is
  published as bound-dependent.
- The secondary arm reading is unaffected and stays on all thirty-six cells: a cell's arm is known
  whether or not its note was graded.

## Amendment: a cell whose harness died before it could report

Also written **before any wave cell outcome was read**. One cell of the wave —
`none-b40-l2000-r3@5` replicate 61, build `1046929403` — ran for 29m36s and then died with
`IOException: Cannot run program "docker": Exec failed, error: 7 (Argument list too long)`, emitting
no `[ACQUISITION-DOWN]` line. The cause is transport, not the tree: the repair prompt carries the
compiler output plus the full text of every failing file, `docker exec` passes the whole command line
as one argv element, and Linux caps a single element at 128 KiB. The crash lands *after* the agent has
spent its budget.

This matters beyond one cell because the failure is **outcome-correlated by construction**: the
prompts that grow past the cap are the ones enumerating many failing files, which is what a
badly-compiling tree produces. Left unaddressed it would silently trim the low end of the outcome
scale — the same direction of bias the three ungraded notes introduce at the low end of `U_note`.

Registered in advance:

- A cell lost this way contributes **no row**. It is not scored 0: 0 is a measurement, and this cell
  produced none. Scoring it 0 would invent a worst-case reading for precisely the cells most likely
  to have crashed.
- Its note therefore folds from **one** replicate instead of two. The reading already reports how many
  notes lack exactly two replicates, and the results text names the build.
- Dropping a cell requires evidence, not assertion: the collector accepts an explicit build id and
  refuses it unless that log carries the E2BIG signature *and* no marker line, so a cell cannot be
  discarded for being inconvenient.
- The transport is fixed at the source (the prompt moves to stdin, `ClaudePromptArgsTest` gates the
  command-line length), but the fix is **not** retrofitted into this wave's numbers. Re-running the
  cell would run it at a different revision from its 35 siblings; whether to re-run it as a labelled
  addition is a decision for after the reading, and either way both build ids stay in the ledger.
