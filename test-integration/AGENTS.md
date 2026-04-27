# test-integration — Agent Guide

**Stable** Docker-based integration tests + shared infrastructure for the wider integration test
suite. Experimental / long-running tests live in the sibling `:test-experiments` module, which
depends on this one for the infrastructure.

## Researching IntelliJ APIs — Use MCP Steroid + Debugger

**The IntelliJ project is open in the IDE (`~/Work/intellij`).** Use `steroid_execute_code`
with `project_name="intellij"` to research APIs directly via PSI — this is faster and more
accurate than file-based search.

**Use the debugger instead of reading code** — when you need to understand runtime behavior
(e.g., what `UnknownSdkTracker` actually does, how `MavenRunConfigurationType` launches a process),
set a breakpoint and step through. This is significantly more effective than reading source:
- Set breakpoint: `steroid_execute_code` with `XDebuggerUtil.toggleLineBreakpoint()`
- Launch debug: fire `DebugClass` context action or create a debug run config
- Evaluate expressions at breakpoint: `XDebugSession.currentStackFrame.evaluateExpression()`
- See `mcp-steroid://prompt/debugger-skill` for the full workflow

**Pattern: Find a class and inspect its methods**
```
// steroid_execute_code on project "intellij"
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val scope = GlobalSearchScope.allScope(project)
val files = readAction { FilenameIndex.getVirtualFilesByName("MavenRunConfigurationType.java", scope) }
files.forEach { f ->
    val lines = String(f.contentsToByteArray(), f.charset).lines()
    lines.forEachIndexed { idx, line ->
        if (line.contains("fun runConfiguration") || line.contains("@Deprecated") || line.contains("@ApiStatus")) {
            println("L${idx+1}: ${line.trim()}")
            for (i in 1..5) { lines.getOrNull(idx+i)?.let { println("  L${idx+1+i}: ${it.trim()}") } }
        }
    }
}
```

**Why this is better than file search:**
- O(1) indexed lookup via `FilenameIndex` — no `find` or `grep` needed
- Can use PSI to resolve types, find usages, check deprecation annotations
- Works on ALL open projects (intellij, mcp-steroid, jb-cli)
- Finds internal/non-exported classes that grep might miss

**Both you and sub-agents MUST use MCP Steroid** for IntelliJ API research — not file search tools.

## Debugging a stuck/hung Docker test — collect thread dumps FIRST

When a `test-integration` or `test-experiments` test hangs (IDE window never appears,
project never finishes importing, `waitForIdeWindow` times out, assertions stall), **do NOT
kill the Gradle task first**. The JVM inside the container is still alive and holds all the
evidence you need in a thread dump. `--rm` only runs on container stop, so you have time to
poke at it.

### Recipe

```bash
# 1. Find the running IDE container (most recent, image built from the test's Dockerfile)
docker ps --format '{{.ID}}\t{{.Image}}\t{{.Names}}\t{{.CreatedAt}}'

# 2. Find the IDE JVM PID inside that container (it's always com.intellij.idea.Main)
docker exec <CONTAINER_ID> jps -l
# → e.g.   766 com.intellij.idea.Main

# 3. Take a full thread dump (includes coroutine dump via the plugin's DebugProbes)
docker exec <CONTAINER_ID> jcmd <PID> Thread.print > /tmp/ide-thread-dump.txt

# 4. Inspect — the EDT (AWT-EventQueue-0) is the primary suspect for modal-dialog hangs
grep -n "AWT-EventQueue-0" /tmp/ide-thread-dump.txt   # find line number
sed -n '<LINE>,$p' /tmp/ide-thread-dump.txt | head -80  # read its stack
```

### What the stack tells you

| Symptom on EDT | Likely cause |
|---|---|
| `DialogWrapperPeerImpl.show` → `MessageDialogBuilder$YesNo.ask` → `UnknownSdkFixActionDownloadBase.collectConsent` | A named SDK (e.g. `corretto-21`) is pinned in `.idea/misc.xml` or `.idea/gradle.xml` and the container doesn't have a `ProjectJdkTable` entry with that exact name. IntelliJ fires `SdkLookup` at project open, which proposes a download, which blocks the EDT on a YesNo modal. |
| `DialogWrapperPeerImpl.show` → `MessageDialogBuilder$YesNo.ask` → `ClassicUiToIslandsMigration` or similar | A "Meet the Islands Theme" / onboarding modal. Fix via `early-access-registry.txt` + `options/other.xml` startup stubs (see `writeEarlyAccessRegistry` / `writeStartupProperties` in `intelliJ.kt`). |
| Deep inside `VfsData` init under `fleet.kernel.Transactor` with `urlopen`/`socket` frames | `AIPromoWindowAdvisor` is blocking startup on a `frameworks.jetbrains.com` HTTP fetch. Fix via `-Dllm.show.ai.promotion.window.on.start=false` + the AI-promo startup stubs. |

### Finding the *caller* that triggered the modal

The EDT frame only shows the dialog itself. The real caller is usually another thread
blocked on `invokeAndWait`:

```bash
grep -n "UnknownSdk\|SdkLookup\|SdkType\|Workspace\|ApplicationImpl pooled thread" /tmp/ide-thread-dump.txt
```

Look for the pooled thread whose stack ends in `SwingUtilities.invokeAndWait` + the relevant
IntelliJ method. That thread's Kotlin frames (if any) identify which entry point kicked off
the modal (e.g. `SdkLookupContextEx.runSdkResolutionUnderProgress` → Gradle plugin called
`SdkLookup.newLookupBuilder().executeLookup()` because of `gradleJvm="corretto-25"` in
`.idea/gradle.xml`).

### Only kill the container after you have the dump

`docker stop <CONTAINER_ID>` after saving the dump, then Ctrl-C the Gradle task. Copy the
dump out of `/tmp` into the failing test's `run-*/intellij/` folder if you plan to iterate
on the fix — keeping the dump alongside the run-dir artifacts (video, screenshots, logs)
makes later comparisons trivial.

## RLM Analysis of Arena Runs (run-*/intellij/mcp-steroid/)

Each arena run creates server-side exec_code logs at `run-*/intellij/mcp-steroid/eid_*`. Structure:
- `reason.txt` — agent's intent for the call
- `script.kts` / `script-wrapped.kts` — actual Kotlin code executed
- `output.jsonl` — execution output (each line: `{"text":"..."}`)
- `success.txt` / `compilation-success.txt` — result status
- `params.json` — timeout, task_id, etc.
- `compiled/` — compiled class files

### Execution Pattern (confirmed across 6 scenarios)

Infrastructure calls (task_id: `integration-test`) run during environment setup, OUTSIDE
the agent measurement window — they are not bottlenecks. Only agent calls count.

**Agent calls (1-3 per scenario, inside measurement window):**
1. **VCS + env check**: Docker, Maven path, JDK list, VCS-modified files
2. **Compile check**: `ProjectTaskManager.buildAllModules()` — ALWAYS triggers "Resolving SDKs..." modal
3. **Error inspection** (optional): Check problem list when build reports errors

### Known Bottleneck: "Resolving SDKs..." Modal Dialog

Every `ProjectTaskManager.buildAllModules()` triggers a `Resolving SDKs...` modal that the
dialog_killer dismisses. This causes `Build errors: true, aborted: false` even when compilation
actually succeeded. The agent then wastes an exec_code call checking the empty problem list,
then falls back to `./mvnw test-compile` via Bash (25-60s).

**Confirmed across ALL 6 scenarios**: modal fires, `Build errors: true`, problem list is empty.
In 2 of 6 scenarios, `Build errors: false` is correctly reported (modal may have resolved faster).

### Key Findings for Prompt Optimization

1. **Agents never use exec_code for test execution** — only for VCS check + compile check
2. **Agents never read MCP Steroid skill resources** (0/6 scenarios read `mcp-steroid://` URIs)
3. **JDK list is printed in first call** but agents still try wrong JDKs via Bash
4. **"Build errors: true" false positive** wastes 1 exec_code + 1 Bash call per scenario

## Configuring the IDE — always via `mcpExecuteCode`, never via XML

Every piece of IDE state that a test relies on (JDKs, trusted paths,
project open, module SDKs, …) must be set up by calling the IntelliJ API
through `session.mcpSteroid.mcpExecuteCode(code = …)` — **never** by
hand-writing config XML into `$configGuestDir/options/*.xml`.

Rationale: we tried the XML route for JDK registration and it failed
silently. A single unescaped `"` in an attribute made
`FileBasedStorage` reject `jdk.table.xml` with `WARN Cannot read …`,
which in turn left the JDK table empty, which made
`UnknownSdkStartupChecker` fire a download-consent modal at project
open — and that modal deadlocked the test run in headless Docker for
10+ minutes before any assertion ever ran. XML writes are far too
fragile for this: no typed feedback, no compile checks, no unit tests
reach deep enough to catch a malformed attribute.

The `mcpExecuteCode` path is strictly better: Kotlin is type-checked at
runtime, the canonical IntelliJ API (`JavaSdk.createJdk(name, path,
false)` / `ProjectJdkTable.addJdk` inside `writeAction { }`, or
`SdkConfigurationUtil.createAndAddSDK`) does all of the classpath /
`jrt://` wiring for us, and every failure lands as a normal exception
in the script output instead of a silent WARN.

Use the atomic driver APIs — don't hand-roll new `mcpExecuteCode`
scripts that touch `ProjectJdkTable`:

```kotlin
// Query what's registered
val jdks: List<JdkInfo> = session.mcpSteroid.mcpListJdks()

// Add one — idempotent, skips if `findJdk(name) != null`
session.mcpSteroid.mcpAddJdk(
    name = "corretto-21",
    homePath = "/usr/lib/jvm/temurin-21-jdk-arm64",
)

// Bulk: discover every Temurin dir in /usr/lib/jvm and register it under
// three aliases each — bare, corretto-N, temurin-N — so projects checked
// into VCS with `project-jdk-name="corretto-21"` resolve locally instead
// of triggering SdkLookup's download-consent modal.
session.mcpSteroid.mcpRegisterJdks(guestProjectDir)
```

Under the hood both `mcpListJdks` and `mcpAddJdk` issue a single
`steroid_execute_code` call — the embedded Kotlin uses the shortest
named-JDK path available: `JavaSdk.createJdk(name, home, isJre=false)`
plus `writeAction { ProjectJdkTable.addJdk(sdk) }`. This is two lines
because `createAndAddSDK(path, sdkType)` auto-generates a unique name
(e.g. `21-ea-1758`), which breaks the whole point of matching
`project-jdk-name="corretto-21"`.

`mcpRegisterJdks` is also called automatically by
`IntelliJ_factoryKt.create` right after `waitForMcpReady`, racing the
project-open `SdkLookup` so `findJdk(sdkName)` sees our aliases before
the download-consent modal path can fire. Tests only need to call it
again if they want to verify the state.

### Still-acceptable XML touches

The launch-time startup XML we write from Kotlin (
`options/AIOnboardingPromoWindowAdvisor.xml`, trusted-paths, consent,
early-access-registry) stay because they control bits that must be set
**before** the IDE starts — so there is no MCP server to talk to yet.
Keep those small, copy them verbatim from IntelliJ's own defaults, and
never let them carry user-provided values that could go wrong at
render time.

### Modal dialogs must never block the harness

As belt-and-suspenders against the modals that fire during project
open, import, and compile, the IDE `.vmoptions` disables **three**
registry keys — one for each entry point the IntelliJ SDK-resolution
code can take into a modal dialog:

| VM flag | Gated code path | What the modal would be |
|---|---|---|
| `-Dunknown.sdk=false` | `UnknownSdkTracker.isEnabled()` at `UnknownSdkTracker.java:57` | no tracker activity; no download fixes ever created |
| `-Dunknown.sdk.auto=false` | `UnknownSdkTracker.createCollector()` at `UnknownSdkTracker.java:76` | tracker exists but never runs; `onLookupCompleted` skipped, no `UnknownSdkFixActionDownloadBase` → no `collectConsent` |
| `-Dunknown.sdk.modal.jps=false` | `CompilerDriverUnknownSdkTracker.fixSdkSettings` at `CompilerDriverUnknownSdkTracker.kt:41` | `Task.WithResult` modal 'Resolving SDKs…' fires during `ProjectTaskManager.build()` |

With all three on, every async `SdkLookup` caller is silenced before
it can request user consent. `mcpRegisterJdks` (from
`IntelliJ_factoryKt.create` immediately after `waitForMcpReady`)
still seeds `ProjectJdkTable` with the three alias forms — bare
version, `corretto-N`, `temurin-N` — so modules that reference a
named JDK in `.idea/*.xml` still resolve without a lookup round-trip.

`waitForIdeWindow` fails fast on any modal detected during startup —
see `IntelliJContainer.kt`. When that trips, the saved PNG under
`run-*/screenshot/` shows which dialog is up and the thread-dump
recipe higher in this file identifies the caller.

### Non-Java IDEs skip JDK setup

`mcpRegisterJdks` / `mcpAddJdk` / `mcpSetProjectSdk` all import
`com.intellij.openapi.projectRoots.JavaSdk`, which is only on the
script classpath in IDEs that bundle the `com.intellij.java` plugin
(IntelliJ IDEA). PyCharm, GoLand, WebStorm, and Rider all fail to
compile `mcpListJdks`'s script with `unresolved reference 'JavaSdk'`.

To handle this cleanly, `IdeProduct` carries a `hasJavaSdk: Boolean`
flag. Both the factory's early-JDK hook and
`IntelliJContainer.waitForProjectReady` gate the JDK steps on it, so
a non-Java IDE logs `Skipping JDK setup — <IDE> has no Java plugin`
and moves on. If you add a new IDE product, set the flag truthfully.

## Architecture

```
test-integration/
  src/main/kotlin/.../infra/   # Shared infrastructure (containers, drivers, MCP client)
                               # — published as a regular library so :test-experiments can reuse it
  src/main/resources/skills/   # MCP skill resources loaded via classpath
  src/test/
    docker/
      ide-base/          # Base Docker image (Debian + X11 + agents)
      ide-agent/         # IDEA-specific image (extends base + JDKs)
      rider-agent/       # Rider-specific image (extends base + .NET SDK)
      goland-agent/      # GoLand-specific image (extends base)
      pycharm-agent/     # PyCharm-specific image (extends base)
      webstorm-agent/    # WebStorm-specific image (extends base)
      test-project/      # Kotlin project with intentional bug (IDEA)
      test-project-rider/  # .NET project with intentional bug (Rider)
      test-project-goland/ # Go project (GoLand)
      test-project-pycharm/# Python project (PyCharm)
      test-project-webstorm/# JS project (WebStorm)
    kotlin/.../
      infra/             # Pure-JVM unit tests for the infra (no Docker)
      tests/             # Stable Docker smoke tests (release matrix)
```

The Docker image / fixture-project tree under `src/test/docker/` is referenced by both modules
via the `test.integration.docker` system property (set per test task in each module's
`build.gradle.kts`). `:test-experiments` reads it as a sibling-project resource.

## Playground Tests for Interactive Debugging

Playground tests start an IDE in Docker and block indefinitely, allowing you to connect
to the running IDE via MCP and experiment interactively. This is the primary technique
for developing and debugging IDE-specific features.

### How to Start

```bash
./gradlew :test-experiments:test --tests '*RiderPlaygroundTest*' \
  -Dtest.integration.ide.product=rider
```

After startup, the test prints connection info. Also check `session-info.txt` in the run directory:
```
MCP_STEROID=http://localhost:<port>/mcp
VIDEO_DASHBOARD=http://localhost:<port>/
```

### Connecting to the Playground

**Via curl (for quick API calls):**
```bash
# List projects
curl -s -X POST http://localhost:<PORT>/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"steroid_list_projects","arguments":{}}}' | python3 -m json.tool

# Execute code
cat > /tmp/mcp-request.json << 'EOF'
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"steroid_execute_code","arguments":{
  "project_name":"DemoRider",
  "reason":"test something",
  "task_id":"playground",
  "code":"println(project.name)"
}}}
EOF
curl -s -X POST http://localhost:<PORT>/mcp \
  -H "Content-Type: application/json" \
  -d @/tmp/mcp-request.json | python3 -m json.tool

# Discover actions at a file location
curl -s -X POST http://localhost:<PORT>/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"steroid_action_discovery","arguments":{
    "project_name":"DemoRider",
    "file_path":"DemoRider.Tests/LeaderboardTests.cs",
    "caret_offset":660
  }}}' | python3 -m json.tool

# Take a screenshot
curl -s -X POST http://localhost:<PORT>/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"steroid_take_screenshot","arguments":{
    "project_name":"DemoRider",
    "reason":"check IDE state",
    "task_id":"playground"
  }}}' | python3 -m json.tool
```

**Via Claude Code (full interactive session):**
```bash
claude --mcp-config '{"mcpServers":{"mcp-steroid":{"url":"http://localhost:<PORT>/mcp"}}}'
```

**Tip**: For complex execute_code payloads with string escaping issues, write the JSON
to a file and use `curl -d @/tmp/request.json` instead of inline `-d`.

### Available Playgrounds

| Test Class | IDE | Command |
|---|---|---|
| `RiderPlaygroundTest` | Rider | `--tests '*RiderPlaygroundTest*' -Dtest.integration.ide.product=rider` |

To create a playground for another IDE, copy `RiderPlaygroundTest.kt` and change the
`IdeProduct` and `consoleTitle`.

## How Rider Test Execution Was Discovered

This documents the experimental process used to find the correct APIs for running
.NET tests in Rider, as a reference for similar investigations with other IDEs.

### Problem

The existing test infrastructure used JUnit-specific APIs (`JUnitConfiguration`,
`JUnitConfigurationType`, `SMTRunnerConsoleView`) for running and inspecting tests.
These classes do not exist in Rider. We needed to find Rider's native test execution
mechanism.

### Step 1: Research in IntelliJ Source

Searched `~/Work/intellij` for Rider's unit test implementation:

- Found `RiderUnitTesting.xml` in `rider/resources/META-INF/` — registers all action IDs
- Found action classes in `rider/src/com/jetbrains/rider/unitTesting/actions/`
- Key actions: `RiderUnitTestRunContextAction`, `RiderUnitTestDebugContextAction`
- These extend `RiderAnAction` which dispatches to the ReSharper backend via the RD protocol

Key finding: Rider's test runner is fundamentally different from IDEA's. Tests are discovered
and executed by the ReSharper backend (C#), not by the IntelliJ frontend (Java/Kotlin).
The frontend actions just serialize the editor context and send it to the backend.

### Step 2: Start the Playground

```bash
./gradlew :test-experiments:test --tests '*RiderPlaygroundTest*' \
  -Dtest.integration.ide.product=rider
```

Waited for `session-info.txt` to appear with the MCP URL.

### Step 3: Discover Available Actions

Used `steroid_action_discovery` with the caret on the test class declaration:

```bash
curl -s -X POST http://localhost:55929/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
    "name":"steroid_action_discovery",
    "arguments":{
      "project_name":"DemoRider",
      "file_path":"DemoRider.Tests/LeaderboardTests.cs",
      "caret_offset":660
    }
  }}'
```

Result confirmed `RiderUnitTestRunContextAction` and `RiderUnitTestDebugContextAction`
are present and enabled in the editor popup menu at offset 660 (`class LeaderboardTests`).

### Step 4: Execute Run Action

Used `steroid_execute_code` to open the test file, position the caret, and fire the action:

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// Open test file
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance()
    .refreshAndFindFileByPath(basePath + "/DemoRider.Tests/LeaderboardTests.cs")
    ?: error("Test file not found")

val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
    ?: error("No text editor")
val editor = textEditor.editor

// Position caret on test class
val text = editor.document.text
val classOffset = text.indexOf("class LeaderboardTests")
withContext(Dispatchers.EDT) {
    editor.caretModel.moveToOffset(classOffset + 6)
}

// Fire the action
val action = ActionManager.getInstance().getAction("RiderUnitTestRunContextAction")
    ?: error("Action not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(
        dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null
    )
    ActionUtil.performAction(action, event)
}
println("Tests started")
```

### Step 5: Verify Results

Took a screenshot — Rider's Unit Test tool window appeared at the bottom showing
test results with failures (the intentional bug).

Checked `RunContentManager.getInstance(project).allDescriptors` — returned 0 descriptors.
This confirmed that Rider does NOT use the standard `RunContentManager` / `SMTRunnerConsoleView`
infrastructure. Test results live in Rider's own unit test session model.

### Step 6: Verify Debug Action

Ran the same pattern with `RiderUnitTestDebugContextAction`. It worked — the action
was accepted and executed. Without breakpoints set, the debug session ran to completion
immediately (`XDebuggerManager.debugSessions` was empty after), confirming tests executed.

### Key Findings

1. **Action pattern works**: Open file → position caret → get DataContext from
   `editor.contentComponent` → create `AnActionEvent` → `ActionUtil.performAction()`
2. **Context matters**: The caret must be on a test class or method for the action
   to know which tests to run
3. **No RunContentManager**: Rider test results are NOT accessible via the standard
   `RunContentManager` / `SMTRunnerConsoleView` APIs
4. **XDebugger APIs work**: `XDebuggerManager`, `XDebuggerUtil`, breakpoints, and
   expression evaluation all work identically in Rider (platform-level APIs)
5. **AnActionEvent.createFromAnAction is deprecated**: Use `AnActionEvent.createEvent()`
   with `ActionUiKind.NONE` instead

### Product Conditionals

To provide Rider-specific guidance in MCP resources, we use runtime conditionals:

```markdown
###_IF_RIDER_###
Rider-specific content here (uses RiderUnitTestRunContextAction)
###_ELSE_###
IDEA-specific content here (uses JUnitConfiguration)
###_END_IF_###
```

These are processed at runtime in `ResourceRegistrar.kt` based on `ApplicationInfo.build.productCode`.
Conditionals must be in the article body, never in the header (title/description).

## Rider/.NET Test Execution — Quick Reference

**Run tests from editor context:**
1. Open test `.cs` file with `FileEditorManager.openFile()`
2. Position caret on test class/method with `editor.caretModel.moveToOffset()`
3. Get DataContext: `DataManager.getInstance().getDataContext(editor.contentComponent)`
4. Fire action: `ActionUtil.performAction(action, event)` with `RiderUnitTestRunContextAction`

**Debug tests from editor context:**
Same as above but use `RiderUnitTestDebugContextAction`.
Set breakpoints first via `XDebuggerUtil.toggleLineBreakpoint()`.

**Action IDs:**
| Action ID | Purpose |
|---|---|
| `RiderUnitTestRunContextAction` | Run tests at caret |
| `RiderUnitTestDebugContextAction` | Debug tests at caret |
| `RiderUnitTestRunSolutionAction` | Run all tests in solution |

**What does NOT work in Rider:**
- `JUnitConfiguration` / `JUnitConfigurationType` — Java-only
- `ApplicationConfiguration` / `ApplicationConfigurationType` — Java-only
- `RunContentManager.allDescriptors` — empty for Rider test runs
- `SMTRunnerConsoleView` — not used for .NET tests
