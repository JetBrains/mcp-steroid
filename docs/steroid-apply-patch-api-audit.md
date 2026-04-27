# `steroid_apply_patch` API audit vs standard agent edit tools

Cross-referencing our tool's input shape and semantics against the
built-in file-mutation tools of the major AI coding CLIs. Evidence
sources: tool-use NDJSON from the DPAIA autoresearch runs (real Claude
Code CLI), cloned / inspected source (`openai/codex`,
`sst/opencode`), Pi documentation (`mariozechner.at/posts/
2025-11-30-pi-coding-agent`), and Anthropic Text-Editor tool docs.

## Comparison table

| Tool | Field names | Multi-file | Atomicity | Pre-validation | Notes |
|------|-------------|------------|-----------|----------------|-------|
| **Claude Code CLI `Edit`** | `{file_path, old_string, new_string, replace_all?}` | no (one file / call) | per-file | `old_string` must occur exactly once unless `replace_all=true` | Field names observed directly from DPAIA NDJSON |
| **Claude Code CLI `MultiEdit`** | `{file_path, edits: [{old_string, new_string, replace_all?}]}` *(from Anthropic tool reference; never observed in DPAIA runs)* | no (one file, N edits) | file-scoped: all or none | each edit's `old_string` must be unique before it is applied; edits are applied top-to-bottom, each sees the result of the previous | Agents rarely reach for it; Claude's `Edit` chain is the observed pattern |
| **Claude Code CLI `Write`** | `{file_path, content}` | no | full-file overwrite | n/a | |
| **Anthropic Text-Editor `str_replace`** | `{old_str, new_str}` (plus `path`) | no | per-file | exactly-one match | Different naming convention from Claude Code CLI |
| **Codex `apply_patch` (model-facing tool)** | `{input: string}` — opaque V4A envelope: `*** Begin Patch / *** Add File: / *** Update File: / *** Delete File: / *** Move to: / @@ / +/-/  lines` | yes | **not atomic** — `apply_hunks_to_files` writes each file sequentially with `fs.write_file`; mid-patch failure leaves partial state | envelope-level parse is up front, but per-hunk context match runs inside the write loop | Freeform grammar for GPT-5; JSON fallback for gpt-oss. NDJSON emits `ThreadItem::FileChange{changes:[{path,kind:{type:"add/update/delete", movePath?}, diff}], status}`. Sources: `codex-rs/apply-patch/src/lib.rs:260-361`, `protocol.rs:3807-3821`, `app-server-protocol/v2.rs:5432-6076` |
| **OpenCode `edit`** | `{filePath, oldString, newString, replaceAll?}` (camelCase) | no | per-file (semaphore-locked) | fuzzy-match pipeline (9 `Replacer` strategies) — throws if zero or >1 match | Auto-runs formatter + LSP diagnostics after the write and appends errors to tool output. Source: `packages/opencode/src/tool/edit.ts:35-45, 192-196, 673-710` |
| **OpenCode `apply_patch`** | `{patchText: string}` — same V4A envelope as Codex | yes | **near-atomic (2-phase)**: phase 1 parses + validates all hunks and derives new contents in memory; phase 2 writes sequentially (no rollback if a later write errors) | strong — `Patch.parsePatch` + `deriveNewContentsFromChunks` + `afs.stat` existence checks for every hunk before any write | Single permission prompt covering all paths. Source: `packages/opencode/src/tool/apply_patch.ts:41-209` |
| **Pi CLI `edit`** | `{path, oldText, newText}` | no | per-file | `oldText` must match exactly (including whitespace) | Minimal toolset philosophy ("read/write/edit is all you need"). No multi-edit / apply-patch. Source: `mariozechner.at/posts/2025-11-30-pi-coding-agent` |
| **Pi CLI `write`** | `{path, content}` | no | full-file | n/a | |
| **`steroid_apply_patch` (ours)** | `{project_name, task_id, reason, hunks: [{file_path, old_string, new_string}]}` | **yes** | **fully atomic** — pre-flight resolves every hunk in a single read-action, validates exactly-one-occurrence per hunk, then applies all hunks in a single `WriteCommandAction` command (one undo step, PSI committed in the same action) | yes, all hunks validated before any edit lands (throws `ApplyPatchException` with hunk index + path + both offsets on non-unique, missing, or unresolvable) | Plus DialogKiller + `Observation.awaitConfiguration` pre-flight to keep the write action from blocking on modals / project saves |

## Findings

### Field-name alignment

- **`old_string` / `new_string`** — matches **Claude Code CLI `Edit`** exactly. Codex's V4A envelope has no JSON field; Anthropic's Text-Editor calls them `old_str` / `new_str`; OpenCode's `edit` uses camelCase `oldString` / `newString`; Pi uses `oldText` / `newText`. Our choice is the most-widely-recognisable for agents that were trained against Claude Code.
- **`file_path`** — aligned with Claude Code `Edit`. Originally we used `path`; renamed to `file_path` in the same commit as this audit so Claude-trained agents can re-use their `Edit` knowledge without a translation step. (OpenCode `edit` uses camelCase `filePath`; Pi uses `path` — our choice matches the most common upstream.)
- **Our extra keys** (`project_name`, `task_id`, `reason`) are MCP-specific and have no counterpart in any built-in. They're justified: we need the project to run in, audit grouping, and a one-line summary for the execution log.

### Atomicity — we have the strongest guarantee

Ranked by how robust atomicity is across a multi-file patch:

1. **`steroid_apply_patch`** — pre-flight + single `WriteCommandAction` = actual all-or-nothing within the IDE's transaction boundary. If the write fails, nothing is committed.
2. **OpenCode `apply_patch`** — 2-phase (parse+validate all, then write all), but the write phase is sequential with no rollback if a later write errors mid-loop.
3. **Codex `apply_patch`** — parse up front, but no atomicity on write. Mid-patch failures leave the FS in an inconsistent state.
4. **Claude `MultiEdit`** — scoped to ONE file; sequential within the file.
5. **Claude `Edit`, OpenCode `edit`, Pi `edit`** — single hunk, single file. No atomic multi-hunk.

### Features we deliberately don't have

V4A envelopes (Codex, OpenCode `apply_patch`) cover file lifecycle ops: **add, delete, rename/move**. We support only `update`. That's by design — `steroid_apply_patch` is an in-place literal-text batcher, orthogonal to file creation (`Write`, `findProjectFile().delete()` inside `steroid_execute_code`, `moveClass` skill, etc.). Adding them would grow our surface without matching what users currently need (validated over iter-15 + iter-17 where no "add file" was part of any applyPatch call).

### Diff format — JSON-native vs V4A envelope

Codex + OpenCode `apply_patch` both accept an opaque V4A-style envelope as a string. Ours ships the hunks as structured JSON. Tradeoffs:

- **Structured JSON (ours)**: trivial to pretty-print, log, diff-in-diff, or round-trip through MCP's tool-input validation. No fragile text parser on the server.
- **V4A envelope (Codex/OpenCode)**: one string field is simpler to slot into an OpenAI function-call signature. But the parser is complex (handles context lines, hunk headers, file-kind sentinels, moves). Both of their parsers are 100s of lines of real code.

Our JSON-native choice avoids a parser entirely. Against Claude's `Edit` + `MultiEdit`, it's the natural multi-file generalisation.

## Recommendations

1. ~~Consider renaming `hunks[].path` → `hunks[].file_path` to match Claude's canonical naming.~~ **Applied in this commit.** Every hunk's file identifier is now `file_path`, matching Claude Code `Edit` exactly.
2. **Keep** `old_string` / `new_string`. Matches Claude Code CLI `Edit` verbatim.
3. **Keep** the JSON-native hunk array. Strictly easier to validate / log than V4A.
4. **Keep** `project_name`, `task_id`, `reason` — they're justified by MCP semantics.
5. **Do NOT add** `add`/`delete`/`move` ops to this tool — they belong to a separate tool (or to `steroid_execute_code` VFS APIs).
6. **Keep** the pre-flight-then-single-WriteCommandAction atomicity. Our guarantee is strictly stronger than every other tool surveyed.

## Sources

- DPAIA run NDJSON: `test-experiments/build/test-logs/test/run-20260423-080715-*/agent-claude-code-1-raw.ndjson` (+ 10 more runs) — observed Claude `Edit`, `Write` tool-use shapes.
- OpenAI Codex source: `~/Work/openai-codex/codex-rs/apply-patch/src/lib.rs:260-361`, `codex-rs/protocol/src/protocol.rs:3807-3821`, `codex-rs/app-server-protocol/src/protocol/v2.rs:5432-6076`, `codex-rs/tools/src/apply_patch_tool.rs:89-99`.
- OpenCode source: `~/Work/opencode-sst/packages/opencode/src/tool/edit.ts` (lines 35-45, 192-196, 673-710), `apply_patch.ts:41-252`.
- Pi Coding Agent: `https://mariozechner.at/posts/2025-11-30-pi-coding-agent/`.
- Anthropic Text-Editor tool: `https://platform.claude.com/docs/en/agents-and-tools/tool-use/text-editor-tool.md`.
