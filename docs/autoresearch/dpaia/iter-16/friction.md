# iter-16 — Codex on PetRest3: steroid_apply_patch NOT adopted, native file_change wins

Cross-agent validation. Does Codex also pick up the new MCP tool, or
stay with its native `file_change` event?

## Result

Codex **did NOT** invoke `steroid_apply_patch`. It used its native
`file_change` item (1 event, 8 file paths batched atomically).

| metric                  | iter-07 Codex (FS125) | iter-15 Claude (PetRest3) | **iter-16 Codex (PetRest3)** |
|-------------------------|-----------------------|----------------------------|-------------------------------|
| steroid_apply_patch     | -                     | 1 call (26 hunks)          | 0                             |
| file_change events      | 4 (18 files)          | -                          | 1 (8 files)                   |
| mcp_steroid_calls       | 3                     | 3                          | 2                             |
| **mcp_share**           | 0.075                 | **0.100**                  | 0.087                         |
| native_edit_calls       | 4 `file_change`       | 1 `Edit`                   | 8 (file_change paths)         |
| Fix success             | yes                   | yes                        | yes                           |
| Agent time              | -                     | 376 s                      | 334 s                         |

For the **same scenario** (PetRest3), Claude with the new tool now has
**higher mcp_share (0.100) than Codex (0.087)** — first time in the
entire loop that Claude leads on this metric. The dedicated MCP tool
is the equaliser.

## Why Codex doesn't adopt

`file_change` is Codex's native event type for batched file edits — it
already ships what `steroid_apply_patch` offers: atomic multi-file
writes, diff-level patches, single round-trip. Codex's tool-selection
logic stays with its built-in because:
- No client timeout pressure (Codex doesn't have Claude's 60s cap).
- `file_change` is zero-overhead — direct shell file writes, no MCP
  round-trip to an IDE.
- The arena prompt doesn't single Codex out.

Per iter-07's finding: Codex is natively closer to MCP-aligned
behaviour; the dedicated tool is **Claude-specific correction**.

## What this means for the research

The dedicated `steroid_apply_patch` tool is the **right architecture
for Claude**. Codex remains fine without it. Future work could either:
- Accept two paths (Codex → `file_change`, Claude →
  `steroid_apply_patch`).
- Add a Codex-specific nudge in the arena prompt to try the MCP tool,
  if there's value in agent-uniform behaviour.

Option 1 (accept two paths) is cleaner — different agents have
different internal mechanics; forcing them to use the same external
tool is a leaky abstraction.

## Absolute mcp_steroid calls (user's secondary goal)

| scenario  | agent  | iter   | mcp_steroid_calls |
|-----------|--------|--------|-------------------|
| PetRest3  | Claude | iter-15| **3**             |
| PetRest3  | Codex  | iter-16| 2                 |

Codex has lower absolute MCP calls too because its `file_change`
replaces what would otherwise need steroid calls. `steroid_apply_patch`
is genuinely reserved for Claude's inability-to-batch problem.

## DSL surface area

`dsl_methods_added_vs_baseline = 1`. Unchanged.
