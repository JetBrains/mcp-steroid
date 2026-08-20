# `dpaia__feature__service-125` — shell arm, SECOND capture (round 2)

The states of the second independent shell-only trajectory, captured by
`DpaiaFeatureService125CheckpointCaptureTest.captureShellArm` at the round-2 revision and probed as
`arm=none2`. It is the control the `mcp2` curve is read against, and the two must come from the same
Docker image build — never from two different ones.

See [`../mcp2/README.md`](../mcp2/README.md) for why the round is encoded in the arm token, which four
states are committed here, and how the pre-registered selection rule in
`docs/ripple-checkpoint-pilot/REPLICATION-2.md` picks them. The rule is identical for both arms; only
the trajectory it is applied to differs.

The step numbers here and in `../mcp2/` are expected to differ, and by more than round 1's did if the
arms behave as round 1 suggests: the shell capture of round 1 needed 57 tool calls against the mcp
capture's 26. Round 2 compares the arms on several upstream denominators — tool calls, cumulative Opus
output tokens (recorded exactly this round), agent seconds and edit fraction — precisely because no
single one of them is a fair axis on its own.

Until the capture lands, this directory holds only this README; that state is green by design.
