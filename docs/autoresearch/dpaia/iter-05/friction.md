# iter-05 friction — Microshop-2 re-run with "🛑 STOP before 2nd Edit" signal

| metric              | iter-03 | iter-04 | iter-05 |
|---------------------|---------|---------|---------|
| **mcp_share**       | 0.028   | 0.025   | **0.026** |
| exec_code_calls     | 2       | 1       | 1       |
| Read                | 32      | 14      | 17      |
| Glob                | 0       | 5       | 1       |
| Bash                | 22      | 7       | 7       |
| Edit                | 8       | 8       | **8**   |
| Write               | 0       | 3       | 3       |
| applyPatch_called   | false   | false   | **false** |
| errors              | 4       | 0       | 0       |

## Three prompt-iterations, zero movement

- iter-02 (anti-MCP-bias flip on the guide) — no change on ratio
- iter-04 (decision tree at top) — no change on edit-ratio, modest drop in
  total calls (Bash find → Glob for discovery)
- iter-05 (🛑 STOP imperative + inline applyPatch example) — no change

Claude sees "I need to edit 8 files, same pattern" and picks 8 × `Edit`
every time. Neither information framing nor imperative language overrides
this prior.

## What HAS worked (prompt-only wins)

Same three iterations, positive sub-signals:
- Bash `find` → native `Glob` (iter-03 22 Bash → iter-04/05 7 Bash)
- File-discovery Bash calls disappear entirely when Glob is listed as
  default
- Error rate dropped to zero since iter-04

So prompts CAN move agent behaviour — just not against Claude's trained-in
prior on the Edit tool specifically.

## iter-06 pivot — don't re-run Microshop-2; swap scenario

Re-running the same scenario a 4th time will give the 4th identical
result. Switch to a different NAVIGATE_MODIFY scenario to test whether
the problem is scenario-specific or agent-wide:

  DpaiaFeatureService125Test — curated as NAVIGATE_MODIFY + MCP HIGH
  DpaiaPetclinic27Test      — same curation

Both involve cross-file edits but different code shapes. If the 8-Edit
chain pattern reproduces: confirmed Claude-wide prior, need a structural
change (next iter explores e.g. steroid_apply_patch as a separate MCP
tool).

If the pattern doesn't reproduce: Microshop-2 has something specific
(four symmetric microservices, identical edits) that triggers the
chain-of-Edits habit.

## Also: prompt-level token-reduction idioms (edit-format-analysis.md)

Even if Claude adopted applyPatch, the 8-hunk naive form ships identical
bytes 4 times (same old/new across 4 Application.java files). The Kotlin
`listOf(paths).forEach { hunk(it, old, new) }` idiom cuts that 40%.
Plan lands in the recipe + tool description simultaneously with the
iter-06 scenario swap.

## DSL surface area

dsl_methods_added_vs_baseline = 1 (applyPatch) — unchanged.
