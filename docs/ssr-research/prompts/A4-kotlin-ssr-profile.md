# A4 — Kotlin SSR profile (K1 + K2)

You are a **Research agent** (per `THE_PROMPT_v5_research`). Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- ROLE = `${PROJECT_ROOT}/THE_PROMPT_v5_research.md`
- IJ_KOTLIN_ROOT = `/Users/jonnyzzz/Work/intellij/community/plugins/kotlin`
- IJ_KOTLIN_SSR = `${IJ_KOTLIN_ROOT}/code-insight` (search under here for `structural-search*` directories)

## Identity
- agent = `claude-research-4`
- runId = your `run_…` directory name
- taskId = `SSR-A4-KOTLIN-PROFILE`

## Mission
Document the **Kotlin SSR profile**, including the K1 vs K2 split (FE10 vs analysis-api),
since the Kotlin frontend has a documented dual-implementation. Skill article for
Kotlin SSR will quote this directly.

Cover:
1. Locate the Kotlin SSR module(s):
   - `find ${IJ_KOTLIN_ROOT} -maxdepth 6 -type d -iname "*structural*search*"`
   - Document each module's path, registered profile class FQN, and which Kotlin
     frontend it targets (K1/FE10 vs K2/Analysis-API).
2. Describe how the Kotlin profile differs from Java's: which PSI nodes are
   "meaningful" parents, how `KtCallExpression`, `KtLambdaExpression`,
   `KtFunctionLiteral`, `KtNamedFunction`, type-arguments, default-arguments are matched.
3. Variable kinds and constraints unique to Kotlin (e.g. nullability, receiver type,
   smart-cast types if expressed at all).
4. Kotlin-specific predicates / scripted filters and any "predefined templates" that
   ship.
5. Replacement specifics: import insertion, fully-qualified name shortening
   (`shortenReferences`), formatter, idiomatic-conversion knobs.
6. Tests as recipes: pick one runnable test that does Kotlin SSR end-to-end; cite path,
   quote ≤25 lines that build the configuration and assert results.
7. Known limitations (what Kotlin SSR cannot match — top-level decl visibility,
   contracts, expect/actual, etc.). Cite issue tracker links if found in source comments.

## Sources
Primary: `${IJ_KOTLIN_SSR}/structural-search-k1` and `…/structural-search-k2` (verify
exact names with `find`). Secondary: `community/plugins/kotlin/idea/tests/testData`
under any `structuralSearch*` subdir.

## RLM guidance
- Confirm `K1` and `K2` modules exist; if only one exists, log a FACT noting the absence
  of the other.
- `rg "class.*Kotlin.*StructuralSearchProfile" ${IJ_KOTLIN_ROOT}` to find profile classes.
- For K2: ensure references to `org.jetbrains.kotlin.analysis.api.KaSession` /
  `analyze {}` are correctly described (read-action context).
- ≤200 lines per file; quote sparingly.

## Output spec
- One FACT per item above, source-linked.
- One FACT comparing K1 vs K2 profile in a 2-column table (capability / behaviour).
- One FACT giving a Kotlin recipe that finds `runCatching{}.onFailure{}` and replaces it
  with `try/catch` — the canonical mcp-steroid example. Pattern text + replacement text +
  the Kotlin script (compatible with `steroid_execute_code` style: project-scoped).
- COMPLETE entry summarising readiness for the skill article and remaining unknowns.

Do NOT modify code anywhere. Research only.
