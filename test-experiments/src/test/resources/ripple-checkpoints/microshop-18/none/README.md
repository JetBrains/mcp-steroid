# `dpaia__spring__boot__microshop-18` — none (shell) arm checkpoints

The states `RippleCheckpointProbeTest.probe` restarts a bare Haiku from, captured by
`DpaiaMicroshop18CheckpointCaptureTest.captureShellArm`.

Expected contents once the capture has run and been admitted:

- `step-<a_i>.patch`, one per planned checkpoint (at most `RIPPLE_CHECKPOINT_COUNT` = 5) —
  `git diff step-0 step-<a_i>` of the recorded trajectory. **The file names are NOT known before the
  run**, and they are NOT the same steps as the `mcp` arm's: the positions are `round(n·(i/6)^1.5)` of
  each capture's own MEASURED tool-call count `n`, so only `RippleCheckpointRecorder.plan` can say
  which steps they are. A probe cell addresses a checkpoint by its ORDINAL (1..5) and resolves the step
  through `checkpoints.json`.
- `checkpoints.json` — `RippleCheckpointRecorder.exportMetadata`, carrying the measured `n` and, per
  checkpoint, its `index`, `nominalStep`, `step`, `position` = `step/n`, `patch` file name and every
  correction the selection had to make. `V_mcp` and `V_shell` are only comparable through those
  normalized positions.

Copy the patches and `checkpoints.json` out of the admitted capture build's run-directory artifact
(`checkpoints/`) in ONE commit — patches from one run and metadata from another describe a trajectory
that never existed, and `RippleCheckpointProbeTest` refuses that mismatch.

This README is what keeps the directory in git before the capture lands: an empty directory cannot be
committed, and `RippleCheckpointProbeTest` asserts the layout exists because a probe cell must fail on
a missing STATE rather than on a missing folder.
