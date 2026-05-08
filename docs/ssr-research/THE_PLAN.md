# THE_PLAN — IntelliJ Structural Search & Replace research

> **AI generated** — orchestration plan produced by Claude Code (Opus 4.7) on 2026-05-08.

## Goal
Produce a deep, source-grounded understanding of IntelliJ's Structural Search & Replace
(SSR) feature so we can ship one or more **MCP-Steroid skill prompt articles** under
`prompts/src/main/prompts/skill/structural-search/` that teach LLM agents to drive SSR
via `steroid_execute_code` for as many supported languages as possible.

**Deliverable target:** skill prompt articles only — no new MCP tools, no new API methods.
This matches the constraint stated in `CLAUDE.md` autoresearch notes: prompt-only fixes.

## Method — RLM 6-step

| Step        | Status | Notes |
|-------------|--------|-------|
| 1. ASSESS   | done   | Located SSR sources across 13+ subdirs in `~/Work/intellij`. JetBrains web docs reachable. Scope > 50K tokens, > 5 files → RLM activated. |
| 2. DECIDE   | done   | Strategy: **partition by dimension + language**, parallel sub-agents (6 in wave 1, 3 cross-validators in wave 2). |
| 3. DECOMPOSE| done   | 6 first-wave research tasks, see *Wave 1* below. Each fits 4–10K tokens of focused context. |
| 4. EXECUTE  | open   | Launch wave 1 in parallel via `run-agent.sh`. Tracked in `runs/`. |
| 5. SYNTHESIZE | open | Orchestrator (next turn) reads MESSAGE-BUS + run dirs, writes `SYNTHESIS.md`. |
| 6. VERIFY   | open   | Wave 2: 3-agent cross-validation review of `SYNTHESIS.md` (Claude/Codex/Gemini). |

## Workspace
- **Repo root** (target of edits): `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro`
- **PROJECT_ROOT** (orchestration): `<repo>/docs/ssr-research/`
- **RUNS_DIR**: `<repo>/docs/ssr-research/runs/`
- **MESSAGE_BUS**: `<repo>/docs/ssr-research/MESSAGE-BUS.md`
- **ISSUES_FILE**: `<repo>/docs/ssr-research/ISSUES.md`
- `runs/` and `*.log` are gitignored.

## SSR sources confirmed in `~/Work/intellij`

| Module | Path |
|---|---|
| Platform | `community/platform/structuralsearch/` |
| Java | `community/java/structuralsearch-java/` |
| Groovy | `community/plugins/groovy/structuralsearch-groovy/` |
| Kotlin (K1) | `community/plugins/kotlin/code-insight/structural-search-k1/` |
| Kotlin (K2) | `community/plugins/kotlin/code-insight/structural-search-k2/` (verify) |
| JS/TS | resolvable via `intellij.javascript.tests/structuralsearch` |
| PHP | `phpstorm/php/testData/structuralsearch` + impl in `intellij.php.impl/structuralsearch` |
| SQL | `dbe/sql/impl/src/structuralsearch/` |
| .NET | `dotnet/Psi.Features/src/Features/StructuralSearch/` |
| Flex | `contrib/flex/flex-tests/testData/structuralsearch` (impl tbd) |
| JSP | `plugins/jsp/testData/structuralsearch` |
| HTML/XML | platform-level (verify under platform/) |

Languages NOT confirmed yet (sub-agents will verify): **Python, Go**. Ruby, Rust likely absent.

## Wave 1 — first-wave sub-agents (6, run in parallel)

Each prompt is a self-contained `prompts/AN-*.md` file pointing to the role prompt + the
target task. Every agent writes FACT/PROGRESS/COMPLETE entries to `MESSAGE-BUS.md` using
the v3 protocol from `MESSAGE-BUS-protocol.md`.

| ID | Role | Agent CLI | Topic | Primary sources |
|----|------|-----------|-------|------------------|
| A1 | research | claude | JetBrains official docs + blog: SSR concepts, syntax, variables, constraints, profile selector, predefined templates, "Replace Structurally" inspection | jetbrains.com/help, IntelliJ blogs, plugins.jetbrains.com |
| A2 | research | codex  | Platform SSR API surface: `StructuralSearchUtil`, `Matcher`, `Replacer`, `MatchOptions`, `ReplaceOptions`, `Configuration`, `MatchResult`, `StructuralSearchProfile` extension point, predicates registry | `~/Work/intellij/community/platform/structuralsearch/source/` |
| A3 | research | gemini | Java SSR profile: `JavaStructuralSearchProfile`, predicates (`type`, `regex`, `count`, `ref`), built-in templates, replacement formatting | `~/Work/intellij/community/java/structuralsearch-java/source/` |
| A4 | research | claude | Kotlin SSR profile (K1 + K2): differences vs Java, available variables, replacement nuances | `~/Work/intellij/community/plugins/kotlin/code-insight/structural-search*/` |
| A5 | research | codex  | Multi-language survey: Groovy, JS/TS, PHP, Python(?), Go(?), SQL, .NET, HTML/XML, JSP, Flex — per-language: profile class, supported template syntax, gotchas, gaps | survey across all subdirs found in plan |
| A6 | research | gemini | Programmatic invocation recipe + use cases: how to run SSR from a `steroid_execute_code` Kotlin script (project, options, scope, replacement, formatter, undoable). Catalog of canonical refactoring use cases (migration, code-style, find-pattern). | platform tests + `Replacer.replace*` + JetBrains "Migrate Structurally" blogs |

**Agent picks rationale:** alternate Claude/Codex/Gemini per AGENTS.md "select agent type
at random unless statistics indicate" — this gets independent perspectives across
language survey + API + docs.

## Wave 2 — cross-validation (3 reviews)

After SYNTHESIS.md exists, fire 3 review agents with the same review prompt but
different CLIs. Per AGENTS.md: "All research tasks should include the multi-agent review
loop, repeated 3 times". Each writes a REVIEW entry on MESSAGE-BUS.md citing concrete
file paths and source links.

## Wave 3 — implementation (skill prompts)

Once review consensus reached, write skill articles:

- `prompts/src/main/prompts/skill/structural-search/overview.md` — top-level
- `prompts/src/main/prompts/skill/structural-search/syntax.md` — pattern syntax,
  variables, constraints
- `prompts/src/main/prompts/skill/structural-search/api-recipe.md` — Kotlin script
  recipe for `steroid_execute_code`
- One sub-article per language profile that ships with IntelliJ
  (`structural-search-java.md`, `structural-search-kotlin.md`,
  `structural-search-jsts.md`, `structural-search-php.md`, etc.) with realistic example
  patterns drawn from `testData/structuralsearch` corpora

The exact final structure will be ratified after wave 2.

## Operating rules

- Each FACT must include a source link (file path + line range, or URL).
- All AI-generated documents must say so in their first line.
- No code changes outside `docs/ssr-research/` (and eventually
  `prompts/src/main/prompts/skill/structural-search/`) until wave 3.
- If a sub-agent is unclear, it creates `*-QUESTIONS.md` and pings the orchestrator
  via MESSAGE-BUS rather than guessing.

## Status table

| Wave | State | Started | Notes |
|------|-------|---------|-------|
| 0 — setup       | done   | 2026-05-08 | THE_PLAN.md, MESSAGE-BUS.md, role prompts, run-agent.sh in place |
| 1 — research × 6 | open  | —          | A1..A6 prompts in `prompts/` |
| 2 — review × 3  | blocked on wave 1 | — | — |
| 3 — skill prompts | blocked on wave 2 | — | — |
