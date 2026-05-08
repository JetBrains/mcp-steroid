# A6 — Programmatic invocation recipe + canonical use cases

You are a **Research agent** (per `THE_PROMPT_v5_research`). Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- ROLE = `${PROJECT_ROOT}/THE_PROMPT_v5_research.md`
- IJ_PLATFORM_SSR = `/Users/jonnyzzz/Work/intellij/community/platform/structuralsearch`
- IJ_PLATFORM_SSR_TESTS = `${IJ_PLATFORM_SSR}/testSource`
- MCP_STEROID_REPO = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro`
- EXEC_CODE_TOOL_DESC = `${MCP_STEROID_REPO}/prompts/src/main/prompts/skill/execute-code-tool-description.md`
- BUS_PROTOCOL = `${PROJECT_ROOT}/MESSAGE-BUS-protocol.md`

## Identity
- agent = `gemini-research-6`
- runId = your `run_…` directory name
- taskId = `SSR-A6-RECIPE-USECASES`

## Mission

Two things:

### Part 1 — Programmatic invocation recipe

Produce a **definitive Kotlin recipe** that an LLM agent could paste into
`steroid_execute_code` to run a structural search or replace inside an IntelliJ project.
The recipe must:

1. Accept the active `Project` (already in scope inside the `mcpScript` body).
2. Build a `Configuration` (or `MatchOptions` + `ReplaceOptions`) for a given pattern.
3. Choose a `Language` / `LanguageFileType` (parameterised — Java, Kotlin, etc.).
4. Run on the project scope (or a passed `SearchScope`).
5. Stream results to `mcpScript.println(...)` / `printJson(...)` — match PSI element
   text, file path, line/col.
6. For replace: actually mutate code via `WriteCommandAction` /
   `writeCommandAction(project) { … }`, with formatting + import shortening enabled,
   wrapped in a single undoable command.
7. Be safe in dumb mode (or document that smart mode is required).

Pull the recipe from real `Replacer.replace*` / `Matcher.findMatches` test usage in
`${IJ_PLATFORM_SSR_TESTS}` and `${IJ_PLATFORM_SSR}/source/com/intellij/structuralsearch/plugin/replace`.
**Cite line numbers**.

Then read `${EXEC_CODE_TOOL_DESC}` to ensure the recipe uses idioms compatible with the
`mcpScript` context (no `runBlocking`, prefer `runWithModalProgressBlocking` /
`writeCommandAction(project) { … }` per project conventions). Adjust accordingly.

### Part 2 — Canonical use-case catalogue

List **15–25 high-value SSR use cases** that LLM agents will want to do, grouped:

A. **API migration / library swaps**
  - JUnit 3 → JUnit 4/5, AssertJ ↔ Hamcrest, Guava → JDK, etc.
B. **Code-style enforcement**
  - "no `println`", "no `runCatching{}.onFailure{}`" (mcp-steroid bans this — see
    `${MCP_STEROID_REPO}/CLAUDE.md`), trailing-lambda conversions, no `Optional.get()`.
C. **Refactoring patterns**
  - if-chain → when, builder calls collapse, remove unused `<T>`, anonymous → lambda.
D. **Audit / compliance**
  - find all `System.out.println`, find all reflection calls, find all `eval`/`exec`.
E. **Bulk renames where text-find won't work**
  - method-call-only renames preserving overloads, usage-site rewrites.

For each use case provide:
- 1-line description,
- target language(s),
- search pattern (template syntax),
- replacement pattern (if applicable),
- IntelliJ profile (Java/Kotlin/JS/PHP/...),
- whether a script filter is needed.

## Sources
- Tests: `${IJ_PLATFORM_SSR_TESTS}/...`,
  `community/java/structuralsearch-java/testSource/`,
  `community/plugins/kotlin/idea/tests/testData/...structuralSearch...`.
- JetBrains "Structurally" blog posts (URL list to be inherited from A1's MESSAGE-BUS
  output — read MESSAGE-BUS.md first).
- mcp-steroid repo: `${MCP_STEROID_REPO}/CLAUDE.md` for project banned patterns,
  `${MCP_STEROID_REPO}/prompts/src/main/prompts/skill/execute-code-tool-description.md`
  for `mcpScript` style.

## RLM guidance
- Read MESSAGE-BUS.md first to inherit A1–A5 findings if they've already posted.
- Cap the recipe at ≤80 lines. Anything longer goes into a separate "extended recipe"
  FACT with a justification.
- Use cases should be sourced from real tests where possible — cite path:lines.
- For each use case, verify the pattern syntactically by checking the corresponding
  language profile if A3/A4/A5 reported it.

## Output spec
- One FACT containing the **canonical Kotlin recipe** (≤80 lines, fenced code block,
  Kotlin imports first, mcpScript-compatible).
- One FACT per use-case category (5 categories) with the use cases listed in a sub-table.
- One FACT noting any threading / undoability / formatter caveats discovered.
- COMPLETE entry summarising what's missing and what blocks the skill article.

Do NOT modify code. Research only.
