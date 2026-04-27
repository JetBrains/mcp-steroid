# iter-07 plan

## iter-06 signals

- Self-eval + full test suite both pass (BUILD SUCCESSFUL in 19:36 + 4:22).
- §5 "Limitations observed": threading-learning-curve item **dropped** — the
  iter-06 threading-rules card landed. Remaining limitations are all
  configuration/environment (index scope, test-fixture writes, VFS refresh) — not
  API ergonomics.
- §1 (a) = 4, §1 (b) = 2, verdicts = 24, report 26.7KB, 9 sections.
- §7 grew: "runInspectionsDirectly()" surfaced as item 5 in the unique-capability
  list — agent discovered it from our inspection recipes without us pointing.

## Residual gap for iter-07

§9 Practical usage rule still says:
> "Use built-ins when: ... making small targeted edits where you know the exact
> text to change."

This contradicts the "always use IntelliJ" policy: the native Edit tool leaves
the IDE's VFS and PSI stale. The compact VfsUtil.saveText recipe exists (from
iter-05), the tool description names it, but the agent still routes to Edit on
token grounds (1.5–2.5× cheaper per call, per §4).

The session-entry prompt `mcp-steroid-info.md` is quiet on this — it's where
the agent anchors its decision rule early in the session, before reading any
individual recipe. That's the highest-leverage location to fix the routing.

## iter-07 edit

Add a bold **"File edits: always through MCP Steroid, even when Edit looks
cheaper on tokens"** paragraph to `prompts/src/main/prompts/mcp-steroid-info.md`,
naming the four reasons that flip the cost calculation:

1. Native Edit writes straight to disk; IntelliJ's VFS and PSI don't see it.
2. Subsequent semantic operations (find-references, rename, hierarchy,
   inspections) return inconsistent results until something forces a refresh.
3. The compact `VfsUtil.saveText` recipe (named in the tool description) does
   read+write+refresh in one call, including every 1–3 line change.
4. The 1.5–2.5× per-call token overhead that the agent keeps quoting is
   cheaper than one debugging round-trip when PSI disagrees with disk.

Constraints: file is at prompts root and thus exempt from
MarkdownArticleContractTest title-length / description-length limits (verified
by reading the test); no other lint concerns.

## Expected iter-07 report change

- §9 decision rule should stop carving out an exception for small edits and
  read "Use MCP Steroid for all code changes (including 1-3 line edits);
  built-ins for non-code files, free-text search, shell, git."
- §1 (b) "Small text edits" bullet should either disappear or be reframed
  as "parity on tokens, MCP Steroid preferred for PSI consistency."
- §4 Token-efficiency small-edit row might stay (the ratio is factual), but
  the accompanying verdict should prefer MCP Steroid despite the ratio.
