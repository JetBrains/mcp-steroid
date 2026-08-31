# Round 8 — the lever probe, and the scale rule it decides

Written before a single round-8 cell is queued. Round 7 ended on a plateau, and the three ways out
of it — a stronger solver, more interactions, a longer note — are each a parameter that can
manufacture a result. So the choice between them is registered here, together with the rule that
says which outcome would make the round a failure.

## What round 7 actually established, once the anchors were put on the same instrument

Round 7 published the wave as `x/6` against `oracle-v2` and the anchors as `x/10` against the
ten-axis oracle, so the two were never compared. They are comparable: the six axes `oracle-v2`
retains are **byte-identical** to the same six in the ten-axis oracle, shared assertion helper
included, so the anchor build logs already contain the six-axis reading. Extracting it costs
nothing and no cell is re-run — it is the same surefire XML, scored against the smaller axis set.

On `oauth-grant-type`, allowance 25, solver `claude-haiku-4-5`:

| condition | obligations of 6 | builds |
|---|---|---|
| no note at all (`baseline`) | 2, 2 | 2 of 3 |
| a distilled note (wave, `mcp` arm) | mean 4.06 | 15 of 18 |
| the gold patch (`oracle-gold`) | 6, 6, 6 | 3 of 3 |

This corrects the round-7 results text, which called the endpoint near-constant and concluded the
case could not measure. The endpoint is near-constant **inside the note arm** — that part stands —
but floor 2 / note 4 / ceiling 6 is a working three-level scale, and the note's two obligations are
not noise. `RESULTS-DOWNSTREAM-7.md` is corrected rather than left standing.

The plateau is now located exactly. Per axis, over the 32 wave cells that produced a surefire block
and the anchors above:

- `theShippedGrantsAreUnchanged` and `theTokenContextShortCodeIsGloballyUnique` pass in **every**
  condition, floor included. They discharge nothing and inflate every reading by a constant 2.
- `theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt` and
  `theGrantAppearsInThePublishedGrantTypesSupported` are what the note buys: the floor fails both,
  a note-carrying agent passes both. They share no assertion helper and reach the grant by
  different routes (registered-factory lookup versus the compiled-classes scan), so they are two
  independent items.
- `anOrdinaryInteractiveCredentialIsRefusedBeforeAnyTokenIsMinted` and
  `anUnreadableCredentialIsAProtocolErrorNotAServerError` fail in every condition except gold —
  32 of 32 and 31 of 32 on the wave. They share `assertRefusedBeforeIssuing`, so they are one
  obligation counted twice.

So the whole of round 7's remaining headroom is a single obligation — *refuse before minting* — and
no agent holding any note ever reached it. That is the plateau, and it is what round 8 has to move.

## The three levers, and the two that are eliminated before any money is spent

The instruction was to pick a stronger model, more iterations, or a longer note. Two of the three
are ruled out by evidence already in hand, and saying so is cheaper than probing them.

**A longer note cannot be it.** The eighteen committed notes run 2476–3807 characters against an
`l2000` label, so the cap was never binding — the label names the condition the note was distilled
under, not a truncation that happened. Sixteen of the eighteen already mention refusal explicitly,
and the highest-scoring note in the corpus (`U = 0.87`, 3807 characters) still reads 4 of 6. The
agent was told and did not act; more text is not the missing input.

**More repair rounds cannot be it.** The repair turn is handed javac diagnostics and the contents
of the files javac named, and nothing else — that is what keeps it from leaking the repository. A
tree that compiles but never refuses an ordinary refresh token produces no diagnostic, so no number
of repair rounds can reach the refusal obligation. The cap did bind for 7 of 35 cells, but it binds
on compilation, which is a different axis from the one that is stuck. Left at three.

**Two live levers remain: the allowance, and the solver.** Both are probed, and both are probed
*with the floor*, because the floor is the risk. Round 5 raised the allowance and withdrew it again:
extra interactions are handed to both arms, and on these cases they were worth more to the arm with
nothing to read than the note was to the arm holding one. The same trap is open for a stronger
solver.

## The probe

`oauth-grant-type` only. Four settings, two replicates each: **14 cells**.

| setting | allowance | solver | conditions | why this point |
|---|---|---|---|---|
| S0 | 25 | `claude-haiku-4-5` | `baseline` ×2 | the reference floor, re-measured on **this** revision |
| S1 | 40 | `claude-haiku-4-5` | `baseline` ×2, note ×2 | the research bill alone, without the solver's own allowance on top — the same derivation as 60 with one term dropped |
| S2 | 60 | `claude-haiku-4-5` | `baseline` ×2, note ×2 | already derived and registered: the unaided solver's whole bill (40 research + 20 solve) |
| S3 | 25 | `claude-sonnet-5` | `baseline` ×2, note ×2 | the solver lever at the allowance the wave already ran, so the wave is its reference |

S0 exists because the free floor above was measured at round 6's revision while the wave ran at
round 7's, and the reference gap `g` would otherwise straddle two revisions of the solver prompt,
the budget hook and the repair turn. Two cells settle whether the floor at 25 is still 2 of 6. If
S0 disagrees with the anchors, the anchors stop being a usable reference and every setting is read
against S0 instead — which is why S0 runs at the same time as the rest and not after. Skipping this
check is the specific mistake round 7 made: the cheap reading that would have caught the problem
existed, cost nothing, and was never taken.

Conditions per setting: `baseline` (no note) ×2, and one note ×2. The note is
**`mcp-b40-l2000-r3@20`**, chosen as the highest `U_note` in the corpus (0.87 on the recorded
`anthropic/claude-opus-5` instrument) — an instrument-side criterion, decided without reading any
cell outcome. Choosing the best note is deliberate: the probe asks whether the ceiling is reachable
*at all*, and if the best note cannot reach it, no note will.

No gold cells. Gold already reads 6 of 6 at allowance 25, and neither a larger allowance nor a
stronger solver can lower a ceiling that is already full.

## Registered in advance — the decision rule

The primary readout is the **gap**, not the level: `g = mean(note) − mean(no note)`, in obligations,
computed per setting. Its reference is the free reading above — at allowance 25 with
`claude-haiku-4-5`, `g = 4.06 − 2.0 = 2.06` on the six-axis scale — subject to S0 confirming the
floor half of it on this revision. The floor there rests on two graded cells, which is thin; it is
the reason the rule below is stated as a threshold on the floor rather than as a significance test
the sample could not support.

A setting is adopted for the round-8 wave only if **both** hold:

1. **The ceiling moves.** At least one note cell discharges the refusal obligation — that is, both
   refusal axes pass in the same cell. Without this the lever changed nothing about the plateau.
2. **The floor holds.** The no-note arm stays at or below **3 of 6** (equivalently, it does not
   discharge more than one of the three items in the scale below). A floor that climbs has absorbed
   the note's advantage, and the wave would measure the allowance rather than the note.

If no setting satisfies both, that is the round's result and it is published as such: the case's
headroom is exhausted at this solver class, and no further lever is tried. Naming the failure
outcome before the cells run is the whole point of writing this down — round 5 raised the allowance,
found the floor had risen, and withdrew it; that withdrawal is the precedent this rule encodes so
the same discovery does not have to be made a third time at full wave price.

Ties and partial outcomes: if more than one setting satisfies both conditions, the one with the
larger `g` is adopted; if they tie on `g`, the cheaper one (lower allowance, weaker solver) is
adopted, because the cheaper setting is the one whose result generalises to a weak agent.

## Registered in advance — the scale rule, decided on the probe's cells

The six-axis scale double-counts one obligation and pays two axes for nothing. The rule below fixes
that, and it is registered now and applied to **the probe's** cells, so the round-8 wave is scored
by a scale that was fixed before the wave existed.

- **A constant axis is excluded.** An axis with the same verdict across every cell of the probe —
  floor, note and the free gold anchors included — leaves the scale. It discharges no obligation
  the conditions differ on, and it moves every mean by the same constant while shrinking the
  visible range.
- **Axes sharing an assertion helper count as one item.** Two axes that reach their verdict through
  the same helper are one obligation wearing two names, and counting both weights it twice against
  every independent axis.
- **A collapsed item is discharged only when every axis in its group passes.** The group's
  obligation is the helper's, and a cell that passes one axis of the pair and fails the other has
  not met it. This is the conservative direction and it is chosen deliberately: the alternative
  would let the round's one remaining obligation be scored as half-met.
- **The exclusion is decided on the probe and then frozen.** An axis that is constant on the probe
  stays out of the wave's scale even if it varies there — otherwise the scale is re-chosen after
  seeing wave outcomes, which is the thing this document exists to prevent.

Applied to what is already known, the rule is expected to yield **three items** —
*registered-and-dispatchable*, *published-in-grant-types*, *refuses-before-minting* — on which the
conditions read floor 0, note 2, gold 3. The probe's cells decide whether that expectation holds;
the constancy of the two excluded axes has been observed over 32 wave cells and 5 anchors, but not
yet at allowance 40 or 60 or under a stronger solver, and it is the probe that gets to confirm it.

Both scales are published side by side for the probe — six axes and three items — so the rule's
effect on the numbers is visible rather than asserted.

## What has to change in the harness

- `ACQUISITION_FLOOR_PROBE_BUDGETS` gains 40, becoming `setOf(ACQUISITION_RESEARCH_BUDGET, 60)`.
  Both rungs stay **derived** rather than chosen — 60 is the research bill plus the solver's
  allowance, 40 is the research bill alone — so 45 is still refused and the probe set cannot drift
  into a place to put a generous number. It stays disjoint from
  `ACQUISITION_DOWNSTREAM_BUDGETS` — the wave's allowance set stays closed at {15, 20, 25}, because
  that set is the one parameter that could manufacture a wave result, and a floor probe is not a
  candidate wave setting. `AcquisitionDownstreamHarnessTest` asserts the probe set is a singleton
  and is updated to assert the pair and the disjointness instead.
- The solver lever needs no code change: `AcquisitionCellTests` honours an already-set
  `claude.model` property and only defaults to `claude-haiku-4-5` when none is given, so S3 is
  queued with `-Dclaude.model=claude-sonnet-5`.
- `ACQUISITION_REPAIR_ROUNDS` stays at 3, for the mechanism reason given above.
