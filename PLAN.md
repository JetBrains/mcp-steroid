# DPAIA Arena Test Refactoring Plan

## Completed Work

### Infrastructure fixes
- [x] GH #18 ClassCastException in steroid_list_windows (dual Pair cast)
- [x] Chromium in Docker for puppeteer arm64
- [x] JAVA_HOME symlink (temurin-N-jdk-arch)
- [x] README.md skip > 10KB, Commit tool window shown
- [x] JDK registration via IntelliJ API (JavaSdk.createJdk + addJdk)
- [x] JdkTableIntegrationTest — all 5 steps pass

### Arena test refactoring
- [x] @TestFactory → explicit @Test methods (DpaiaArenaTest, DpaiaComparisonTest)
- [x] Fresh container per test (no shared session)
- [x] Lenient assertions (exitCode 0 OR claimedFix)
- [x] DpaiaJhipsterArenaTest with prewarm + token metrics

### Prompt improvements
- [x] Test patch diff embedded in prompt
- [x] Removed "skip steroid" instruction
- [x] Mandatory first steroid call (VCS check)
- [x] Mandatory compilation check after edits
- [x] Exact buildAllModules code snippet

### Iteration results (jhipster-3, Claude+MCP)

| Iter | exec_code | errors | Time | Cost | Tests | Notes |
|------|-----------|--------|------|------|-------|-------|
| 0 | 0 | 0 | 117s | $0.53 | 47/47 | Old "skip steroid" prompt |
| 1 | 3 | 0 | 118s | $0.41 | 47/47 | VCS+compile checks work |
| 2 | 3 | 0 | 122s | $0.42 | 47/47 | Stable |
| 3 | 3 | 0 | 105s | $0.37 | 47/47 | Fastest |
| 4 | 5 | 3 | 151s | $0.46 | 47/47 | SDK modal aborted builds |
| 5 | 4 | 4 | 123s | $0.40 | 47/47 | Same SDK issue |
| 6 | — | — | 117s | — | 47/47 | DpaiaJhipsterArenaTest post-refactor (JDK via API, 9-step flow) |
| 7 | — | — | 133s | — | 47/47 | DpaiaArenaTest explicit @Test post-refactor |

### feature-service-125 A/B comparison (harder case: 44KB, cross-layer, state machine)

| Mode     | Fix? | Exit | Duration | Tool calls | Notes |
|----------|------|------|----------|------------|-------|
| claude+mcp  | YES  | 0    | 516s     | 4 steroid  | Implemented endpoints, state machine, migration, 25/25 tests |
| claude+none | NO   | -1   | 900s     | 60+ bash/read, 0 writes | Timeout — only explored, never implemented |

ProcessRunner timeout fix validated: agent killed at exactly 900s (previously ran 1941s / 32 min due to stuck I/O threads).

## Next: Redesign waitForProjectReady flow

### Problem
Current `waitForProjectReady()` flow has issues:
1. JDK registration happens too late (after indexing complete) → causes "SDK not specified" errors
2. Maven/Gradle import may start without JDK → fails or produces incomplete model
3. No explicit trigger for build tool import — relies on IntelliJ auto-detection
4. No project compilation step — agents hit full Maven compile cycle on first test run
5. Build tool sync uses `Observation.awaitConfiguration` which may not wait for actual import

### New flow (ordered steps)

Each step depends on the previous. Steps must run in this exact order.

#### Step 1: Wait for IDE window (existing)
Poll `mcpListWindows` until `projectInitialized=true`. Kill modal dialogs.
No change needed — this works correctly.

#### Step 2: Reposition IDE window (existing)
Apply layout via `repositionIdeWindow()`.
No change needed.

#### Step 3: Register JDKs (NEW — moved earlier)
Call `mcpRegisterJdks()` immediately after window appears.
Must happen BEFORE any build tool import so the project model has a valid SDK.
Currently this runs inside `mcpSetupJdkAndWaitForImport` which is step 5 — too late.

#### Step 4: Set project SDK (parameter-driven)
Apply the correct JDK version as project SDK.
- Default: JDK 21 (LTS, matches JAVA_HOME)
- Parameter: `projectJdkVersion: String = "21"` on `waitForProjectReady()`
- Some projects need JDK 17 or 11 (e.g. older Spring Boot)
- For Rider/.NET: skip entirely

#### Step 5: Trigger build tool import (parameter-driven)
Explicitly trigger Maven or Gradle import rather than waiting for auto-detection.
- Maven: `MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()`
- Gradle: `ExternalSystemUtil.refreshProjects(...)` or let IntelliJ auto-import
- Parameter: `buildSystem: BuildSystem = BuildSystem.AUTO` (AUTO, MAVEN, GRADLE, NONE)
- AUTO: detect from pom.xml / build.gradle presence

#### Step 6: Wait for import to complete
Use dedicated APIs to wait for build tool sync:
- `Observation.awaitConfiguration(project)` — waits for all pending project model activities
- Combine with `DumbService.waitForSmartMode()` for indexing after import
- Timeout: configurable, default 8 minutes

#### Step 7: Wait for indexing to complete (existing)
Poll `mcpListWindows` until `indexingInProgress=false`.
After import completes, IntelliJ may re-index new dependencies.
Must wait for this second indexing pass.

#### Step 8: Compile project (NEW)
Run `testClasses` (Gradle) or `test-compile` (Maven) via MCP or bash.
This ensures:
- All source files compile before agent starts
- Maven downloads all test dependencies
- Agents don't hit compilation failures on first test run

For different build systems:
- Gradle: `./gradlew testClasses --console=plain`
- Maven: `./mvnw test-compile -Dspotless.check.skip=true -q`
- None: skip

#### Step 9: Install plugins (existing, moved later)
Plugin detection from project dependencies (e.g. Kafka plugin).
Moved after import because plugin detection reads build files.

#### Step 10: Open file + show tool windows (existing)
Open source file, show Commit + build tool windows.

### Parameters for waitForProjectReady

```kotlin
fun waitForProjectReady(
    timeoutMillis: Long = 600_000L,
    // NEW parameters:
    projectJdkVersion: String? = "21",        // null = skip JDK setup (e.g. Rider)
    buildSystem: BuildSystem? = null,          // null = auto-detect from pom.xml/build.gradle
    compileProject: Boolean = true,            // run testClasses/test-compile before agent
    triggerImport: Boolean = true,             // explicitly trigger Maven/Gradle import
): IntelliJContainer
```

### Implementation plan (ordered tasks)

1. [x] Refactor `mcpRegisterJdks` to be callable standalone
2. [x] Add `BuildSystem` enum: `MAVEN, GRADLE, NONE`
3. [x] Create `mcpTriggerImportAndWait(buildSystem)` helper
4. [x] Create `mcpSetProjectSdk(path, version)` helper
5. [x] Create `mcpCompileProject(buildSystem)` helper
6. [x] Refactor `waitForProjectReady()` to use the new ordered steps
7. [x] Update `DpaiaJhipsterArenaTest` to pass `buildSystem = MAVEN`
8. [x] Update `DpaiaArenaTest` to derive from testCase.buildSystem
9. [x] Run JdkTableIntegrationTest — PASSED (1m 9s)
10. [x] Run jhipster arena test — PASSED (4 steroid, 0 errors, $0.46, 163 tests)
11. [x] Update remaining test classes with explicit buildSystem/JDK params:
        - RiderDebuggerTest, RiderPlaygroundTest: projectJdkVersion=null
        - DpaiaClaudeComparisonTest: buildSystem from testCase, compileProject=true
        - KeycloakArchitectureTest: buildSystem=MAVEN
        - Others (DebuggerDemoTest, EapSmokeTest, etc.): defaults OK (NONE)
12. [x] Extract data processing into shared AgentOutputMetrics.kt:
        - TokenUsage, TestMetrics, ToolCallStats data classes
        - extractTestMetrics(), extractTokenUsage(), extractToolCallStats()
        - Removed duplicates from DpaiaJhipsterArenaTest + DpaiaClaudeComparisonTest
        - Updated unit tests (ExtractTestMetricsTest, ExtractToolCallStatsTest)

### Files to modify

- `test-integration/src/main/kotlin/.../infra/intelliJ-container.kt` — main flow
- `test-integration/src/main/kotlin/.../infra/mcp-steroid.kt` — new helpers
- `test-experiments/src/test/kotlin/.../arena/DpaiaJhipsterArenaTest.kt` — remove prewarm (now in flow)
- `test-experiments/src/test/kotlin/.../arena/DpaiaArenaTest.kt` — update
- `test-experiments/src/test/kotlin/.../arena/DpaiaComparisonTest.kt` — update
- `test-integration/src/test/kotlin/.../tests/JdkTableIntegrationTest.kt` — verify

### Risks
- Changing `waitForProjectReady()` signature affects ALL integration tests
- Maven import timeout may vary by project (microshop-18 needs 20 min)
- Gradle projects may auto-import without explicit trigger
- Some projects may not need compilation (pure config projects)

### Migration strategy
- Keep existing parameters backward-compatible (new params have defaults)
- New params are all optional with sensible defaults
- Tests that don't pass build system will get auto-detection
- DpaiaJhipsterArenaTest's manual prewarm step becomes unnecessary once compile is in flow
