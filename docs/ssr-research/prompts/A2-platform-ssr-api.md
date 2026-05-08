# A2 — Platform SSR API surface in IntelliJ source

You are a **Research agent** (per `THE_PROMPT_v5_research`). Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- ISSUES_FILE = `${PROJECT_ROOT}/ISSUES.md`
- THE_PLAN = `${PROJECT_ROOT}/THE_PLAN.md`
- ROLE = `${PROJECT_ROOT}/THE_PROMPT_v5_research.md`
- BUS_PROTOCOL = `${PROJECT_ROOT}/MESSAGE-BUS-protocol.md`
- IJ_PLATFORM_SSR = `/Users/jonnyzzz/Work/intellij/community/platform/structuralsearch/source/com/intellij`

## Identity
- agent = `codex-research-2`
- runId = your `run_…` directory name
- taskId = `SSR-A2-PLATFORM-API`

## Mission
Map the platform-level SSR API — the pieces every language profile builds on, and the
ones a Kotlin script running inside `steroid_execute_code` would have to use to drive
search/replace from agent code.

Specifically, document:

1. **Entry points for programmatic invocation**:
   - `com.intellij.structuralsearch.plugin.ui.Configuration` (subclasses, builders)
   - `com.intellij.structuralsearch.MatchOptions` — fields, defaults, fileType / dialect,
     scope, search context, recursive, case sensitivity
   - `com.intellij.structuralsearch.plugin.replace.ReplaceOptions`
   - `com.intellij.structuralsearch.Matcher` — `findMatches`, `testFindMatches`,
     `processMatchesInElement`
   - `com.intellij.structuralsearch.plugin.replace.impl.Replacer` — `replace*` methods,
     `findReplacements`, formatting/optimization options
   - `com.intellij.structuralsearch.MatchResult` and `MatchResultImpl` — what info each
     match exposes (PSI element, variable bindings, source lines)
   - `com.intellij.structuralsearch.plugin.ui.UIUtil`, `ConfigurationManager` — saved
     templates, predefined inspection configurations
2. **Extension point: `StructuralSearchProfile`** — its responsibilities. List every
   abstract/overridable method (createPatternTree, isMyLanguage, getTypedVarString,
   isApplicableConstraint, supports ReplaceOptions, etc.). Note the `extensionPointName`
   used to register profiles.
3. **Predicates / constraints model**: `com.intellij.structuralsearch.MatchPredicate`,
   `com.intellij.structuralsearch.MatchVariableConstraint`, regex / type / count / ref /
   script / context / formal-arg / exact-match predicates. How they compose.
4. **Pattern compiler & matcher pipeline**: `PatternCompiler`, `MatchingHandler`,
   `GlobalMatchingVisitor`, search context flag enum. (Skim only; one paragraph.)
5. **PSI scope & file-type model**: how `MatchOptions.setFileType(LanguageFileType)`
   selects the profile, how dialects (`Language` instances inside a `LanguageFileType`)
   are represented in saved configurations.
6. **Test entry points**: `StructuralSearchTestCase` / `Replacer*Test` — what helpers
   they expose; how tests build a `Configuration`, run match, assert results. These are
   the closest to a public recipe for scripts.
7. **Threading / read-action requirements**: which API calls require read action / EDT,
   which can run in BGT, undoability of replacements, formatter pass.

## Sources
Primary: `${IJ_PLATFORM_SSR}/...` and `community/platform/structuralsearch/testSource/`.
Secondary (light): IntelliJ Platform SDK docs at
`https://plugins.jetbrains.com/docs/intellij/structural-search-and-replace.html` (if it
exists there) and any `@ApiStatus` annotations on the public types.

## RLM guidance
- This is a > 50K token codebase. Use `grep -r` first, never `cat` whole packages.
- Peek the directory tree with `find … -maxdepth 4 -type f -name "*.java" | head`.
- Use ripgrep when available: `rg --type java "class.*StructuralSearchProfile" $IJ_PLATFORM_SSR`.
- For each public API class you list, give: FQN, 1-line purpose, key fields/methods (≤8),
  usage example pulled from a test. Do NOT paste full file contents.
- Cap total context budget per file inspection: ~200 lines.

## Output spec
- One FACT per public class or extension point, with `files:` set to a `path:start-end`
  source link inside `~/Work/intellij`.
- One FACT enumerating MatchOptions / ReplaceOptions fields with one-line semantics.
- One FACT giving a **minimal Kotlin recipe** that:
  - constructs a `Configuration` for a Java pattern,
  - runs `Matcher` over a `Project` + scope,
  - prints each match's PSI text to a logger.
  Pull the recipe from a real test file; cite the test's `path:lines`.
- One FACT noting any threading constraints documented in source or javadoc.
- COMPLETE entry summarising the API map and listing 3–5 unanswered questions for A3/A4
  (language profiles).

Do NOT modify code anywhere. Research only.
