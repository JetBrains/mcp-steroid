# apply-patch recipe evaluation task

You have access to MCP Steroid — an IntelliJ plugin that exposes the running IDE
to you via MCP tools. Key tools:

- `steroid_list_projects` — list open IntelliJ projects, pick one
- `steroid_fetch_resource` — read MCP Steroid's prompt resources (recipes, skill
  guides, etc.), addressed by `mcp-steroid://<path>` URIs
- `steroid_execute_code` — run a Kotlin suspend-body against the live IDE JVM,
  with full IntelliJ Platform API access
- `steroid_execute_feedback` — rate a prior execution (optional)

## Your task

Evaluate the **`mcp-steroid://ide/apply-patch`** recipe end-to-end. The recipe
is supposed to apply multiple literal-text substitutions across one or more
files *atomically* (all or nothing, one undoable command, PSI kept in sync).

Produce a candid, evidence-based review under ~700 words covering:

1. **Does the recipe actually work as advertised?** Pick one or more open
   projects via `steroid_list_projects`. Construct a realistic 3–5 hunk
   multi-site edit scenario (e.g. rename a logger key in two files, tweak
   constants) and run the recipe through `steroid_execute_code`. Report what
   worked, what failed, and what warnings / errors appeared.

2. **Are the defaults sensible?** Is the *pre-flight validation* (checking
   `old_string` uniqueness per file) too strict / too loose? Is the
   **descending-offset** per-file ordering correct? Does the success message
   tell the caller useful things?

3. **Ergonomics in practice.** Count lines of Kotlin the caller needed to
   write to invoke this. Count the bytes of tool input. Compare to what the
   caller would have written without the recipe (direct `VfsUtil.saveText` +
   `WriteCommandAction`). Estimate the real-world savings.

4. **Failure modes.** Simulate at least two failure paths:
   - `old_string` absent from the file,
   - `old_string` occurring multiple times.

   Does the recipe fail cleanly? Does it leave the project in a partial
   state? Is the error message actionable?

5. **Concrete improvement suggestions.** List 1–3 specific edits to
   `prompts/src/main/prompts/ide/apply-patch.md` that would make the next
   caller more successful. Each suggestion must name: (a) the exact change
   to the recipe, (b) the symptom in your run that motivates it, (c) the
   expected follow-on effect.

## Constraints

- Use `steroid_execute_code` for every edit — no native `Edit` tool use.
  You are stress-testing the IDE-side path.
- Do not modify files outside the project you chose — no side effects on the
  mcp-steroid plugin source, no edits to `prompts/src/main/prompts/`.
- If an edit fails, **revert immediately** via git or by writing a script to
  restore the original content.
- Keep the review ~700 words. A tight, evidence-backed review beats a long
  speculative one.

## Output format

Write your review to stdout, bracketed by these exact markers so a post-
processing step can extract it:

```
APPLY_PATCH_REVIEW_START
<your review>
APPLY_PATCH_REVIEW_END
```

Include your agent identity (Claude / Codex / Gemini) on the first line of
the review.
