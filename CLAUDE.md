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
- **Fail fast and log problems.** Never swallow exceptions with empty `catch` blocks or `catch (_: Exception) {}`. Every catch must either rethrow, log the error with `System.err.println` / `logger.error`, or both. Silent failure hides the root cause and wastes iteration cycles. When something can fail (DLL loading, file I/O, network calls), log what was attempted and what went wrong — the error message is often the entire diagnosis
- **BANNED:** Production code and tests must never reference or depend on `run-agent.sh` or `docs/run-agent.sh`. These scripts are tools for humans and AI agents to use manually during development (peer reviews, research, etc.), not for programmatic execution from project code. Code should implement agent integrations directly using CLI flags and arguments. `run-agent.sh` must **never** be installed inside Docker containers (no `COPY run-agent.sh` or `RUN chmod +x ... run-agent.sh` in Dockerfiles)
- **BANNED:** Gradle build files must never reach into another subproject's `build/` directory directly. Use Gradle dependency configurations to share artifacts between subprojects. Fail fast with a clear `require()`/`error()` — no silent fallbacks that hide misconfiguration
- **BANNED:** Do NOT use `append("\n")` or `append("...\n")` tricks to work around the `NoLargeInlineStringsTest` lint rule. When a `buildString { }` block exceeds the consecutive-`appendLine` limit, the correct fix is to move the content to `src/main/prompts/` resource files and reference them via article URIs — not to sprinkle `append("\n")` calls to artificially break the line count.
- **BANNED:** Do NOT hardcode `mcp-steroid://...` resource URIs as string literals in production Kotlin code. Use the generated prompt article class instead: `XxxPromptArticle().uri` (from `com.jonnyzzz.mcpSteroid.prompts.generated.*`). This ensures URIs stay in sync with prompt files and break at compile time if renamed. Enforced by `NoHardcodedMcpSteroidUriUsageTest`. See `FetchResourceToolHandler.kt` for the correct pattern.
- Log new ideas/tasks in TODO* files (TODO.md, TODO-*.md)
- **No infrastructure workarounds**: when tests fail due to infrastructure limitations (missing Docker socket, missing CLI, wrong JDK, missing native libraries), fix the infrastructure — mount Docker socket, install Docker CLI, configure JDK, install the dependency. Do NOT add code that detects the limitation and silently skips tests or changes behavior. A failing test that reveals a real problem is better than a passing test that hides it.
- **BANNED: detecting failures and skipping tests.** Tests exist to show problems. When a test fails, it stays failing and gets escalated to humans. Never add `try { } catch { skip() }`, `TestAbortedException` on error detection, `Assumptions.assumeTrue(isAvailable)`, or any pattern that turns a real failure into a skip. The only acceptable test skip is at the **Gradle task level** (`enabled = !condition`) when an entire test suite is structurally incompatible with the platform (e.g., native-only tests disabled on an OS that cannot run them). Individual test-level runtime skips that hide failures are forbidden. **Single documented exception: Gemini API key on CI** — `DockerGeminiSession.Companion` opts into `skipTestWhenKeyMissing=true` (see `test-helper/.../AISessionBase.kt`). The TeamCity server has no Gemini token configured and there is no plan to add one, so Gemini-using tests throw `AssumptionViolatedException` and are reported as ignored when `GEMINI_API_KEY`/`GOOGLE_API_KEY`/`~/.vertex` are all unset. The unresolved-TC-reference branch (`%credentialsJSON:…%`) still throws — that case remains a real misconfiguration. Do not extend this opt-in to other agents (Anthropic / OpenAI keys ARE configured on TC; their tests must keep failing if the key disappears).
- **Prefer Kotlin Coroutines native APIs over Java threading primitives**: use `CompletableDeferred<T>` + `withTimeout(duration) { deferred.await() }` instead of `CountDownLatch`. Use `Channel<T>` for streaming. Use `suspendCancellableCoroutine` for one-shot callbacks. `CountDownLatch` / `Semaphore` / `Object.wait()` are banned in new coroutine code — they block threads and are not cancellation-aware.
- **NEVER run `test-integration` or `test-experiments` tests in parallel** — each test starts a full Docker IntelliJ container. Running two or more concurrently exhausts RAM/CPU (IDE windows never appear, containers OOM). Always run one `./gradlew :test-integration:test --tests '...'` (or `:test-experiments:test --tests '...'`) at a time. Wait for it to finish completely before starting the next. This applies to all Docker-based tests: DPAIA arena, debugger demo, playground, CLI agent tests.
- **BANNED: `./gradlew test` at the root.** It fans out to every module — `:prompts:test` alone runs ~80 `KtBlocksCompilationTest` classes × 8 IDEs (external kotlinc processes) and can take hours after any cache-invalidating change. Always scope to the module(s) you touched: `./gradlew :ij-plugin:test`, `./gradlew :kotlin-cli:test`, `./gradlew :prompts:test --tests '<specific-class>'`. See the per-module guidance under "Test Task Isolation Rules".
- **Diagnose a stuck/slow test with JDK tooling BEFORE killing it.** `jps -l | grep GradleWorkerMain` → `jcmd <pid> Thread.print > /tmp/dump.txt` while the JVM is alive; then `grep '<YourTest>Test' /tmp/dump.txt -A 5` to find the blocked method. Killing first throws away the evidence and forces a guess-and-retry loop. See "Live JVM Thread/Coroutine Dumps". Once you have the stuck test's name, **iterate on just that test** (`./gradlew :ij-plugin:test --tests 'com.example.StuckTest'` with `--rerun-tasks`) — don't re-run the whole module.
- **Running an integration test: start a 1-minute timer; past that, investigate, don't just wait.** Any `:test-integration` or `:test-experiments` test case that hasn't printed a PASS / FAIL marker within ~60 s of reaching `> Task :*:test` is suspicious — the IDE inside the container is usually blocked on a dialog, an indexing stall, or a background task that will never finish on its own. Don't keep waiting. **Capture evidence in the container and analyze it before deciding what to do next:**
  - **Latest screenshot** — every Docker IDE test writes PNGs into `test-integration/build/test-logs/test/run-<timestamp>-<name>/screenshot/screen-*.png`. `ls -t .../screenshot/*.png | head -1` gives the most recent frame; `Read` it for a visual check — modal dialogs, "Invalid Gradle JVM configuration" banners, and onboarding pop-ups are all visible here.
  - **In-container thread dump** — `docker ps` → find the IDE container → `docker exec <id> jps -l` → `docker exec <id> jcmd <PID> Thread.print > /tmp/<name>-dump.txt`. Grep for `AWT-EventQueue-0`, `Observation.awaitConfiguration`, `SdkLookup`, `UnknownSdk*`, `collectConsent` to identify what's blocking. Full recipe + symptom→cause table in `test-integration/AGENTS.md` under "Debugging a stuck/hung Docker test".

  The cost of one 5-second thread-dump is tiny next to sitting through a 15-minute timeout; the recipes above have already proven this workflow for the Corretto-consent modal and the `CompilerDriverUnknownSdkTracker` `Resolving SDKs…` modal (see commits `46254df5`, `21522330`).

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

Gradle 9.4.1 / Kotlin 2.2.20 / Java 21 / IntelliJ Platform 2025.3+ / Ktor 3.1.0 (CIO+SSE) / kotlinx.serialization

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

**Prefer per-project `:module:test` over root `./gradlew test`.** Running the entire root suite blindly is almost always useless for stabilization work — it churns through ~100+ tests (many unrelated to the change at hand), can take 45+ minutes end-to-end, and buries a single real failure under noise from every other module. Only reach for root `test` when doing a pre-release green-sweep. For every other case:

- After touching `ij-plugin` production code: `./gradlew :ij-plugin:test` (or with `--tests '<pattern>'` for a subset).
- After touching `prompts/src/main/prompts/**`: `./gradlew :prompts:test --tests '<relevant KtBlocksCompilationTest>'`.
- After touching `kotlin-cli` (e.g., `CodeWrapperForCompilation`): `./gradlew :kotlin-cli:test`.
- After touching `buildSrc` codegen: `./gradlew :prompt-generator:test :prompts:generatePrompts` (the regeneration is the real validation).
- When a test fails under root `test`, re-run just that module first — much faster, same signal.

This repository's per-module split (`:ij-plugin`, `:prompts`, `:kotlin-cli`, `:prompts-api`, `:test-helper`, `:test-integration`, `:test-experiments`) is intentional and each module stands alone.

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
| `NoHardcodedMcpSteroidUriUsageTest` fails | Hardcoded `mcp-steroid://...` URI in production Kotlin | Replace with generated article class: `XxxPromptArticle().uri` (see `FetchResourceToolHandler.kt` for examples) |
| `:test-integration` hangs with `Blocking modal dialog detected` | Stale local `test-project/.idea/` pins `project-jdk-name`/`gradleJvm` to a name not in `ProjectJdkTable` → `SdkLookup` fires download-consent modal | Either sanitize the pins out of your local `.idea/` (it's gitignored, so only you hit this) or add the name to `mcpRegisterJdks` aliases; see "SDK-lookup modal paths" in MEMORY.md |
| `:test-integration` hangs with `MODAL DIALOG DETECTED — Resolving SDKs…` during `ProjectTaskManager.build()` | Third `unknown.sdk*` registry key missing: `-Dunknown.sdk.modal.jps=false` (gates `CompilerDriverUnknownSdkTracker.fixSdkSettings`) | Add the flag to `intelliJ.kt` `generateVmOptions()` alongside `unknown.sdk` / `unknown.sdk.auto` |
| `unresolved reference 'JavaSdk'` when running a PyCharm/GoLand/WebStorm/Rider test | Factory's early-JDK hook fires for IDEs without `com.intellij.java` on script classpath | Check `IdeProduct.hasJavaSdk` is true only for `IntelliJIdea`; new IDE products must set it truthfully |
| `ContentModuleClasspathTest` fails with "JAR(s) on filesystem but not in classpath" after an IDE upgrade | IDE release bundled a new unloaded content module (e.g. `tailwindcss.ruby.jar` in 2026.1.1) | Add the JAR path to `UNLOADED_CONTENT_MODULES_IU_261` with a one-line rationale |

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

The plugin (v2.13.1 in project, v2.14.0 latest) resolves IDEs in two modes:
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

## Windows CI Compatibility

Cross-platform code must avoid these Windows pitfalls:

### Line endings
- **`BufferedWriter.newLine()`** writes `\r\n` on Windows. Use `write("\n")` explicitly for
  protocol output (NDJSON, MCP responses).
- **`File.readText()`** preserves `\r\n`. Normalize with `.replace("\r\n", "\n")` when the
  content is compared against generated/assembled text.

### Path separators
- **`File.toRelativeString()`** uses `\` on Windows. Normalize with `.replace('\\', '/')` when
  paths are used as Kotlin identifiers or URI components.

### Command-line length
- Windows `cmd.exe /c` has an 8191-char limit; `CreateProcessW` has a 32K limit.
- **kotlinc**: `KotlincProcessClient.kotlinc()` always wraps args into a `@argfile` (kotlinc's
  native argfile support). If the caller already provides `@argfile`, skip re-wrapping.
- **OCR**: `OcrProcessClient` bypasses the `.bat` wrapper on Windows and invokes `java` directly
  with `-cp "lib/*"` (wildcard classpath) to avoid the `.bat`'s CLASSPATH expansion exceeding
  `cmd.exe` limits. OCR CLI args use `@argfile` on all platforms.

### Native libraries (OCR)
- JavaCPP uses platform-specific naming: `lib*.so` (Linux), `lib*.dylib` (macOS), `jni*.dll` (Windows).
- `ensureNativeLibraries()` in `OcrCli.kt` detects the platform and uses the correct prefixes.
- Aliases: Linux needs `.so` symlinks, Windows needs `.dll` copies — macOS needs `.dylib` symlinks.

## Environment Constraints

`timeout`/`gtimeout` not available. Use Gradle timeout mechanisms or Bash tool's `timeout` parameter.

## CI Aggregator Tasks

Root `build.gradle.kts` defines `ci`-prefixed aggregator tasks for TeamCity and GitHub Actions.
Run `./gradlew tasks --group ci` to list them.

| Task | Subprojects covered | Notes |
|------|-------------------|-------|
| `buildPluginOnCI` | `:ij-plugin` (builds + publishes ZIP) | Entry point for both GH Actions & TC "build plugin" configs |
| `ciBuildPluginTests` | All plugin modules **except** prompts + non-plugin (includes `ocr-tesseract`, `ocr-common`, `agent-output-filter`, `kotlin-cli`, etc.) | Per-OS matrix (Win/Linux/Mac) on TC |
| `ciBuildPromptsTests` | `prompt-generator`, `prompts`, `prompts-api` | Per-IDE on TC via `-Pmcp.prompts.ide.filter=<product>`; single-threaded on TC |
| `ciIntegrationTests` | `:test-helper:test` → `:ij-plugin:integrationTest` → `:test-integration:test` | **Strict sequential ordering** via `mustRunAfter`; requires Docker + API keys |

### `ciIntegrationTests` ordering

The three steps run cheapest-first, heaviest-last, with `mustRunAfter` ensuring no two
Docker IDE containers overlap even under `--parallel`:

1. `:test-helper:test` — pure-test infrastructure (Docker reaper, output-filter plumbing)
2. `:ij-plugin:integrationTest` — Docker CLI tests (Claude/Codex/Gemini); needs `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`
3. `:test-integration:test` — Docker IntelliJ smoke matrix (full IDE containers)

`:test-integration:test` has an `onlyIf` guard: it only runs when the task name contains
`:test-integration:` **or** `ciIntegrationTests`. Plain `./gradlew test` at root skips it.

### `ciBuildPromptsTests` runtime

The full `ciBuildPromptsTests` run (all three prompts subprojects, all IDE targets) takes
**significantly longer** than the `*KtBlock*`-only subset quoted elsewhere (~7 min).
Expect **60–120+ minutes** for the full matrix on a developer machine — each KtBlock
compilation test invokes `kotlinc` as an external process for every block × IDE combination.

## Gradle Daemon JVM

The Gradle Daemon is pinned to **JDK 21** via `gradle/gradle-daemon-jvm.properties`.
Without this, the daemon inherits whatever JVM is on `JAVA_HOME`/`PATH` — on some
workstations that defaults to JDK 25, which causes subtle "works on CI / breaks locally"
mismatches since all modules target JDK 21 via `kotlin { jvmToolchain(21) }`.

The `foojay-resolver-convention` plugin (declared in `settings.gradle.kts`) is the
download fallback: if no JDK 21 is found locally, Gradle auto-downloads one. It stays
completely silent on machines that already have a local JDK 21 installed.

To update the daemon JVM version: edit `gradle/gradle-daemon-jvm.properties` directly
(it's a one-line `toolchainVersion=N` file). The `./gradlew updateDaemonJvm --jvm-version=N`
task also works but requires network access to the foojay disco-API for generating
download URLs.

## Commit Guidelines

Atomic commits, descriptive messages (what and why). Test and build before committing.

## Git Remotes: `origin` vs `jb`

This clone has two remotes — they are **not** the same branch and must be synced deliberately:

| Remote | URL | Role |
|---|---|---|
| `origin` | `git@github.com:jonnyzzz/mcp-steroid` | Day-to-day development fork; source of truth for new commits |
| `jb` | `git@github.com:JetBrains/mcp-steroid.git` | Canonical JetBrains-org mirror; consumed by TeamCity (`mcp_steroid` project on `buildserver.labs.intellij.net`) |

**Sync direction rules:**
- **origin → jb**: always via merge (the `jb-merge` procedure below)
- **jb → origin**: always via cherry-pick (pick individual commits, resolve conflicts manually)
- **Never fast-forward-push `main` straight to `jb`** — `jb/main` carries org-specific commits that would be lost

The correct sync procedure — always a merge commit, always from a throwaway `jb-merge` branch:

```bash
git fetch jb
git checkout -b jb-merge jb/main
git merge main --no-ff -m "Merge remote-tracking branch 'origin/main' into jb-merge"
git push jb jb-merge:main
git checkout main
git branch -D jb-merge
```

The `--no-ff` is required: it preserves `jb/main`'s existing head as the first parent of the merge, so the jb-only history stays reachable.

This is also what every `Merge remote-tracking branch 'origin/main' into jb-merge` commit in `jb/main`'s log is doing — keep the pattern consistent.

**Why this matters for CI:** the TC VCS root `mcp_steroid_main` pulls from `jb`, not `origin`. If your commit isn't on `jb/main`, TeamCity builds stale code.

## TeamCity (`buildserver.labs.intellij.net`)

The project lives at https://buildserver.labs.intellij.net/project/mcp_steroid.

The TeamCity Kotlin DSL lives in a **separate** repository:
`~/Work/mcp-steroid-teamcity` → `git@github.com:JetBrains/mcp-steroid-teamcity.git`.

It is **not** inside the main mcp-steroid repo. Changes to the DSL follow their own commit/push cycle.
See `mcp-steroid-teamcity/CLAUDE.md` for the full DSL workflow (generate → backup → edit → regenerate → diff → commit).

Store your Personal Access Token at `~/.teamcity` (chmod 600) so REST calls can read it.

### DSL rules for agents

- **Every build configuration must be a checked-in Kotlin file.** The TC DSL runtime is
  sandboxed and cannot enumerate the filesystem of the mcp-steroid repo, introspect Kotlin
  classpath entries, or fetch external data. You cannot generate build configs "on the fly"
  at DSL evaluation time. Every test scenario that deserves its own TC build needs its own
  explicit `object XxxBuild : BuildType({ … })` declaration, or an explicit `for` loop over
  a statically-written list inside `settings.kts`. This is also why `SettingsTest` must
  enumerate the repo's test classes and assert that each has a matching build config — if
  the DSL itself could discover them, the check would be unnecessary.
- **Infrastructure-only commits on jb/main.** Only infrastructure / tooling changes should
  be authored directly on `jb/main`. Everything else (tests, features, bug fixes, new TC
  configs triggered by code changes) goes to `origin/main` first and reaches `jb/main`
  through the documented merge-sync procedure above. Direct commits to `jb/main` bypass
  origin review and are reserved for org-specific infra (compliance edits, TeamCity token
  rotations, etc.).
- **Triggering builds via TC MCP / curl.** The `buildserver` MCP tool forces
  `personal=true` on every queued build. For non-personal CI runs, POST directly to
  `/app/rest/buildQueue` with PAT from `~/.teamcity` (no `personal` field in body). The
  helper `/tmp/tc-trigger.sh` captures the pattern.
- **Reading build logs — use the TC MCP `teamcity_build_log` tool** with `filter=errors`/
  `warnings` and `start`/`count` for pagination. `/downloadBuildLog.html` redirects to
  interactive OAuth; PAT auth doesn't work for it.
- **Declare shared secrets at the root project** via `params { param("env.X", Ref(…).toString()) }`
  in `settings.kts`. TC param inheritance is parent → child only (NOT peer-to-peer), so
  declaring a `credentialsJSON:UUID` on a single build leaves peers failing with
  "unresolved TeamCity reference". When a build's `properties.property` REST field shows
  the literal `%credentialsJSON:…%` value instead of `******`, the substitution failed and
  the test will see the placeholder string — tests MUST fail hard in that state (no
  `TestAbortedException` / `Assume.assumeTrue` to paper over it).
- **`BuildType.requireLinuxDocker()` helper (`utils/LinuxDocker.kt`).** Every build that
  shells out to `docker` applies this bundle — `exists("docker.version")` + Linux_amd64
  + `dockerSupport { loginToRegistry = on(PROJECT_EXT_789) }`. The registry login routes
  base-image pulls through `registry.jetbrains.team`, avoiding the daemon-level mirror
  that occasionally 503s.
- **`freeDiskSpace { requiredSpace = "20gb"; failBuild = true }`** on every Docker-based
  test build. IDE image (~1.5 GB) + plugin + base layer + per-test run-dir content
  easily hit 15 GB+; without the gate, mid-build ENOSPC shows up as cryptic `docker cp:
  Could not find the file …` errors against `/mcp-run-dir` mount.
- **`publishArtifacts` emission pattern.** Emit AT TEARDOWN (lifetime cleanup action),
  NOT at container creation — TC processes the service message immediately, not at
  end-of-build. Use the recursive-glob form (`<path>/<star><star> => <dest>`) so TC
  resolves matching files at publish time; a literal `<dir> => <zip>` spec on an empty
  directory yields "Artifacts path '…' not found". Publish video + screenshots as
  **standalone artifacts** (under per-run folders) IN ADDITION to the zip, so TC's
  in-browser MP4 / image preview works without downloading the zip.
- **`-PtestFilter=<pattern>` in build.gradle.kts**, NOT `--tests` on the CLI. TC's Gradle
  runner emits `gradleParams` BEFORE task names in the final invocation, which detaches
  `--tests` from its task. A project-property applied programmatically via `Test#filter`
  is task-position-independent and survives every runner configuration.
- **`gradleParams` values are NOT shell-quoted.** TC passes gradleParams tokens directly
  to gradle (no intermediate shell). Wrapping a value in single quotes (e.g.
  `"-PtestFilter='*X'"`) makes the project property contain literal quotes, which never
  match anything. Patterns must be whitespace-free single tokens — no quoting needed.

### Linux bind-mount gotchas (for Docker-based tests)

- **Bind mounts do NOT remap UIDs** on Linux. A host directory owned by the TC-agent user
  (uid e.g. 999) is still owned by that uid inside the container, so a container user
  `agent` (uid 1000) cannot write to `/mcp-run-dir`. On macOS/Docker Desktop the virtiofs
  VM handles UID mapping transparently, hiding the issue locally.
- **Fix pattern:** call `File.setReadable(true, false)` / `setWritable(true, false)` /
  `setExecutable(true, false)` on the host dir after `mkdirs()` and before the container
  starts. Ownership still mismatches; mode bits 777 let any uid write.
- **git's "dubious ownership" check** fires on the same UID mismatch when a read-only
  bind mount is a git repo (e.g. `/repo-cache`). Workarounds tried:
  * `git -c safe.directory=*` — worked locally, rejected by TC agent's git build
  * `git -c safe.directory=<explicit-path>` — same
  * `git config --global --add safe.directory <path>` as a SEPARATE exec before the clone — **works**
  The container's `~/.gitconfig` is ephemeral so this doesn't leak anywhere.
- **`escapeShellArgs` must quote glob/meta chars.** Args passed through
  `docker exec … bash -c "<joined args>"` are subject to shell word-splitting; `*`, `?`,
  `[`, `]`, `$`, `;`, `&`, `|`, `<`, `>`, `(`, `)`, `!` — quote every one of them or
  tokens get rewritten silently (e.g. `safe.directory=*` → `safe.directory=<cwd-file-1>`).
- **SSH_AUTH_SOCK is NOT set on CI agents.** Tests that default `mountSshAgent = true`
  must fall back gracefully (log + skip the mount) when `SSH_AUTH_SOCK` is unset,
  rather than hard-failing. None of the DPAIA arena / debugger / bright-scenario tests
  actually need SSH (public HTTPS clones, local Maven/Gradle drivers).

### DSL generation

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd ~/Work/mcp-steroid-teamcity/.teamcity
mvn -q org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate
```

Must run on **JDK 21** — the Byte Buddy agent bundled by the TC DSL dependency does not support JDK 25+.
Generated XML lands in `.teamcity/target/generated-configs/` (gitignored).

### Build configurations

| TC config | DSL file | Gradle task | Agent requirements |
|-----------|----------|-------------|-------------------|
| `build number` | `_01_build_number.kt` | shell (VERSION + git hash) | any (runs in alpine Docker) |
| `teamcity settings test` | `_02_settings_test.kt` | (empty / TODO) | Linux |
| `ij-plugin test` (composite) | `_04_ij_plugin_test.kt` | — | — |
| `ij-plugin test (Linux/Mac/Win)` | `_04_ij_plugin_test.kt` | `ciBuildPluginTests` | per-OS |
| `prompt-test` | `_05_prompt_test.kt` | `ciBuildPromptsTests` | Linux |
| `test-integration` | `_06_test_integration.kt` | `ciIntegrationTests` | Linux + Docker |
| `test-experiments` | `_09_test_experiments.kt` | (empty / TODO) | Linux |
| `build plugin` | `_08_build_plugin.kt` | `buildPluginOnCI` | any (docker build image) |
| `website` | `_11_website.kt` | `make build` | Linux + Docker |
| `Deploy plugin to TBE` | `_AA_deploy_tbe.kt` | curl upload | any |

All configs snapshot-depend on `BuildNumber` via `useRootBuildNumber()`, which wires the build number pattern
and ensures the same VCS revision across the chain.

### TC DSL: build types must be global named objects

Every `BuildType` in the `.teamcity/` Kotlin DSL must be a **global `object` declaration**
(e.g., `object IjPluginTestWindows : BuildType({...})`), not an anonymous or local instance.
TC DSL serializes build configs by their Kotlin object identity — anonymous instances from
`listOf(object : BuildType {...})` or factory functions break ID resolution and cause
"Failed to load build settings from VCS" errors.

When adding a new build config, register it in `settings.kts` via `buildType(MyNewBuild)`.
For a matrix (e.g., per-IDE prompt tests), declare each variant as a named object and collect
them in a `val` list for iteration in composites and `settings.kts`.

### TC DSL: no sandboxed APIs in `runAllTests()`

The `runAllTests()` function in `.teamcity/test/main-test.kt` runs during DSL evaluation
(called from `settings.kts`). It must NOT use `System.getenv()`, file I/O, or other APIs
blocked by the TC DSL security sandbox. Filesystem-dependent tests go in `main()` only.

### Always bump queued builds to the top of the queue

**MANDATORY**: Every time you trigger a build on TC — whether via REST API or retrigger —
you **must** immediately move it (and all its snapshot dependencies) to the top of the queue.
No exceptions. The queue has 5000+ builds; without moving to top, our builds wait hours.

When an agent triggers a build on TC, the queued builds **must** be moved to the top of the
build queue so they land on an agent immediately instead of sitting behind thousands of
unrelated builds.

Use the internal `ajax.html` endpoint with the `moveToTop` parameter — this is the same
mechanism the TC UI "Move to the top" button uses:

```bash
TC=https://buildserver.labs.intellij.net
TOK=$(cat ~/.teamcity)
BUILD_ID=$1  # the build id to move to the top

curl -sS -X POST -H "Authorization: Bearer $TOK" \
  "$TC/ajax.html" -d "moveToTop=$BUILD_ID"
# Returns: <response /> on success
```

Always move **all** queued builds from a trigger (including snapshot dependencies like
`build number`) to the top. Example for multiple builds:

```bash
for id in 925418945 925418946; do
  curl -sS -X POST -H "Authorization: Bearer $TOK" "$TC/ajax.html" -d "moveToTop=$id"
done
```

**Why not the REST API?** The REST `POST /app/rest/buildQueue/order` requires listing *every*
queued build ID in the body — at JB scale (5000+ builds) this exceeds the 1 MB request limit.
The `PUT /app/rest/buildQueue/id:<id>` with `{"queuePosition":"top"}` returns HTTP 405 on this
server version. The `ajax.html` endpoint is the only working programmatic path.

### API keys on TC

Credentials stored as `credentialsJSON:*` on the TC server, referenced via `Tokens.kt`:

| Token | Env var on agent | Used by |
|-------|-----------------|---------|
| `ANTHROPIC_TOKEN_KEY_REF` | `ANTHROPIC_API_KEY` | `test-integration` (Cli Claude tests) |
| `OPENAI_TOKEN_KEY_REF` | `OPENAI_API_KEY` | `test-integration` (Cli Codex tests) |
| `TBE_PLUGINS_TOKEN_REF` | — (inline in script) | `Deploy plugin to TBE` |

**Missing:** `GEMINI_API_KEY` — no `credentialsJSON` ref exists yet. `CliGeminiIntegrationTest` will fail
with `GEMINI_API_KEY required` until one is added to the TC server and declared in `Tokens.kt`.

These keys are only needed by `:ij-plugin:integrationTest` (and the `:test-integration:test`
Docker smoke tests), NOT by the regular `:ij-plugin:test` task. The `ciBuildPluginTests`
aggregator deliberately excludes integration tests.

### Queue management — move builds to top

The TC queue has thousands of builds. After triggering a build, move it to the top:
```bash
curl -sS -X POST -H "Authorization: Bearer $TOK" "$TC/ajax.html" -d "moveToTop=$BUILD_ID"
```
Or use the "Move to top" button in the TC UI.

### Windows CI compatibility

- `BufferedWriter.newLine()` writes `\r\n` on Windows — use `write("\n")` for protocol output (NDJSON, MCP)
- `File.readText()` preserves `\r\n` — normalize with `.replace("\r\n", "\n")` when comparing against generated text

### Adding / changing a TC build configuration

1. Edit the `.kt` file in `~/Work/mcp-steroid-teamcity/.teamcity/builds/`
2. Every entity needs an explicit `id("...")` — omitting it risks build-history loss on rename
3. Use `setupIjPluginTestBuildForOs(...)` (in `builder.kt`) for per-OS Gradle test configs
4. Register the `BuildType` in `settings.kts` via `buildType(...)` — order there controls UI order
5. Regenerate, diff against baseline, verify only intended XML changed, commit & push

### Shared DSL helpers

- `builder.kt` — `setupIjPluginTestBuildForOs()`: shared scaffold for per-OS Gradle test builds (VCS, requirements, JDK, gradle step)
- `CpuAndOs.kt` — `CpuAndOs` enum + `setupRequirementsFor()` extension on `Requirements`
- `_01_build_number.kt` — `useRootBuildNumber()` extension: wires snapshot dependency + `buildNumberPattern` to the `BuildNumber` config

## GitHub Actions (`.github/workflows/`)

GitHub Actions runs on pushes to `origin/main` (the public `jonnyzzz/mcp-steroid` repo).
Currently scoped to building the publishable plugin ZIP — **full test coverage stays on
TeamCity** (`buildserver.labs.intellij.net`), whose internal agents run tests 3–5× faster
than GitHub-hosted runners.

| Workflow | File | What it does |
|----------|------|-------------|
| Build Plugin | `build-plugin.yml` | `build-number` job → `build-plugin` job (Docker, `buildPluginOnCI`), uploads `.zip` artifact |
| Deploy to GitHub Pages | `github-pages.yml` | Builds Hugo website, generates `updatePlugins.xml` from GitHub release, deploys to Pages |

**Build Plugin** — Plugin tests are intentionally NOT mirrored — they take 15+ min on
`ubuntu-latest` and regularly get cancelled by `cancel-in-progress` before completing.
PR commits don't auto-trigger; use `workflow_dispatch` on the PR's head branch.

**Deploy to GitHub Pages** — triggers on: push to `main` touching `website/**` or
`VERSION`, `release: published` events, and `workflow_dispatch`. The `release: published`
trigger is the primary path for releases — the website build queries the GitHub release
for the plugin ZIP download URL, so it must run after the release is created. The
push-triggered build from the website page commit may fail (release doesn't exist yet);
the `release: published` trigger handles the retry automatically.

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

## Prompt Optimization (Autoresearch)

MCP Steroid serves prompt resources (`mcp-steroid://` URIs) that guide AI agents. Optimizing
these prompts is an iterative process documented in `docs/autoresearch-findings.md`.

### Key Findings (from 51 arena runs + 2 autoresearch cycles)

- **MCP server instructions** (`prompts/src/main/prompts/mcp-steroid-info.md`) are metadata context,
  not behavioral directives — agents don't follow them during planning
- **Tool descriptions** are schema reference — MANDATORY warnings in them don't change behavior
- **MCP resources** (84 available via `ReadMcpResourceTool`) are never read (0/69 runs)
- **Arena prompt recipes** DO work — agents follow first-call exec_code recipes verbatim
- **exec_code output** drives next-step decisions — agents act on compile results immediately

### Where Agent Information Lives (priority order)

1. **User prompt** (arena task) — agents follow this. Put recipes here.
2. **exec_code output** — agents act on results. Put suggestions here.
3. **System prompt** (MCP server instructions) — background context only
4. **Tool schema description** — reference material, not directives
5. **MCP resources** — never accessed by agents

### Running Arena Experiments

```bash
# Single scenario (fast, ~5 min)
./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks

# Full 3-pass run
SKIP_IMPROVE=1 MAX_RUNS=1 bash docs/dpaia-arena-runner.sh 0
```

See `docs/arena-3pass-results.md` for full comparison table and `docs/autoresearch/` for
the Karpathy-style optimization loop prompts.

### Prompt Files

| File | What it controls | Impact |
|------|-----------------|--------|
| `prompts/src/main/prompts/mcp-steroid-info.md` | MCP server instructions (system prompt) | Low — ignored by agents |
| `prompts/src/main/prompts/skill/execute-code-tool-description.md` | steroid_execute_code description | Medium — read as reference |
| `test-experiments/.../arena/ArenaTestRunner.kt` (`buildPrompt()`) | Arena task prompt | High — agents follow recipes |
| `prompts/src/main/prompts/skill/*.md` | MCP resources | Low — never read by agents |

### Active DPAIA Working Notes

- Use `TASKS.md` for the active DPAIA/autoresearch task list and `MEMORY.md` for factual handoff notes.
- New DPAIA ideas must also be logged in a TODO-style file; current pointer is `TODO-DPAIA.md`.
- Direction changes require 3 `run-agent.sh` reviews and consensus before selecting the next low-hanging fruit.
- Current consensus (2026-04-26): prune contradictory DPAIA MCP prompt guidance in
  `ArenaTestRunner.buildPrompt()` first; keep Gradle-specific prompt resources as a larger follow-up.
- Follow-up 3-agent review (2026-04-27, `/tmp/mcp-steroid-review/runs-next-20260427/`) selected
  global apply-patch prompt-resource routing before Gradle-resource work. The issue: the arena prompt
  used dedicated `steroid_apply_patch`, but `execute-code-tool-description.md` still taught the slower
  `steroid_execute_code` + script-context `applyPatch` DSL as the default.
- Apply-patch persistence fix (2026-04-27): `steroid_apply_patch` now saves every touched document before
  returning, and `ApplyPatchToolIntegrationTest` verifies success and failure cases through the actual MCP
  HTTP tool with direct disk reads. Reference checked in `~/Work/intellij`: IntelliJ's patch path saves the
  document after changing it.
- Latest measured Gradle run (2026-04-27 UTC, run dir
  `run-20260427-115129-dpaia__spring__boot__microshop-2-mcp`): `DpaiaMicroshop2Test.claude with mcp`
  fixed the task in 136s and the full Gradle suite passed. First MCP call printed
  `Recommended JAVA_HOME: /usr/lib/jvm/temurin-25-jdk-arm64`; both Bash Gradle calls used that path, with
  no Java 21 dead-end and no `invalid source release: 24`. Metrics: 15 total tool calls, 3 `steroid_execute_code`,
  1 `steroid_apply_patch` with 8 hunks, 0 native Edit, 3 Read, 3 Write, 2 Glob, 2 Bash, 0 tool errors, 979,647 tokens.
  Delta versus the 171s baseline: Bash 4 -> 2 and agent time 171s -> 136s. Copyable Gradle prompt examples now
  include `JAVA_HOME=<Recommended JAVA_HOME>`, and decoded-log regression coverage flags Gradle Bash calls that drift
  back to Java 21, omit JAVA_HOME, use wildcard JAVA_HOME assignments, or use unsafe absolute wrapper paths.
- IntelliJ monorepo lookup note (2026-04-27): for MCP scripts that need indexed reads after initial import,
  prefer `Observation.awaitConfiguration(project)` followed by one `smartReadAction(project)` around the whole query.
  `waitForSmartMode()` is not a stable handoff; IntelliJ source explicitly says another dumb mode can begin before
  the next statement. The green regression test is `IntelliJThisLoggerLookupTest`. MCP server/resource guidance now
  routes `IndexNotReadyException` and indexed PSI reads to that pattern.
- Follow-up fixes from that monorepo run (2026-04-27): `ExceptionCaptureService` now handles JUL severe records with
  null `LogRecord.parameters` without masking the original IDE error, and IntelliJ checkout setup now honors explicit
  configured ZIPs/checkouts before reusing the cached TeamCity archive, while preserving the checkout's real `origin`
  remote for in-container fetches.
- FIR follow-up from that monorepo run (2026-04-27): `IntelliJThisLoggerLookupTest` now fails if lookup-time logs contain
  the known Kotlin FIR severe signatures. The test avoids triggering `KaFirReferenceResolver` on the whole monorepo by
  using `CacheManager.getVirtualFilesWithWord(..., UsageSearchContext.IN_CODE, ...)` plus Kotlin PSI `KtCallExpression`
  filtering for actual `thisLogger()` call sites; the fixed run found 2670 calls across 1522 files with no FIR severe
  logs after the lookup started.
- Historical review consensus for the earlier exception-capture/checkout fixes selected Gradle/JDK prompt guidance as
  the next item; that Gradle/JDK work is now complete. Do not treat that historical note as the current next task.
- Current review consensus for the FIR follow-up: Claude/Codex/Gemini approved the diff after Codex-requested cleanup.
  The selected Gradle-focused MCP prompt resource is now implemented as `mcp-steroid://skill/execute-code-gradle`.
  It routes Gradle sync/test work inside `steroid_execute_code` to IntelliJ ExternalSystem APIs, keeps Bash `./gradlew`
  outside `steroid_execute_code`, and keeps `ProcessBuilder("./gradlew")` banned inside `steroid_execute_code`.
- Gradle resource review (2026-04-27, `/tmp/mcp-steroid-review/gradle-prompt-resource-20260427/runs/`):
  Claude, Codex, and Gemini approved. The next low-hanging item by 2/3 reviewers is to measure
  `DpaiaMicroshop2Test.claude with mcp` against the 136s JDK-fixed baseline and check whether the new resource reduces
  Gradle Bash calls or hand-rolled Gradle snippets; Gemini's alternate candidate was a PSI refactoring recipe resource.
- Gradle resource measurement (2026-04-27, run dir
  `run-20260427-135940-dpaia__spring__boot__microshop-2-mcp`): the host test passed and the agent emitted
  `ARENA_FIX_APPLIED: yes`, but it fetched 0 MCP resources and did not use `mcp-steroid://skill/execute-code-gradle`.
  Metrics regressed versus the 136s JDK-fixed baseline: 170.8s agent time, 28 total calls, 5 Bash calls, 2 tool errors,
  and 1,458,578 tokens. The agent used `steroid_apply_patch`, then an IDE build returned `Build errors: false, aborted: true`,
  then it fell back to Bash Gradle with the correct JDK. Treat this as evidence that the resource content is OK but
  discoverability/routing is the next problem.
- Gradle resource routing fix (2026-04-27): after review under
  `/tmp/mcp-steroid-review/gradle-resource-measurement-20260427/runs/`, Gradle arena prompts now explicitly call
  `steroid_fetch_resource` for `mcp-steroid://skill/execute-code-gradle`, Maven prompts route build-abort sync to
  `mcp-steroid://skill/execute-code-maven`, and execute-code prompt resources tell agents to sync before Bash fallback
  on `errors=false, aborted=true`. Validation passed for `ArenaPromptContractTest` plus changed prompt KtBlocks and
  `MarkdownArticleContractTest`. Next measurement should rerun Microshop-2 and first check `fetch_resource_calls >= 1`;
  do not over-interpret runtime from a single Microshop-2 run because recent variance is wide.
- Gradle resource routing measurement (2026-04-27, run dir
  `run-20260427-142637-dpaia__spring__boot__microshop-2-mcp`): the host test passed and the full Gradle suite passed
  in 142.0s agent time, with 10 total calls, 4 MCP calls, 2 Bash calls, 0 tool errors, and 764,238 tokens. This improved
  over the 170.8s post-resource run, but `fetch_resource_calls` was still 0. The decoded log shows the agent saw
  `Build errors: false, aborted: true`, mentioned Gradle sync, then chose Bash directly. Next low-hanging fix:
  result-boundary guidance from `steroid_execute_code` for aborted builds, using generated Maven/Gradle prompt article
  classes rather than hardcoded MCP URI strings.
- Aborted-build result-boundary guidance (2026-04-27): `ExecuteCodeToolHandler` now appends a `HINT` when tool output
  contains `Build errors: false, aborted: true` or `Compile errors: false, aborted: true`. The hint points agents to
  `steroid_fetch_resource` for the detected Gradle/Maven resource before Bash fallback. Production code uses
  `ExecuteCodeGradlePromptArticle().uri` and `ExecuteCodeMavenPromptArticle().uri`; `ExecuteCodeBuildAbortGuidanceTest`
  covers Gradle, Maven, mixed/unknown/null roots, successful-build no-op, and result preservation. Review under
  `/tmp/mcp-steroid-review/build-abort-guidance-20260427/runs/` approved by Claude/Codex/Gemini; scoped `:ij-plugin:test`
  with that test plus `NoHardcodedMcpSteroidUriUsageTest` passed. Next measurement is Microshop-2 with
  `fetch_resource_calls >= 1` as the first criterion.
- Aborted-build boundary measurement (2026-04-27): first run
  `run-20260427-144355-dpaia__spring__boot__microshop-2-mcp` failed before the agent ran because the Docker IDE
  container disappeared during repository setup. Valid run `run-20260427-150914-dpaia__spring__boot__microshop-2-mcp`
  passed the full Gradle suite in 169.6s agent time. The new hint was visible directly after
  `Build errors: false, aborted: true`, but Claude still made 0 `steroid_fetch_resource` calls and used 3 Bash calls.
  Metrics: 15 total calls, 4 MCP calls, 0 tool errors, 985,678 tokens. Treat the fetch-only boundary hint as a failed
  hypothesis; next low-hanging correction needs stronger actionability, such as an exact next tool call name or an
  inline minimal Gradle sync recipe.
- Explicit aborted-build boundary hint (2026-04-27): review under
  `/tmp/mcp-steroid-review/build-abort-boundary-measurement-20260427/runs/` reached 3/3 consensus to try exact
  Claude tool-name wording first. Commit `c29e13b4` changed the hint to `REQUIRED ACTION` / `NEXT TOOL CALL must be
  mcp__mcp-steroid__steroid_fetch_resource` and prepended a newline before appended guidance so decoded logs do not
  show `trueHINT`. Focused `:ij-plugin:test` for `ExecuteCodeBuildAbortGuidanceTest` plus
  `NoHardcodedMcpSteroidUriUsageTest` passed through the IntelliJ Gradle runner.
- Explicit hint measurement (2026-04-27): run
  `run-20260427-151926-dpaia__spring__boot__microshop-2-mcp` passed the full Gradle suite in 174.0s agent time, but
  still made 0 `steroid_fetch_resource` calls. The decoded log shows the separate-line `REQUIRED ACTION` at line 744,
  followed by Bash Gradle at line 747. Metrics: 19 total calls, 4 MCP calls, 2 Bash calls, 1 native Read error,
  1,255,211 tokens. Stop iterating on fetch-only wording for this scenario; next review should choose inline minimal
  Gradle sync guidance at the boundary versus removing/replacing the failed hint.
- Gradle abort root-cause fix (2026-04-27): review under
  `/tmp/mcp-steroid-review/gradle-jdk24-finaltasks-20260427/runs/` approved by Claude/Codex/Gemini. Gradle import
  setup now sets the linked Gradle JVM from the configured project SDK and waits for
  `ProjectDataImportListener.onFinalTasksFinished` before smart mode; this follows IntelliJ's own Gradle wait pattern.
  DPAIA Microshop Gradle cases now use JDK 24 because Gradle 8.14.3 rejects Java 25 as the daemon JVM, and the Docker
  IDE base installs Temurin 24. Validation: `GradleCompileTest` passed with `GRADLE_JVM=25`, `BUILD_ERRORS=false`,
  `BUILD_ABORTED=false`; Microshop-2 MCP run `run-20260427-161050-dpaia__spring__boot__microshop-2-mcp` passed with
  `Recommended JAVA_HOME: /usr/lib/jvm/temurin-24-jdk-arm64` and `Build errors: false, aborted: false`.
- Gradle IDE guidance update (2026-04-27): review under
  `/tmp/mcp-steroid-review/gradle-ide-guidance-20260427/runs/` approved by Claude/Codex/Gemini.
  `execute-code-gradle.md` now uses `ProjectDataImportListener.onFinalTasksFinished` as the Gradle sync boundary,
  and Gradle arena prompts inline an IDE-native `ProjectTaskManager.build(*modules).await()` check before Bash fallback.
  Validation passed for the scoped prompt tests and `ArenaPromptContractTest`.
- Gradle IDE guidance measurement (2026-04-27): Microshop-2 MCP run
  `run-20260427-185422-dpaia__spring__boot__microshop-2-mcp` passed with `ARENA_FIX_APPLIED: yes`,
  `Build errors: false, aborted: false`, and full Gradle suite success. Metrics: 1,370,218 tokens, 26 calls,
  4 MCP calls, 22 native calls, 12 Read, 4 Glob, 3 Write, 2 Bash, 0 errors, 0 resource fetches. Delta versus the
  post-JDK24/final-tasks baseline: tokens 1,773,570 -> 1,370,218, calls 36 -> 26, Bash 5 -> 2.
- Current next low-hanging item from the follow-up measurement review
  (`/tmp/mcp-steroid-review/gradle-ide-guidance-measurement-20260427/runs/`): batch source discovery and related file
  reads with IDE/VFS APIs in one `steroid_execute_code` call before falling back to native `Glob`/`Read`. All three
  reviewers approved the current patch and picked this same next target.
- Constraints for this track: do not add `McpSteroid*` interface methods and do not add MCP tools.

### Git Remotes Sync

**Sync direction rules:**
- **origin → jb**: always via merge (the `jb-merge` procedure)
- **jb → origin**: always via cherry-pick (individual commits, manual conflict resolution)
