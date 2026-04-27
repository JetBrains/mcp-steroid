# iter-02 analysis → iter-03 plan

## Did iter-02's edit land?

Yes, strongly. iter-02 §3.6 verdict (was §3.9 flaw in iter-01):

> MCP Steroid path (from rename recipe): `steroid_execute_code` with
> `RenameProcessor` — resolves the symbol at a position, finds all usages
> via the type system (imports, qualified references, overrides,
> implementations, annotations), applies the rename atomically.
> 1 call for dry-run preview, 1 call to apply.

The agent now reads the recipe and correctly identifies the semantic path.
§4 token-efficiency table: "Rename across 10 files | 10 Reads (~50KB) +
10 Edits (~5KB) | 1 call | MCP Steroid sends/receives ~50x less total".

The iter-00 category-(b) finding "The MCP Steroid LSP rename template does
not use semantic rename" is gone.

## iter-02 numbers

- Section 1 (a) count: 5 bullets — held (6 / 5 / 5 across iter-00/01/02)
- Section 1 (b) count: 2 bullets — shrunk from 3 / 2
- Section 7 unique capabilities: grew to 7 categories (added code-generation)
- Section 9 now has a clean one-line heuristic: symbol → MCP Steroid,
  text → built-ins
- Wall time: 12:39 (vs 20:31 / 12:58)
- Test: BUILD SUCCESSFUL, 0 failures (verdict regex correctly matched 21
  of the verdict variants the agent emitted)

## Residual gap for iter-03

The §1 category-(b) list still contains "Small text edits where the exact
string is known and unique". iter-01 added routing rows to
`skill/coding-with-intellij.md`, but **`mcp-steroid-info.md`** (the MCP server
instructions loaded once at session start) still reads:

> "Use it aggressively instead of manual file operations or shell commands."

That sentence tells the agent MCP Steroid targets file operations too —
directly contradicting the quick-reference routing. So the agent keeps
surfacing small edits as a category-(b) weakness ("applies but no
improvement") rather than category-(c) ("outside scope").

The fix is at the session-entry prompt, not a recipe.

## iter-03 edit (one narrow change)

Rewrite `prompts/src/main/prompts/mcp-steroid-info.md`:

- Drop "drop-in replacement for LSP" and "Use it aggressively instead of
  manual file operations" — both push the agent toward the wrong scope.
- Name the semantic zone (refactor, navigate, inspect, test, debug,
  code-gen, library-symbol resolution).
- Name the textual zone (read / edit known paths, free-text search, shell)
  and mark it as **out of scope** for MCP Steroid, not a "no-improvement"
  category — steering the agent's classification from (b) to (c).
- Keep the "Getting started" three-step unchanged.
- Respect MarkdownArticleContractTest: title 79 chars (≤80), description
  180 chars (≤200).

## Expected report change

- §1 (b) list shrinks further — small edits migrate from (b) to (c)
- §8 (outside scope) may gain a bullet about small text edits
- §9 decision heuristic should become sharper since the entry-point prompt
  now matches the quick-reference routing added in iter-01
