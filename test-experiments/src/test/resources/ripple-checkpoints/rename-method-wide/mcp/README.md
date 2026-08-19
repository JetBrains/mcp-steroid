# `rename-method-wide` — mcp arm checkpoints

The states captured by `KeycloakRenameMethodWideCheckpointCaptureTest.captureMcpArm`.

**No probe cell addresses this case any more.** The pilot moved to
`dpaia__spring__boot__microshop-18` (see `../../microshop-18/`) because this case's edit is atomic: in
the real capture (TC build 1034656372) the work tree went from untouched straight to all 111 files
renamed at step 11, and two scheduled checkpoints came out byte-identical — a readiness CURVE cannot be
measured on a solution that appears all at once. The directory stays, checked by
`RippleCheckpointProbeTest`, because it is a second, already-measured case and a published curve is only
readable next to the trajectory it came from.

Expected contents once a capture has run and been admitted:

- `step-<a_i>.patch`, one per planned checkpoint (at most `RIPPLE_CHECKPOINT_COUNT` = 5) —
  `git diff step-0 step-<a_i>` of the recorded trajectory. **The file names are NOT known before the
  run**: the positions are `round(n·(i/6)^1.5)` of the MEASURED tool-call count `n`, corrected where a
  nominal step repeats an already-probed state, so only `RippleCheckpointRecorder.plan` can say which
  steps they are. The first fixed schedule this pilot used assumed `n = 32` and met runs of 23 and 51.
- `checkpoints.json` — `RippleCheckpointRecorder.exportMetadata`, carrying the measured `n` and, per
  checkpoint, its `index`, `nominalStep`, `step`, `position` = `step/n`, `patch` file name and every
  correction. Without it nobody can say what fraction of a trajectory a patch represents.

Copy the patches and `checkpoints.json` out of the admitted capture build's run-directory artifact
(`checkpoints/`) in ONE commit — patches from one run and metadata from another describe a trajectory
that never existed, and `RippleCheckpointProbeTest` refuses that mismatch.

This README is what keeps the directory in git while it holds no patches: an empty directory cannot be
committed, and the layout is asserted to exist because a probe cell must fail on a missing STATE rather
than on a missing folder.
