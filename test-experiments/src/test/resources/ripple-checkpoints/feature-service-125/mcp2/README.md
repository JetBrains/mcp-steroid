# `dpaia__feature__service-125` — mcp arm, SECOND capture (round 2)

The states of the second independent mcp trajectory, captured by
`DpaiaFeatureService125CheckpointCaptureTest.captureMcpArm` at the round-2 revision and probed as
`arm=mcp2`.

**Why the round is in the arm name.** A probe build addresses a cell by `arm`, `index` and `replicate`
only, and those three parameters are declared in a separate repository's TeamCity DSL. A fourth
`capture` coordinate would need a cross-repo commit before one cell could run; a new arm token needs
nothing — round 1 proved TeamCity forwards a prompted value outside its `select` options (it ran
indices 6..10 against options 1..5). `../mcp/` therefore stays byte-identical, which every number in
`docs/ripple-checkpoint-pilot/RESIDUAL-DIFFICULTY.md` depends on.

**What lands here, and how it is chosen.** Not the ten-fraction grid. Round 2 probes FOUR states per
trajectory, selected by the rule pre-registered in
`docs/ripple-checkpoint-pilot/REPLICATION-2.md` before any capture-2 verdict was read:

- `C1` = `M0`, the first write;
- `C2` = `T − 1` and `C3` = `T`, where `T` is the step with the largest single-step increase in
  `layerCov` (ties → earliest);
- `C4` = the last step whose tree differs from the final one.

The same rule runs on both arms. `checkpoints.json` keeps exactly the shape
`RippleCheckpointRecorder.metadataJson` emits, so `RippleCheckpointProbeTest` validates this pair as it
validates round 1's: the committed patch set must be precisely the set of steps the metadata names.

Copy the patches and `checkpoints.json` out of the admitted capture build's run-directory artifact
(`checkpoints/`) in ONE commit — patches from one run beside metadata from another describe a
trajectory that never existed.

Until the capture lands, this directory holds only this README. That state is deliberately green:
`RippleCheckpointProbeTest` asserts the layout exists and that whatever IS committed is consistent,
while `probeCoordinates` refuses every cell of an arm with no `checkpoints.json`, so a probe queued too
early fails in the first milliseconds of its build rather than an hour in.
