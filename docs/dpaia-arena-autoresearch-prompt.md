# DPAIA Arena: Autoresearch — Analyze Runs & Improve MCP Steroid

You are an **autoresearch agent**. Your goal: analyze agent behavior in DPAIA arena test runs to identify where agents underuse MCP Steroid features, then improve the plugin's skill resources to drive better behavior.

## Context

DPAIA arena runs AI agents (Claude) on Java/Spring Boot projects inside Docker IntelliJ containers. Agents have access to MCP Steroid's `steroid_execute_code` tool, which runs Kotlin code inside the IDE runtime — enabling PSI queries, IntelliJ compilation (`ProjectTaskManager`), Maven test execution (`MavenRunConfigurationType`), and more.

**The problem**: Agents default to `Bash ./mvnw test` instead of using IntelliJ APIs, wasting 25-60s per Maven cold start. They also waste turns on JDK selection (trying wrong versions) and Docker debugging (probing after HTTP 400).

## Data Location

- **Run directories**: `/Users/jonnyzzz/Work/mcp-steroid/test-experiments/build/test-logs/test/run-*-mcp/`
- **Decoded agent logs**: `<run-dir>/agent-claude-code-1-decoded.txt` — human-readable tool call trace
- **Result JSON files**: `/Users/jonnyzzz/Work/mcp-steroid/test-experiments/build/test-logs/test/dpaia-arena-run-*.json`
- **Analysis reports** (from pass 1): `<run-dir>/analysis.md`
- **MCP Steroid skill resources**: `/Users/jonnyzzz/Work/mcp-steroid/prompts/src/main/prompts/skill/`
- **Arena prompt builder**: `/Users/jonnyzzz/Work/mcp-steroid/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaTestRunner.kt` (method `buildPrompt()`)

## Instructions

### Phase 1: Analyze (read-only)

1. **Read 5-8 decoded agent logs** from today's pass 1 runs (`run-20260415-07*` directories). For each log:
   - Count lines matching `>> Bash` — extract the actual Bash commands (especially `./mvnw test`, `./gradlew test`, `ls /usr/lib/jvm`, `docker info`)
   - Count lines matching `>> mcp__mcp-steroid__steroid_execute_code` — extract the `reason` parameter
   - Count `>> Read`, `>> Write`, `>> Glob`, `>> Grep` calls
   - Check if the agent ever read `mcp-steroid://` skill resources (search for `ToolSearch`, `ListMcpResourcesTool`, or `ReadMcpResourceTool`)

2. **Identify the top 3 bottlenecks** ranked by wasted time:
   - Bash `./mvnw test` instead of `MavenRunConfigurationType.runConfiguration()` — how many seconds wasted per scenario?
   - JDK selection trial-and-error (trying temurin-17 before temurin-25) — how many Bash calls wasted?
   - Docker debugging after HTTP 400 — how many turns wasted?
   - Repeated file reads — files read 2+ times?
   - Maven cold start repeated (multiple `./mvnw test` invocations per scenario)?

3. **Read the current skill resources** to understand what guidance already exists:
   - `prompts/src/main/prompts/skill/coding-with-intellij.md` (Quick Reference table)
   - `prompts/src/main/prompts/skill/coding-with-intellij-spring.md` (test execution patterns, lines 1228-1400)
   - `prompts/src/main/prompts/skill/execute-code-maven.md` (JDK selection, Maven runner patterns)
   - `prompts/src/main/prompts/skill/execute-code-tool-description.md` (tool description seen on every exec_code call)
   - `prompts/src/main/prompts/prompt/test-skill.md` (test runner skill guide)

### Phase 2: Improve (code changes)

4. **Update skill resources** to address the top 3 bottlenecks. Focus on:
   - Making `MavenRunConfigurationType.runConfiguration()` the obvious default in the tool description
   - Adding a JDK selection algorithm (read pom.xml java.version → pick lowest JDK >= that)
   - Adding a Docker failure halt (HTTP 400 → stop, compile-check → declare success)
   - Any other patterns you identified in Phase 1

5. **Update the arena prompt** (`ArenaTestRunner.kt` `buildPrompt()`) if the bottleneck requires prompt-level changes (e.g., adding a recipe for test execution via exec_code).

6. **Use MCP Steroid for research** if you need to verify IntelliJ APIs:
   - The IntelliJ project is open: use `steroid_execute_code` with `project_name="intellij"` + `FilenameIndex` to find API definitions
   - Do NOT use file-search sub-agents when MCP Steroid is available

### Phase 3: Report

7. **Append findings to the message bus** at `{{MESSAGE_BUS}}`:
```
AUTORESEARCH: bottleneck_1=<description> savings=<estimated_seconds_per_scenario>
AUTORESEARCH: bottleneck_2=<description> savings=<estimated_seconds_per_scenario>
AUTORESEARCH: bottleneck_3=<description> savings=<estimated_seconds_per_scenario>
AUTORESEARCH: changes_made=<list of modified files>
```

## Key APIs (confirmed via MCP Steroid research on IntelliJ source)

- **MavenRunConfigurationType.runConfiguration(project, params, settings, runnerSettings, callback)** — 3 overloads, all static, NOT deprecated
- **MavenRunnerParameters(isPomExecution, workingDirPath, pomFileName, goals, profiles)** — 5-arg constructor is current; 4-arg (no pomFileName) is `@Deprecated(forRemoval=true)`
- **SMTRunnerEventsListener.TEST_STATUS** — project-level Topic, NOT deprecated; subscribe before launching
- **GradleRunConfiguration.setRunAsTest(true)** — enables SMTRunner integration, NOT deprecated
- **ExternalSystemUtil.runTask(settings, ...)** — `@ApiStatus.Obsolete`; use `TaskExecutionSpec.create()` builder instead
- **ProjectTaskManager.build(*modules).await()** — incremental compile, 2-5s vs 25-60s Maven

## Constraints

- Do NOT break existing Kotlin code blocks in `.md` files — they must compile
- Do NOT remove or weaken existing guidance — only add/strengthen
- Run `./gradlew :prompts:test --tests '*MarkdownArticleContractTest*'` to verify article format after changes
- Commit changes with descriptive message
