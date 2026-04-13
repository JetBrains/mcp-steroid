# CLAUDE.md, AGENTS.md

Guidance for Claude Code when working with this repository. **Instructions here override default behavior.**

Never include AI as co-author or mention AI in commit messages.

## MUST DO

- Use IntelliJ MCP for everything where you can
- Never ignore warnings or errors — fix them properly
- No test-only branches (`isUnitTestMode`) — use correct IntelliJ actions (`writeIntentReadAction`, `writeCommandAction`)
- Tests must show reality — a failing test is better than a fake passing test. **Never remove, disable, or weaken a failing test**; fix the underlying issue instead
- No `@Suppress("DEPRECATION")` — find the non-deprecated replacement
- Prefer JSON libraries for JSON parsing/manipulation; only static final JSON constants may be hand-written as raw JSON strings
- **BANNED:** `runCatching{}.onFailure{}` — use `try { } catch (e: Exception) { }` instead. Other `runCatching` uses (`.getOrNull()`, `.getOrDefault()`) are fine
- **BANNED:** Production code and tests must never reference or depend on `run-agent.sh` or `docs/run-agent.sh`. These scripts are tools for humans and AI agents to use manually during development (peer reviews, research, etc.), not for programmatic execution from project code. Code should implement agent integrations directly using CLI flags and arguments. `run-agent.sh` must **never** be installed inside Docker containers (no `COPY run-agent.sh` or `RUN chmod +x ... run-agent.sh` in Dockerfiles)
- **BANNED:** Gradle build files must never reach into another subproject's `build/` directory directly. Use Gradle dependency configurations to share artifacts between subprojects. Fail fast with a clear `require()`/`error()` — no silent fallbacks that hide misconfiguration
- **BANNED:** Do NOT use `append("\n")` or `append("...\n")` tricks to work around the `NoLargeInlineStringsTest` lint rule. When a `buildString { }` block exceeds the consecutive-`appendLine` limit, the correct fix is to move the content to `src/main/prompts/` resource files and reference them via article URIs — not to sprinkle `append("\n")` calls to artificially break the line count.
- Log new ideas/tasks in TODO* files (TODO.md, TODO-*.md)
- **No infrastructure workarounds**: when tests fail due to infrastructure limitations (missing Docker socket, missing CLI, wrong JDK), fix the infrastructure — mount Docker socket, install Docker CLI, configure JDK. Do NOT add code that detects the limitation and silently skips tests or changes behavior. A failing test that reveals a real problem is better than a passing test that hides it.
- **Prefer Kotlin Coroutines native APIs over Java threading primitives**: use `CompletableDeferred<T>` + `withTimeout(duration) { deferred.await() }` instead of `CountDownLatch`. Use `Channel<T>` for streaming. Use `suspendCancellableCoroutine` for one-shot callbacks. `CountDownLatch` / `Semaphore` / `Object.wait()` are banned in new coroutine code — they block threads and are not cancellation-aware.
- **NEVER run `test-integration` or `test-experiments` tests in parallel** — each test starts a full Docker IntelliJ container. Running two or more concurrently exhausts RAM/CPU (IDE windows never appear, containers OOM). Always run one `./gradlew :test-integration:test --tests '...'` (or `:test-experiments:test --tests '...'`) at a time. Wait for it to finish completely before starting the next. This applies to all Docker-based tests: DPAIA arena, debugger demo, playground, CLI agent tests.

## Workflow

### IntelliJ API

Use IntelliJ services instead of `object`/singletons. Prefer:
```
inline val serviceX: ServiceX get() = service()
inline val Project.serviceY: ServiceY get() = service()
```

### Test-First Approach

All bugs: add failing test first, then fix. Integration tests preferred. Never fake tests.

### Feature Checklist

1. Read requirements, ask if ambiguous
2. Write tests, implement feature
3. Run Gradle build/test via MCP (not shell)
4. Deploy: `./gradlew deployPlugin`
5. Test with IntelliJ MCP, run ai-tests/
6. Update docs and TODO* entries, commit

### TODO-Driven Development

Track all tasks in TODO* files. Review them before starting work. Work items one-by-one: add failing test, implement, run Gradle build via MCP, mark done. Create new entries for discovered work.

### Final Verification

1. Gradle `build` via MCP — compiles
2. Gradle `test` via MCP — tests pass
3. ai-tests/ scenarios work
4. Use `steroid_execute_code` to check for warnings/errors before completing work

## IntelliJ and Coroutines

- `runBlockingCancellable` — BGT, blocks thread, cancellation-aware
- `runWithModalProgressBlocking` — EDT, shows modal progress
- `RunSuspend` — low-level Object.wait()/notifyAll()

## Project Overview

MCP Steroid — MCP server plugin exposing IDE APIs to LLM agents via Kotlin code execution.

- **GitHub (public)**: https://github.com/jonnyzzz/mcp-steroid — public issue tracker for epics and public bugs
- **GitHub (internal)**: https://github.com/jonnyzzz/mcp-steroid — internal repo for internal tasks
- **Docs**: [README.md](../../README.md), [AGENT-STEROID-GUIDE.md](../guides/AGENT-STEROID-GUIDE.md)

## Build

Run Gradle tasks from the IDE via MCP, not shell. Key tasks: `build`, `test`, `buildPlugin`, `runIde`, `verifyPlugin`, `deployPlugin`.

Gradle run config example:
```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType

val runManager = RunManager.getInstance(project)
val factory = GradleExternalTaskConfigurationType.getInstance().configurationFactories.single()
val runConfig = factory.createTemplateConfiguration(project) as ExternalSystemRunConfiguration
runConfig.name = "Gradle test (MCP)"
runConfig.settings.externalProjectPath = project.basePath
runConfig.settings.taskNames = listOf("test")
// runConfig.settings.scriptParameters = "--tests \"*ExecutionManagerTest*\""
val settings = runManager.createConfiguration(runConfig, factory)
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```

### Build Notes

- IDE cached under `.intellijPlatform/ides/IU-2025.3`
- Tests use `localPlugin(...)` for Kotlin/Java plugins to align with IDE classpath

## Technology Stack

Gradle 8.11.1 / Kotlin 2.2.21 / Java 21 / IntelliJ Platform 2025.3+ / Ktor 3.1.0 (CIO+SSE) / kotlinx.serialization

## Architecture

IntelliJ Platform plugin with standalone MCP server (Kotlin MCP SDK + Ktor):

**Core flow**: `SteroidsMcpServer.kt` → `ExecuteCodeToolHandler.kt` → `ExecutionManager.kt` → `CodeEvalManager.kt` (compile) → `ScriptExecutor.kt` (run with timeout)

**Key decisions**: Standalone MCP server (no built-in MCP dependency). Synchronous request-response. Coroutines over blocking. Two-phase execution (compile then run). Fast failure on errors. Disposable lifecycle for cleanup.

### Source Structure

```
src/main/kotlin/com/jonnyzzz/mcpSteroid/
├── server/          # MCP server, tool handlers, skills
├── mcp/             # Core MCP protocol, tool registry
├── execution/       # ExecutionManager, CodeEvalManager, ScriptExecutor, McpScriptContext
├── review/          # Human review workflow
├── storage/         # Append-only file storage
├── vision/          # Screenshot, input dispatch
├── demo/            # Demo mode overlay
├── ocr/             # External OCR process
├── koltinc/         # External kotlinc process
└── updates/         # Update checker
```

## Testing

```kotlin
fun testExample(): Unit = timeoutRunBlocking(30.seconds) { /* coroutine test code */ }
```

Test helper:
```kotlin
private fun testExecParams(code: String, timeout: Int = 30) = ExecCodeParams(
    taskId = "test-task", code = code, reason = "test", timeout = timeout,
    rawParams = buildJsonObject { }
)
```

### Test Task Isolation Rules

- **`./gradlew :ij-plugin:test`** — runs only unit/in-process tests. Docker CLI tests are excluded by default.
  - Docker CLI tests (require Docker + API keys): run explicitly with `--tests '*CliClaudeIntegrationTest*'` etc.
  - `./gradlew test` at root does NOT run `test-integration:test` or `test-experiments:test` (both guarded by `onlyIf`).
- **`./gradlew :test-integration:test`** — MUST be invoked explicitly with `:test-integration:` prefix.
  Holds the **stable** Docker-based smoke tests (release matrix: DialogKiller, IntelliJContainer, Infrastructure,
  WhatYouSee, PyCharm…, EapSmoke). Shared infrastructure (IdeContainer, drivers, MCP client) lives here in
  `src/main/kotlin` and is consumed as a library by `:test-experiments`.
  - Running `./gradlew test` at root silently skips `test-integration:test` (`onlyIf` returns false).
  - Direct invocation `./gradlew :test-integration:test --tests '...'` still works.
- **`./gradlew :test-experiments:test`** — MUST be invoked explicitly with `:test-experiments:` prefix.
  Holds the **experimental / long-running / less stable** tests: `DebuggerDemoTest`, `RiderDebuggerTest`,
  `RiderPlaygroundTest`, `Plugin{Build,Runtime}CompatibilityTest`, the DPAIA arena suite, all prompt-quality
  comparisons, `XcvbConsoleTest`, etc.
  - Same `onlyIf` guard — never runs as part of root `./gradlew test`.
  - Depends on `:test-integration` for the shared infrastructure.

### Key Test Files

- **McpServerIntegrationTest** — MCP protocol handshake, tool flows, session management (in-process, no Docker)
- **CliClaudeIntegrationTest** / **CliCodexIntegrationTest** / **CliGeminiIntegrationTest** — Docker-isolated CLI tests (need API keys); excluded from default `ij-plugin:test`
- **ScriptExecutorTest** — Fast failure semantics, compilation/runtime errors
- **ExecutionManagerTest** — Execution with progress reporting

### Docker Integration Tests (test-integration/ and test-experiments/)

Run AI agents in Docker with IntelliJ IDEA + MCP Steroid. Prerequisites: Docker, API keys, plugin ZIP built.

Stable smoke matrix lives in `:test-integration`. Experimental / long-running tests live in `:test-experiments`
(see split rules above).

```bash
./gradlew :test-experiments:test --tests '*DebuggerDemoTest.claude*'
./gradlew :test-experiments:test --tests '*DpaiaArenaTest*' -Darena.test.instanceId=dpaia__empty__maven__springboot3-3
```

Key classes: `IdeContainer`, `ConsoleDriver`, `XcvbDriver`, `AiAgentDriver`, `ConsolePumpingContainerDriver`

**Screen layout**: IDE left 2/3, console right 1/3, managed by `LayoutManager`.

#### Agent CLI Flags

Each agent is invoked with specific flags to produce NDJSON output piped through `agent-output-filter`:

| Agent | Output flag | Auto-approve flag | Verbose |
|-------|------------|-------------------|---------|
| **Claude** | `--output-format stream-json` | (uses `--permission-mode bypassPermissions`) | **`--verbose`** |
| **Codex** | `--json` | `--dangerously-bypass-approvals-and-sandbox` | n/a |
| **Gemini** | `--output-format stream-json` | `--approval-mode yolo` | n/a |

The `--verbose` flag is **required** for Claude — without it, tool call details are not emitted.

#### Agent Output Filters

**Design principle**: The goal of output filters is to **render NDJSON as human-readable text**, NOT to filter or suppress events. Every event must produce some output. The only legitimate exceptions are structural lifecycle events where content arrives in the corresponding completed event:
- `thread.started`, `turn.started` — no content, silenced
- `item.started` for `agent_message` — content arrives in `item.completed`

Unknown/future event types always fall through as raw JSON so no information is lost.

**`toolDetail()` in `FilterUtils.kt`**: Extracts human-readable summary for `>> tool_name (detail)` lines. Handles: `steroid_execute_code` (reason), `steroid_execute_feedback` (rating + explanation first line), `steroid_open_project` (path), `Read`/`Glob`/`Write` (path), `Grep` (pattern). Generic fallback: first short (<80 chars, no newlines) primitive value from the input JSON object.

**Agent log files**: `ConsoleAwareAgentSession` writes two log files per `runPrompt()` call into `logDir` (always = `runDir`):
- `agent-{name}-{N}-raw.ndjson` — raw NDJSON lines from STDOUT (unfiltered)
- `agent-{name}-{N}-decoded.txt` — human-readable decoded output + stderr/info lines

##### ClaudeOutputFilter

Claude Code 2.1.x changed from streaming events (`content_block_delta`, `tool_use`, `tool_result`) to structured events (`assistant`/`user` with full `message.content` arrays). The `result.result` field is empty in new format; actual output is in `assistant.message.content[].type=text` blocks.

`ClaudeOutputFilter` handles **both formats** simultaneously for backward/forward compatibility.

MCP tool names in new format are fully qualified: `mcp__mcp-steroid__steroid_execute_code`. `toolDetail()` strips the prefix with `substringAfterLast("__")`.

##### CodexOutputFilter

Codex `--json` output uses different field names than Claude. Key differences:

**`mcp_tool_call` items** (Codex actual format):
```json
{
  "type": "item.started",
  "item": {
    "id": "item_5",
    "type": "mcp_tool_call",
    "server": "mcp-steroid",
    "tool": "steroid_execute_code",      ← "tool", NOT "name"
    "arguments": { "reason": "...", ... } ← "arguments", NOT "input"
  }
}
```
Completed `mcp_tool_call` result is a structured object, NOT a primitive:
```json
"result": {
  "content": [{"type": "text", "text": "..."}, ...],
  "structured_content": null
}
```

**`reasoning` items** (Codex emits thinking steps):
```json
{"type": "item.completed", "item": {"type": "reasoning", "text": "Planning to..."}}
```
Rendered as `[thinking] first-non-blank-line`.

`resolveToolName()` checks `item["name"]` → `item["function"]["name"]` → `item["tool"]` (covers all variants).
`resolveInputObject()` checks `item["input"]` → `item["arguments"]` → `item["function"]["arguments"]`.

#### Integration Test Strategy

- **ONLY 1 test-integration / test-experiments test at a time** — each test spins up a full Docker IntelliJ container. Running two simultaneously exhausts memory and CPU: the second IDE window never appears, containers get OOM-killed (exit 137), and both tests fail. Always wait for the current test to finish before starting another.
- **Infrastructure failures are transient** — "Failed waiting for IntelliJ IDEA window" or "Failed waiting for Project import" are usually environment issues; retry individually with a cooldown
- One-at-a-time run: `./gradlew :test-experiments:test --tests '*DebuggerDemoTest.claude*'`

#### Forcing Agents to Output Required Data

Use explicit output markers in prompts to ensure agents include required information in final text (not just internal reasoning):

```kotlin
appendLine("OUTPUT_MARKER: <required content description>")
// e.g.
appendLine("BUG_LINE: <the exact buggy line of code>")
appendLine("FILE_PATHS: <at least one file path ending in .java or .kt that you found>")
```

Agents (especially Gemini) may find the right answer internally but not include it in the final text output — markers force explicit reporting.

#### Known Agent Quirks

- **Gemini exit 137** (SIGKILL): Treat as success when NDJSON confirms success — `DockerGeminiSession` handles this automatically
- **Codex exit 137** (SIGKILL): Same pattern as Gemini — exit code 137 can be treated as success if the required output markers were emitted before the kill. Tests already check for markers before checking exit code.
- **Codex MCP prefix**: Tool names are NOT MCP-prefixed in Codex output (no `mcp__` prefix). Codex uses bare tool names like `steroid_execute_code`.
- **Codex `mcp_tool_call` field names**: Different from `tool_call` — uses `"tool"` (not `"name"`), `"arguments"` (not `"input"`), result in `result.content[]` array (not a primitive). See `resolveToolName()` / `resolveInputObject()` in `CodexOutputFilter`.
- **Claude new NDJSON**: Since Claude Code 2.1.x, use structured `assistant`/`user` events; filter handles both old and new formats

### Test naming

- **NO `@ParameterizedTest`** — create explicit `@Test` methods with descriptive names instead (e.g., `` `describeMcp claude`() ``, `` `describeMcp codex`() ``)
- For truly dynamic test cases (runtime-discovered), use `@TestFactory` with `DynamicTest.dynamicTest("descriptive-name") { ... }`
- This ensures IDE test runner, `./gradlew --tests`, and CI reports work optimally

## Build Troubleshooting

### Test Suite Runtimes

- **`./gradlew :ij-plugin:test`** (full suite, clean): ~13–14 minutes
- **`./gradlew :prompts:test --tests '*KtBlock*'`** (KtBlocks compilation only): ~7 minutes (note: KtBlocks tests are in `:prompts:test`, not `:ij-plugin:test`)
- **Suspiciously fast results** (e.g., 500 tests in 14 seconds): this is **stale Gradle test cache** — results from a previous run are being replayed. Run with `--rerun-tasks` or delete `ij-plugin/build/test-results/` to force a fresh run.

### IntelliJ Index Corruption

**Symptom**: Many tests fail with:
```
TestLoggerAssertionError: Index data initialization failed
  → IllegalStateException: Index data initialization failed
  → PersistentEnumerator storage corrupted
    /Users/.../ij-plugin/build/idea-sandbox/IU-2025.3/system-test/index/stubs/Stubs.storage
```

The full coroutine stacktrace shows the failure inside `IndexDataInitializer$Companion$submitGenesisTask$2.invokeSuspend` running on a `CoroutineScheduler$Worker`. This is an IntelliJ infrastructure issue — the persistent index/stub storage on disk got corrupted (usually a truncated write from a previous abrupt JVM kill).

**Fix**: Delete the corrupted index storage and let IntelliJ rebuild it:
```bash
rm -rf ij-plugin/build/idea-sandbox/IU-2025.3/system-test/
# Or more broadly:
rm -rf ij-plugin/build/idea-sandbox/
```

After deletion, the next test run will rebuild indexes from scratch (adds ~30–60s to startup).

**Prevention**: Avoid killing the Gradle test JVM with SIGKILL (`kill -9`) mid-run — prefer SIGTERM so IntelliJ can flush its index files cleanly.

**Critical**: NEVER run two `./gradlew :ij-plugin:test` tasks concurrently. Both JVMs write to the same `ij-plugin/build/idea-sandbox/` directory and will corrupt each other's `Stubs.storage`. Always wait for a test run to finish before starting another. Also, **never delete `idea-sandbox/` while a test JVM is running** — file handle corruption causes the same result.

### Live JVM Thread/Coroutine Dumps

When a test run hangs or behaves unexpectedly, **do NOT stop the task** — collect a thread dump first to understand what's happening:

```bash
# Find the test JVM PID (GradleWorkerMain is the test worker process)
jps -l | grep GradleWorkerMain
# OR
pgrep -f GradleWorkerMain

# Collect full thread dump (includes coroutine dump via DebugProbes)
jcmd <PID> Thread.print > /tmp/thread-dump.txt

# Show coroutines specifically (if kotlinx-coroutines-debug is on classpath)
jcmd <PID> Thread.print -l > /tmp/thread-dump-with-locks.txt
```

The dump will show all threads + their stack traces, allowing you to diagnose:
- Deadlocked threads (look for `BLOCKED` state)
- Stuck coroutines (look for `DefaultDispatcher-worker-*` threads waiting)
- EDT violations (look for `AWT-EventQueue-0` with deep blocking calls)
- Which test method is currently executing

### Cleaning Build Artifacts

```bash
# Clean only test outputs (forces test re-run, preserves downloaded IDEs)
rm -rf ij-plugin/build/test-results/ ij-plugin/build/reports/

# Clean corrupted IntelliJ indexes (see above)
rm -rf ij-plugin/build/idea-sandbox/

# Full clean (re-downloads nothing, IDE cached in .intellijPlatform/)
./gradlew :ij-plugin:clean
```

### Common Test Failure Patterns

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `PersistentEnumerator storage corrupted` | Corrupted index files | `rm -rf ij-plugin/build/idea-sandbox/` |
| 500+ failures in <30s | Stale Gradle test cache | `./gradlew :ij-plugin:test --rerun-tasks` |
| `KtCompilationTest` fails with `-Werror` | Deprecated API used in `.kt` section | Replace deprecated call (see MEMORY.md) |
| `KtBlocksCompilationTest` fails | Non-compilable code in ` ```kotlin ``` ` fence | Change fence to ` ```text ``` ` in `.md` |
| `MarkdownArticleContractTest` fails | Title >80 chars, desc >200 chars, or bare code outside fences | Fix the article header/body |

## Key Types

- **ExecCodeParams**: `taskId`, `code`, `reason`, `timeout`, `rawParams`
- **ToolCallResult**: `content` (List<ContentItem>), `isError`. Use `ToolCallResult.builder()`
- **McpScriptContext**: `project`, `params`, `disposable`, `println()`, `printJson()`, `progress()`, `waitForSmartMode()`

## Configuration

Registry keys: `mcp.steroid.server.port`, `.host`, `.review.mode` (ALWAYS/TRUSTED/NEVER), `.review.timeout`, `.execution.timeout`, `.dialog.killer.enabled`, `.demo.enabled`, `.storage.path`, `.kotlinc.parameters`, `.kotlinc.home`

### Kotlinc Version-Mismatch Workaround

When IDE bundles newer Kotlin than the plugin's compiler: set `mcp.steroid.kotlinc.home` to `<IDE>/plugins/Kotlin/kotlinc` via Registry.

### Script Preprocessing (CodeButcher)

`CodeButcher.wrapWithImports()`: extracts user imports, adds defaults, merges, wraps body into suspend method.

## IntelliJ Platform Coding Principles

### Threading

- Never block EDT. Use `Dispatchers.EDT` for UI, `Dispatchers.IO`/`Default` for background
- PSI/VFS access needs read actions; modifications need write actions. Use `readAction { }` / `writeAction { }`
- Dumb mode: use `DumbService.isDumb(project)`, `waitForSmartMode()` handles this for scripts

### Coroutines & Services

```kotlin
@Service(Service.Level.PROJECT)
class MyService(private val project: Project, coroutineScope: CoroutineScope)
```

Use `childScope()` for cleanup, `job.cancelOnDispose(disposable)` for cancellation, `Disposer.register(parent, child)` for lifecycle.

Get services: `project.service<MyService>()` or `service<AppService>()`

### Error Handling

- Never catch `ProcessCanceledException` — rethrow it
- Use `Logger.getInstance(MyClass::class.java)` for logging

## Multi-Version Compatibility Strategy

**Strategy: build against 253, run the same binary on 261 and 262.**

The plugin is always compiled against IntelliJ 2025.3 (253). The same binary is then validated
against newer IDE versions via two independent test suites:

| Test | What it validates | How |
|------|------------------|-----|
| `PluginBuildCompatibilityTest` | Plugin **compiles** against newer SDKs | Docker + `./gradlew buildPlugin` with patched versions |
| `PluginVerificationTest` | Built binary is **API-compatible** with newer IDEs | Docker + Plugin Verifier (`verifyPlugin`) |
| `PluginRuntimeCompatibilityTest` | Plugin **runs** correctly in newer IDEs | Docker IDE container + MCP tool calls |

The runtime test specifically exercises `list_windows` which triggers mcp-steroid#18
(ClassCastException on the `kotlin.Pair` vs `c.i.o.u.Pair` type change in 262).

### Build Compatibility Tests

Docker-based tests that validate the plugin compiles against multiple IntelliJ Platform versions.
Located in `test-experiments/.../PluginBuildCompatibilityTest.kt`.

```bash
# Run all build compat tests
./gradlew :test-experiments:test --tests '*PluginBuildCompatibilityTest*'

# Run specific version
./gradlew :test-experiments:test --tests '*PluginBuildCompatibilityTest.build plugin with IntelliJ 2025_3*'
```

### How It Works

Each test mounts the project read-only into a Docker container (`docker/build` image: Debian + JDK 21 + git),
copies to a build dir, cleans with `git clean -fdx`, applies version patches via `sed`, then runs
`./gradlew :ij-plugin:buildPlugin`. Persistent caches under `build/build-compat/` (Gradle home,
`.intellijPlatform`) make re-runs fast.

### Version Patches

| Target IDE | Patches needed |
|------------|---------------|
| 2025.3 | None (project default) |
| 2026.1 | Kotlin → 2.4.0-Beta1 (IDE bundles metadata 2.4.0) |
| 262-SNAPSHOT | Kotlin → 2.4.0-Beta1 + plugin 2.14.0 + `useInstaller = false` + `nightly()` repo |

### IntelliJ Platform Gradle Plugin — Snapshot Resolution

The plugin (v2.11.0 in project, v2.14.0 latest) resolves IDEs in two modes:
- **Installer mode** (`useInstaller = true`, default): Downloads `.zip`/`.dmg` from `download.jetbrains.com`. Works for releases only.
- **Maven mode** (`useInstaller = false`): Resolves from Maven repos (`snapshots()`, `nightly()`). Required for snapshot/nightly versions.

Nightly builds (`262-SNAPSHOT`) require:
1. `nightly()` repo (`https://www.jetbrains.com/intellij-repository/nightly` — may require authentication/VPN; source: [Constants.kt#L250](https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/12b993e2a56a66c6fdde72deb0bebb02a1635622/src/main/kotlin/org/jetbrains/intellij/platform/gradle/Constants.kt#L250))
2. `useInstaller = false` (Maven resolution, not installer download)
3. Plugin version ≥ 2.14.0 (v2.11.0 doesn't handle nightly snapshots correctly)

Source: cloned at `~/Work/intellij-platform-gradle-plugin/` — key files:
- `IntelliJPlatformDependenciesHelper.kt` — dependency resolution, `useInstaller` default
- `IntelliJPlatformRepositoriesExtension.kt` — `nightly()` repo definition
- `RequestedIntelliJPlatformsService.kt` — snapshot version pattern matching

### Runtime Compatibility Tests

Validates the 253-built plugin works when loaded into newer IDEs at runtime.
Located in `test-experiments/.../PluginRuntimeCompatibilityTest.kt`.

```bash
./gradlew :test-experiments:test --tests '*PluginRuntimeCompatibilityTest*'
```

Each test starts a full IntelliJ IDE in Docker with the pre-built plugin installed,
then calls `list_projects`, `list_windows`, and `execute_code` via MCP HTTP.
The `list_windows` call exercises the code path affected by mcp-steroid#18.

## Plugin Deployment

```bash
./gradlew deployPlugin     # Hot-reload (requires Plugin Hot Reload plugin)
./gradlew deployPluginLocallyTo253  # Cold deploy (requires restart)
```

## Adding New MCP Tools

1. Create `@Service(Service.Level.APP)` handler with `register(server: McpServerCore)` method
2. Register in `SteroidsMcpServer.kt`: `service<MyToolHandler>().register(server)`

## IDE Control via execute_code

Invoke IDE actions programmatically. Key pattern:
```kotlin
val action = ActionManager.getInstance().getAction("RestartIde")
val dataContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
ActionUtil.invokeAction(action, dataContext, "mcp", null, null)
```

List actions: `ActionManager.getInstance().getActionIds("").filter { it.contains("restart", ignoreCase = true) }`

## Environment Constraints

`timeout`/`gtimeout` not available. Use Gradle timeout mechanisms or Bash tool's `timeout` parameter.

## Commit Guidelines

Atomic commits, descriptive messages (what and why). Test and build before committing.

## Website

`website/` is a separate git repo clone (jonnyzzz/mcp-steroid public repo). It contains the Hugo site sources in `website/website/`. The `website/` folder is gitignored from the main repo.

## IntelliJ Source Research

Use `run-agent.sh` from `~/Work/jonnyzzz-ai-coder/` to launch AI agents for researching IntelliJ Platform internals in `~/Work/intellij`, peer reviews, and development tasks. Using `run-agent.sh` from agentic sessions during development is encouraged — the BANNED rule only applies to production code and tests referencing it.

## AI Tests

Deploy fresh plugin (`./gradlew deployPlugin`), run prompts from `ai-tests/*.md`, validate outcomes. See `ai-tests/_INSTRUCTIONS.md`.

## Playground Tests for Interactive IDE Debugging

Use playground tests to start an IDE in Docker and keep it running indefinitely for manual
experimentation via MCP, screenshots, video, and CLI.

### How It Works

A playground test calls `IntelliJContainer.create().waitForProjectReady()` then blocks
the test thread. The IDE container stays running with MCP Steroid server, live video
stream, screenshot capture, and a loaded test project.

### Available Playgrounds

| Test | IDE | Command |
|------|-----|---------|
| `RiderPlaygroundTest` | Rider (.NET) | `./gradlew :test-experiments:test --tests '*RiderPlaygroundTest*' -Dtest.integration.ide.product=rider` |

To create a playground for another IDE, copy `RiderPlaygroundTest.kt` and change `IdeProduct.Rider` to the desired product.

### Connecting

After startup the test prints:

```
MCP:   http://localhost:<PORT>/mcp
```

**Claude Code CLI:**
```bash
claude --mcp-config '{"mcpServers":{"mcp-steroid":{"url":"http://localhost:<PORT>/mcp"}}}'
```

**Video stream:** `http://localhost:<PORT>/video.mp4`

**Container shell:** `docker exec -it <CONTAINER_ID> bash`

**Artifacts:** `run-<timestamp>-<name>/` — `video/`, `screenshot/`, `intellij/` (IDE logs), `session-info.txt`

### Use Cases

- API discovery: explore IDE APIs interactively via `steroid_execute_code`
- Prompt testing: verify prompt resource content works in the target IDE
- Debugging: set breakpoints, evaluate expressions, step through code via MCP
- Action discovery: use `steroid_action_discovery` to find available actions

### Rider/.NET Test Execution Architecture

Rider uses a fundamentally different test execution model from IntelliJ IDEA:

- **No standard RunConfigurationType** for NUnit/xUnit/MSTest — `RiderUnitTestRunConfigurationType`
  is `VirtualConfigurationType` (cannot be manually instantiated)
- **Test execution is backend-driven** via RD protocol to the ReSharper engine
- **Frontend entry point**: `project.solution.rdUnitTestHost`
- **SMTRunnerConsoleView is NOT used** — Rider has its own `RiderUnitTestTreeSessionDescriptor`

To run .NET tests programmatically, use `dotnet test` CLI or fire
`RiderUnitTestRunSolutionAction` / `RiderUnitTestRunContextAction` actions.
