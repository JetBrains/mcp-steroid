# iter-04 friction — Microshop-2 re-run with decision-tree-at-top

| metric              | iter-03 | iter-04 | delta |
|---------------------|---------|---------|-------|
| **mcp_share**       | 0.028   | 0.025   | -0.003 (noise) |
| exec_code_calls     | 2       | 1       | -1 |
| Read                | 32      | 14      | **-18 ✓** |
| Glob                | 0       | 5       | +5 (new) |
| Bash                | 22      | 7       | **-15 ✓** |
| Edit                | 8       | 8       | unchanged |
| total calls         | 71      | 40      | -31 ✓ |
| errors              | 4       | 0       | **-4 ✓** |
| applyPatch_called   | false   | false   | unchanged |
| tokens_total        | 2.7 M   | 1.9 M   | -0.8 M |

## Delta interpretation

Positive:
- Total calls dropped ~44%; the agent explored less before editing.
- Native `Bash find` replaced by native `Glob` (one prompt-level signal landed — our `coding-with-intellij.md` explicitly lists Glob as the right alternative when native is chosen).
- Zero errors (iter-03 had 4 — mostly Glob/find hallucinations).

Negative:
- `mcp_share` statistically unchanged at ~2-3%.
- The 8 repeat-edits still bypass `applyPatch`.
- Decision-tree-at-top was read but not weighted: Claude's trained Edit-first prior dominates.

## What Claude actually did at the multi-edit moment (iter-04)

Same pattern as iter-03:
  >> Edit (product-service/…/ServiceImpl.java)
  >> Edit (product-composite-service/…/ServiceImpl.java)
  >> Edit (recommendation-service/…/ServiceImpl.java)
  >> Edit (review-service/…/ServiceImpl.java)
  >> Edit (product-service/…/Application.java)
  >> Edit (product-composite-service/…/Application.java)
  >> Edit (recommendation-service/…/Application.java)
  >> Edit (review-service/…/Application.java)

Eight Edits across 8 files — 4×`@Autowired private Validator` + 4×`@ComponentScan`.
This is the exact shape applyPatch was designed for. The decision-tree's
first row said so. Agent ignored.

## iter-05 plan — STOP signal before the 2nd Edit

Prompt-only. No new DSL (negative-metric rule in force).

Add an imperative STOP-warning at the top of
`skill/execute-code-tool-description.md`, BEFORE the decision-tree:

  > **STOP before the 2nd native `Edit` in a task.** If you are about to
  > make similar edits across 2+ files (same pattern, different paths),
  > switch to `applyPatch { hunk(...) }` in a single `steroid_execute_code`
  > call — one undoable command, atomic pre-flight, PSI committed.

The bet: imperative "STOP" language triggers Claude's meta-evaluation
layer, overriding the default tool-picking path. If this doesn't work
either, iter-06 moves to structural options that don't violate the
DSL-methods-added negative metric (e.g. tool-description hosts an actual
code block at the top that demonstrates applyPatch adoption — the
template effect).

## DSL surface area

dsl_methods_added_vs_baseline = 1 (applyPatch). No change.

