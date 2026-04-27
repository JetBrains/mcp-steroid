# iter-03 friction — DpaiaMicroshop2Test.claude with mcp (NAVIGATE_MODIFY, MCP-HIGH)

Fresh run dir: `run-20260422-232102-dpaia__spring__boot__microshop-2-mcp`.
Edit-heavy scenario — deliberately picked to give `applyPatch` a shot.

## Metrics vs prior iterations

| metric              | iter-00 avg | iter-02 | iter-03 |
|--------------------|-------------|---------|---------|
| **mcp_share**       | 0.065       | 0.091   | **0.028 ↓↓** |
| exec_code_calls    | 2.1         | 1       | 2 |
| total calls        | —           | 11      | 71 |
| Read               | —           | 2       | 32 |
| Bash               | —           | 3       | 22 |
| Edit               | 8.7         | 2       | **8** |
| applyPatch_called  | false       | false   | **false** |
| fetch_resource_calls| 0          | 0       | 0 |
| errors             | 1.1         | 0       | 4 |
| tokens_total       | 4.2 M       | 726 k   | 2.7 M |

mcp_share **regressed** below baseline. On a scenario explicitly curated
as "NAVIGATE_MODIFY / MCP benefit HIGH" the agent used the IDE for 2 of
71 tool calls.

## The tell

Agent's own words before the 8 native Edits:

> "Now I'll make all the edits — adding `@ComponentScan("shop")` to all 4 main
> classes and adding validation to all 4 service implementations."

Then 8 `Edit` calls, four on `*Application.java`, four on `*ServiceImpl.java`.

That sentence **is** the `applyPatch { hunk(...); hunk(...); ... }` pitch.
The agent knew it was doing a multi-site repeat-edit. It still picked native
Edit because the tool-description's applyPatch pointer was a single line
("Two or more edits in one or more files: use the applyPatch { hunk(...) }
DSL …") buried deep after compile-check, threading rules, Maven recipe.

## Other friction

- **Bash `find` for file discovery — 7 calls.** Agent ran `find … -name "*.java"`
  seven times instead of `FilenameIndex.getAllFilesByExt(project, "java", projectScope())`.
  Same pattern: FilenameIndex is one table row, `find` is built-in reflex.
- **22 Bash calls total.** The agent lived in the shell for both discovery
  and build/test orchestration.
- **Compile check aborted** at the end: "Build errors: false, aborted: true".
  The ProjectTaskManager.buildAllModules() call got cancelled — likely the
  harness's 120 s exec_code timeout before the fresh project finished indexing.

## iter-04 prompt change plan (no new DSL methods — negative metric in force)

Promote the multi-site-edit decision to the TOP of
`skill/execute-code-tool-description.md`. The agent reads top-down; the
first pattern it sees dominates. Restructure so line 1-10 of the
description is a **decision tree**:

  - multi-site edit (same literal across files, or >1 hunk) → `applyPatch`
  - single-site edit → compact `VfsUtil.saveText` recipe
  - find files / classes / symbols → `FilenameIndex` / `PsiShortNamesCache`
  - run tests → IDE runner (link to recipe)
  - everything else → fall back to native tools

Before burying threading rules, Maven recipe, import table. Budget: the
decision tree adds ~10 lines but EVERY exec_code call pays that token
cost — if it moves mcp_share from 0.03 to 0.30, each call buys more
MCP usage with a fixed tax.

No new DSL methods this iteration (dsl_methods_added_vs_baseline stays at 1).
