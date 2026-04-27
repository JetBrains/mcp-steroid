# iter-05 plan

## Gap surfaced by iter-04

Iter-04 §3b Task 7a verdict:
> Built-in Edit is strictly better for small edits.

Agent's reasoning:
> MCP Steroid path: `steroid_execute_code` with PSI: find method by name, get
> document, compute offset, replace text in `writeAction`. 1 call but ~15 lines
> of Kotlin code as payload.

The agent STILL reached for a heavy PSI-based pattern for a 1-3 line edit,
ignoring the compact `VfsUtil.saveText` recipe I added to the Quick Reference
table in `skill/coding-with-intellij.md` at iter-03' (f0ff0af1).

Hypothesis: the compact recipe is one `steroid_fetch_resource` hop deep
(only in `mcp-steroid://prompt/coding-with-intellij`), but the agent's
default behaviour — shown repeatedly in iter-00 through iter-04 — is to
fetch the skill guide once at session start, then generate scripts from
memory plus whatever is in the tool description. The quick-reference
table row is never re-surfaced when the agent thinks about a small edit.

## iter-05 edit

Add the compact in-place-edit recipe inline to
`prompts/src/main/prompts/skill/execute-code-tool-description.md`.

This file is the MCP tool description — every single `steroid_execute_code`
call carries it in the tool schema that the agent sees. It is the
highest-visibility prompt location in the project.

The new section says:
- Use `steroid_execute_code` for in-place edits of ANY size (not native `Edit`)
- Shows the 5-line read+replace+writeAction recipe verbatim
- Explains the VFS consistency reason explicitly
- Gives regex + exactly-one-occurrence variants
- Tells the agent NOT to pre-Read via the native tool (the recipe already reads)

## Expected report change

- §3b Task 7a: agent should either use the compact recipe or at least
  acknowledge it (~5 lines, not "~15 lines of Kotlin boilerplate").
- §4 Token-efficiency small-edit row should compress MCP Steroid's column
  from ~200 tokens to ~80-100 tokens, narrowing or eliminating the "4x
  smaller" gap.
- §1 (b) "Small edits" bullet should either disappear or flip its framing
  from "built-in strictly better" to "parity, prefer MCP Steroid for VFS
  consistency".
