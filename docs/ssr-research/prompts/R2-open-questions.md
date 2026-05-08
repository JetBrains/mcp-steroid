# R2 — Resolve the 6 open questions in SYNTHESIS.md §9

You are a **Review agent**. Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS  = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- SYNTHESIS    = `${PROJECT_ROOT}/SYNTHESIS.md`
- IJ_ROOT      = `/Users/jonnyzzz/Work/intellij`

Read SYNTHESIS section 9 first.

## Identity
- agent = `codex-review-2`
- runId = your `run_…` directory name
- taskId = `SSR-R2-OPEN-QUESTIONS`

## Mission
Independently investigate and answer **each** of the 6 open questions in
`SYNTHESIS.md` §9. For each, write a FACT entry on MESSAGE-BUS with your answer plus
≥1 source citation (path:lines under `${IJ_ROOT}` or a JetBrains help URL).

The 6 questions:

1. **Pattern syntax `$x$` vs `'_x`** — verify by reading
   `${IJ_ROOT}/community/platform/structuralsearch/source/com/intellij/structuralsearch/plugin/ui/StringToConstraintsTransformer.java`
   end-to-end. What exactly does it transform? Is there a third documented form?
2. **Scala SSR availability** — search the IntelliJ checkout for any `Scala*StructuralSearch*`
   and check the standalone Scala plugin repo if its sources are nearby
   (`~/Work/intellij/scala/` or external). Where does the third-party Scala plugin's
   SSR profile live (if it exists)?
3. **Threading under `LocalSearchScope`** — does the "don't wrap `findMatches` in outer
   `readAction { }`" advice still apply when the scope is `LocalSearchScope` (a small
   set of `PsiElement`s) rather than `GlobalSearchScope`? Read `Matcher.findMatches`
   (`Matcher.java:172-213`) and trace the local-scope path.
4. **Predefined-templates programmatic discovery** — confirm
   `StructuralSearchUtil.getPredefinedTemplates(profile)` (or equivalent) returns the
   list a script can iterate. Cite the exact API + a usage example in tests.
5. **K1 vs K2 user-side equivalence** — pick ONE non-trivial method (e.g.
   `isApplicableConstraint`) and diff its implementation between
   `…/structural-search-k1/src/.../KotlinStructuralSearchProfile.kt` and
   `…/structural-search-k2/src/.../KotlinStructuralSearchProfile.kt`. Are they
   byte-for-byte the same logic, or only "equivalent at the user surface"?
6. **Custom inspection persistence schema** — read
   `${IJ_ROOT}/community/platform/structuralsearch/source/com/intellij/structuralsearch/plugin/ui/Configuration.java`
   `writeExternal/readExternal` and a checked-in example
   `.idea/inspectionProfiles/*.xml` (search anywhere under `${IJ_ROOT}`). Document the
   on-disk schema so a script could generate one without the dialog.

## RLM guidance
- One question at a time. Answer Q1 before reading Q2's source.
- Cap each answer at ~12 lines of FACT body; longer goes into a follow-up FACT.
- For Q5, an approximate diff (function-signature + 5 highlighted differences) is enough
  — don't paste both files in full.

## Output spec
- 6 `FACT` entries (one per question), each with `taskId: SSR-R2-OPEN-QUESTIONS` and at
  least one source citation in `files:`.
- One `REVIEW` entry summarising whether SYNTHESIS.md needs corrections based on what
  you found.
- One `COMPLETE` entry with: which questions resolved cleanly, which still need more
  work, and any new questions you raised.

Do NOT modify SYNTHESIS.md or IntelliJ source. Append-only on MESSAGE-BUS.md.
