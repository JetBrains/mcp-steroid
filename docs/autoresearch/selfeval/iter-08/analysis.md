# iter-08 plan

## iter-07 signals

- Self-eval + full validation pass (12:04 + 3:37) — fastest so far.
- §1 (a) ballooned to 8 items (up from 4). Agent enumerated more specific
  capabilities — inspections, quick-fixes, signature help, run configs,
  debugger — in §1 rather than only in §7. Net positive.
- §1 (b) still 3 items (Reading file contents, Small 1-3 line edits, Simple
  text search). The file-reading item reappeared.
- iter-07's `mcp-steroid-info.md` policy statement landed as a fact but did
  NOT flip §9: the decision rule still says "Small edit (1-3 lines), known
  location, no cross-references | Edit" and "default to Edit for simplicity
  unless you need stable addressing for chained edits."

## Root cause of the stuck small-edit verdict

iter-07 §3.7 Task 7a shows the agent's model:
  "Payload: entire Kotlin script (~15-25 lines) + **full file content in saveText**."

This is wrong. `saveText(vf, updated)` writes inside the IDE JVM — those bytes
do not cross the MCP tool boundary. The script source is the input payload;
the file content read by `vf.contentsToByteArray()` and written back by
`saveText` stays on the IDE side. But the agent is double-counting them, which
makes MCP Steroid look ~10× worse on payload for small edits.

Note that the agent still, correctly, says iter-07 §4:
  "For chained edits, MCP Steroid is more token-efficient (no re-reads)"
so the mis-count only afflicts the ISOLATED small-edit case.

## iter-08 edit

Add a "Payload accounting for this recipe" paragraph right after the compact
recipe in `skill/execute-code-tool-description.md`:

- The MCP tool input is **only** the Kotlin script source (~200–400 chars).
- `vf.contentsToByteArray()` reads happen **inside** the IDE JVM — they do
  NOT cross the MCP boundary. Don't count them against the script's payload.
- `saveText(vf, updated)` writes back on the IDE side — same: not in the
  payload.
- Concrete example: 1-line change in a 160-line file. Edit ships ~60 bytes
  (old + new strings) but requires a prior Read of ~3600 bytes of that file;
  the recipe ships ~300 bytes of script and no Read. Net payload is smaller
  with MCP Steroid, not larger.

## Expected iter-08 report change

- §1 (b) "Small text edits" should either disappear or shift from
  "Built-in is more token-efficient" → "parity or MCP Steroid cheaper
  once forced-Read is counted".
- §4 Token-efficiency small-edit row should either invert (MCP Steroid
  smaller) or show the net-of-forced-Read computation.
- §9 decision rule should stop defaulting to Edit for small edits.
- §1 (b) may also lose the "Reading file contents" item now that the
  agent sees reads are IDE-side.
