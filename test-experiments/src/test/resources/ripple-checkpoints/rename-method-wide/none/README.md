# `rename-method-wide` — none (shell) arm checkpoints

The states captured by `KeycloakRenameMethodWideCheckpointCaptureTest.captureShellArm`.

**No probe cell addresses this case any more** — the pilot probes
`dpaia__spring__boot__microshop-18` instead (see `../../microshop-18/`), whose solution is built
incrementally and graded by a test suite. The directory stays, checked by `RippleCheckpointProbeTest`,
because this case was already measured and its states are what its numbers mean.

Expected contents once a capture has run and been admitted:

- `step-<a_i>.patch`, one per planned checkpoint (at most `RIPPLE_CHECKPOINT_COUNT` = 5) —
  `git diff step-0 step-<a_i>` of the recorded trajectory. **The file names are NOT known before the
  run**, and they are NOT the same steps as the `mcp` arm's: the positions are `round(n·(i/6)^1.5)` of
  each capture's own MEASURED tool-call count `n`. The two arms' curves are therefore compared at equal
  NORMALIZED positions `a_i/n`, never at equal tool-call counts.
- `checkpoints.json` — `RippleCheckpointRecorder.exportMetadata`, carrying the measured `n` and, per
  checkpoint, its `index`, `nominalStep`, `step`, `position` = `step/n`, `patch` file name and every
  correction the selection had to make.

Copy the patches and `checkpoints.json` out of the admitted capture build's run-directory artifact
(`checkpoints/`) in ONE commit — a mismatch between them means they come from different runs, and
`RippleCheckpointProbeTest` refuses it.

This README is what keeps the directory in git while it holds no patches: an empty directory cannot be
committed, and the layout is asserted to exist because a probe cell must fail on a missing STATE rather
than on a missing folder.
