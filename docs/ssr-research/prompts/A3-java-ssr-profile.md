# A3 — Java SSR profile in IntelliJ source

You are a **Research agent** (per `THE_PROMPT_v5_research`). Your role is fixed.

## Absolute paths
- PROJECT_ROOT = `/Users/jonnyzzz/Work/mcp-steroid/.claude/worktrees/vcs-add-dialog-repro/docs/ssr-research`
- MESSAGE_BUS = `${PROJECT_ROOT}/MESSAGE-BUS.md`
- ISSUES_FILE = `${PROJECT_ROOT}/ISSUES.md`
- THE_PLAN = `${PROJECT_ROOT}/THE_PLAN.md`
- ROLE = `${PROJECT_ROOT}/THE_PROMPT_v5_research.md`
- IJ_JAVA_SSR = `/Users/jonnyzzz/Work/intellij/community/java/structuralsearch-java`
- IJ_PLATFORM_SSR_TESTS = `/Users/jonnyzzz/Work/intellij/community/platform/structuralsearch/testSource`

## Identity
- agent = `gemini-research-3`
- runId = your `run_…` directory name
- taskId = `SSR-A3-JAVA-PROFILE`

## Mission
Document the **Java SSR profile** — the most mature implementation, used as the reference
for every other language. The skill article for Java patterns will lean on this.

Cover:
1. `JavaStructuralSearchProfile` (FQN + path) — every overridden method with a one-line
   purpose. Highlight `getMeaningfulExpressionParents`, `isApplicableConstraint`,
   `isApplicableContext`, `isReplacementTypeApplicable`, etc.
2. The Java-specific pattern variables: how PSI element kinds (statement, expression,
   declaration, type, member) map to template-variable types. Which constraints are
   meaningful per kind (regex, expected-type, written-class-name, etc.).
3. **Built-in Java predicates** registered by this profile, including any custom ones
   (e.g. `JavaScriptedPredicate`, `JavaTypeMatcher`, `WithinHierarchy`).
4. **Predefined template configurations** that ship for Java — find their resource files
   (search XML / properties under `${IJ_JAVA_SSR}/resources` and similar). List 8–12 of
   the most useful and quote their pattern text.
5. **Replacement specifics**: short-class-name handling, import insertion, formatter
   pass, Java-only options (`shorten classes`, `reformat`, `static import`).
6. **Tests as recipes**: identify 3–5 tests that build a Java `Configuration`, run
   `Matcher`, and assert results. Quote one minimal end-to-end test for the FACT.

## Sources
Primary: `${IJ_JAVA_SSR}/source/`, `${IJ_JAVA_SSR}/resources/`, `${IJ_JAVA_SSR}/testSource/`.
Secondary: any cross-references into `community/platform/structuralsearch/`.

## RLM guidance
- Start with `find ${IJ_JAVA_SSR} -maxdepth 4 -type d` and `… -name "*.java"`.
- `rg "extends StructuralSearchProfile" ${IJ_JAVA_SSR}` to find the entry class.
- For predefined templates: `rg -l "predefined" ${IJ_JAVA_SSR}` and search resource files
  for `<searchConfiguration` / `<replaceConfiguration` blocks.
- Read any one full test file end-to-end (≤300 lines) to extract a recipe; otherwise
  scan helper method signatures only.

## Output spec
- One FACT per item above, source-linked with `path:lines`.
- One FACT containing a **list of 8–12 predefined Java SSR templates** with pattern
  text + replacement text + 1-line use case.
- One FACT giving a Kotlin recipe (≤25 lines) that uses the Java profile to find usages
  of `Lists.newArrayList()` and replace with `new ArrayList<>()` — derived from a real
  test, paths cited.
- COMPLETE entry summarising Java's strengths, gaps, and where it diverges from the
  platform contract.

Do NOT modify code anywhere. Research only.
