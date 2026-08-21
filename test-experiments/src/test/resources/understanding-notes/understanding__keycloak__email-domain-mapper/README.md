# Committed notes of `understanding__keycloak__email-domain-mapper`

One file per downstream condition, named exactly as the cell that consumes it:

- `<arm>-b<budget>-l<limit>-r<replicate>.md` — the note a research run published, copied verbatim out
  of that build's artifacts. `UnderstandingDownstreamTest` reads it by that name and by no other, and
  refuses to run when it is missing rather than quietly sending no note.
- `oracle-<name>.md` — a hand-written, gold-derived note. **Calibration only.** It is written by
  someone who has seen the solution, so a cell run under it says nothing about acquiring
  understanding and must never appear in an mcp-versus-shell comparison.

An empty directory is the normal state before any research run has happened; the `baseline` condition
needs no file at all.

Copy a note in exactly as the run emitted it. Editing it here would make the downstream cell measure a
note no research run ever wrote, and nothing downstream could tell.
