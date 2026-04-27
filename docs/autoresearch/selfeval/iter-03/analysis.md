# iter-03' plan (replacement for reverted iter-03)

## Policy context

Iter-03 (ba1dbaf3) tried to reclassify small edits as "outside MCP Steroid's
scope" by splitting mcp-steroid-info.md into semantic / textual zones. That
was reverted: MCP Steroid should stay the default tool for every edit so the
IDE VFS remains authoritative.

The agent's iter-02 category-(b) complaint about small-edit PSI script
overhead is still real — but the fix is to make the in-IDE path compact,
not to route around MCP Steroid.

## iter-03' edit

Two changes in `prompts/src/main/prompts/skill/coding-with-intellij.md`:

1. **Remove** the two iter-01 (ac8a0182) routing rows that sent small edits
   and single-file private renames to the built-in Edit tool. Both contradict
   the new "IntelliJ is always better" policy.

2. **Promote** the `VfsUtil.saveText` pattern (already present under the
   "exception" note in skill/execute-code-overview.md) into the primary
   Quick Reference table as a new row: "In-place edit of any size (1–1000+ lines)".
   The row spells out the 5-line recipe and explicitly says this stays inside
   the IDE JVM so VFS/PSI auto-refresh, contrasting with `Edit` which leaves
   PSI stale. This gives the agent a compact, payload-competitive in-IDE path
   for small edits — same ergonomics as `Edit(old, new)` but through the IDE.

## Expected change in the iter-03' report

- §1 (b) list should shrink: the "small text edits" complaint should either
  disappear or shift to a neutral observation ("both paths take ~100 bytes")
  rather than a category-(b) finding.
- §4 token analysis should lose the "~4x more payload" claim since the
  Quick-Reference path matches `Edit`'s payload.
- §9 decision heuristic: may now read more like "MCP Steroid for everything
  semantic or involving the project model; `Edit`/`Grep`/`Bash` only when the
  IDE is not involved at all (shell, git, free-text log grep)."

## What this enables later

The parked apply-patch recipe (task #4) extends this pattern to **multiple
substitution sites at once**, closing the last remaining category-(b) gap.
After apply-patch lands, the iter-01-style "use Edit for small edits" rows
can stay retired permanently and MCP Steroid becomes the uniform path for
all in-workspace edits.
