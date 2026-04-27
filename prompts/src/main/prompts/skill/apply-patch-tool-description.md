Apply Patch Tool

MCP tool description for the steroid_apply_patch tool.

###_NO_AUTO_TOC_###
Atomic multi-site literal-text patch. Apply N `old_string → new_string`
substitutions across one or more files in a single undoable command.

Use this INSTEAD of chaining 2+ native `Edit` calls. Pre-flight
validates every `old_string` is present exactly once per file; if any
hunk fails validation, NO edits land (all-or-nothing). Multi-hunk
edits in the same file apply in descending-offset order automatically
so earlier edits don't shift later ones.

Why this tool vs `steroid_execute_code` with `applyPatch { }`: this
bypasses kotlinc compilation, so large patches (8+ hunks, 3k+ char
payloads) complete in tens of ms instead of tens of seconds — matters
for Claude Code CLI's 60s per-tool MCP timeout.

Input parameters:
- `project_name` (string) — project name from `steroid_list_projects`
- `task_id` (string) — your task id; reuse across related calls
- `reason` (string, optional) — one-line summary
- `hunks` (array of objects) — each with `file_path`, `old_string`, `new_string` (all strings)

Field names match Claude Code `Edit` exactly (`file_path`, `old_string`, `new_string`) — agents that already know `Edit` can re-use their knowledge directly.

Example hunks: `[{"file_path": "/abs/A.java", "old_string": "old", "new_string": "new"}, {"file_path": "/abs/B.java", "old_string": "other", "new_string": "replacement"}]`.

Return: human-readable audit — `N hunks across M file(s) applied
atomically` + per-hunk `path:line:col (oldLen→newLen chars)`.

Same underlying engine as `steroid_execute_code`'s `applyPatch { hunk(…) }`
DSL — identical semantics, no boilerplate, no compile overhead.
