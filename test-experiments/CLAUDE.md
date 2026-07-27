# test-experiments — Agent Guide

**Experimental / long-running / less stable** Docker-based tests. Read this **in addition to** the
root `CLAUDE.md` and `test-integration/AGENTS.md` (the shared infrastructure lives there) when
changing files under `test-experiments/`.

The three repo-wide design tenets ([`docs/PHILOSOPHY.md`](../docs/PHILOSOPHY.md);
runtime mirror `mcp-steroid://skill/design-philosophy`) apply here too —
arena prompts and debugger demos are exactly the surface that "agents
deliver more" measurements run against, so prompt-quality wins land here.
New `steroid_*` tools and new `McpScriptContext` methods do not.

## What lives here

- `DpaiaArenaTest` and the DPAIA arena instance suite — agentic task scenarios.
- `DebuggerDemoTest`, `RiderDebuggerTest` — debugger-driven prompt tests.
- All long-running prompt-quality comparisons.

## What does NOT live here (despite the name)

The following tests are in `:test-integration` even though they look "experimental":

- `PluginBuildCompatibilityTest`, `PluginRuntimeCompatibilityTest`, `PluginVerificationTest`
  (multi-version compat suite).
- `RiderPlaygroundTest`, other playground tests.
- `FindDuplicatesPromptTest` and similar prompt smoke tests.

For multi-version compat, playgrounds, Rider/.NET test execution, and Docker-test CI gotchas, see
`test-integration/AGENTS.md`.

## Running

Always `:test-experiments:` prefixed and **one at a time** — each test spins up a full Docker IntelliJ
container. Two concurrent runs OOM-kill both. See root `CLAUDE.md` → "Test execution discipline" for
the 1-minute rule and stuck-test debugging.

```bash
./gradlew :test-experiments:test --tests '*DebuggerDemoTest.claude*'
./gradlew :test-experiments:test --tests '*DpaiaArenaTest*' -Darena.test.instanceId=dpaia__empty__maven__springboot3-3
./gradlew :test-experiments:test --tests '*RiderDebuggerTest*'
```

`:test-experiments:test` has an `onlyIf` guard — root `./gradlew test` silently skips it. Direct
invocation still works. Depends on `:test-integration` for the shared infrastructure (`IdeContainer`,
`ConsoleDriver`, `XcvbDriver`, `AiAgentDriver`, `ConsolePumpingContainerDriver`).

## Remote-debugging (shared with :test-integration)

Because the infra is shared, every `:test-experiments` Docker IDE also starts with a JDWP agent on
`IDE_DEBUG_PORT` (5005), and devrig stdio-bridge runs add one on `DEVRIG_DEBUG_PORT` (5006) — both
Docker-mapped to host ports printed on the host (`Listening for transport dt_socket at address:
<host-port>` + `session-info.txt`). Attach IntelliJ's "Remote JVM Debug" to step through a debugger
demo or arena run live. **Both agents are `suspend=n` and MUST stay that way** — `suspend=y` would
block the JVM until a debugger attaches, hanging the whole test on CI. Full workflow + the attach
recipe (`mcp-steroid://debugger/debug-attach-remote-jvm`): `test-integration/AGENTS.md` →
"Remote-debugging the Dockerized IDE".

## Arena experiments (DPAIA)

Run AI agents in Docker against curated tasks; measure tool calls, tokens, runtime, success.

```bash
# Single scenario (~5 min)
./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks

# Full 3-pass run
SKIP_IMPROVE=1 MAX_RUNS=1 bash ../docs/dpaia-arena-runner.sh 0
```

Working notes, comparison tables, and autoresearch loop prompts live in `../docs/CLAUDE.md` and
`../docs/autoresearch/`.

**Four-arm scenarios and objective verification.** Each scenario test class carries four arms: `claude with mcp`
(runs the agent against the live `steroid_*` MCP API), `claude with mcp slim` (same as `mcp`, but the container
serves the slim `steroid_execute_code` tool description), `claude with devrig` (same agent, devrig stdio protocol),
and `claude without mcp` (shell-only baseline — agent uses bash, cat, find, grep, ./mvnw; no steroid_* tools, no IDE APIs).
After each agent run, the harness re-executes FAIL_TO_PASS test classes via Maven and grades from surefire XML
(Gradle cases currently skip verification), using fields like `verified_ftp_rate` and `tests_tampered` in the run JSON/CSV.
The `dpaia__feature__service-125x` case runs with a local overlay test patch and is reported separately from the base scenario.

**How the `mcp-slim` arm varies the tool description.** One plugin ZIP is deployed per run, so the variant
cannot be a build-time choice: `ExecCodeDescriptionVariant` (in `:mcp-steroid-server`, so both the in-IDE
server and devrig resolve it the same way) reads `MCP_STEROID_EXEC_CODE_DESCRIPTION` per process, defaulting
to `full` — the repo default is unchanged, slim is opt-in. `ArenaMode` maps each arm to a variant and passes
it through `IntelliJContainerOpts.extraEnv`, explicitly for every arm including the `full` ones, so a row's
variant is configuration rather than an assumption. Before spending any API budget, each arm reads the served
description back over `tools/list` (`McpSteroidDriver.mcpToolDescription`) and fails if the variant's marker
is missing; its length is recorded as `exec_description_chars` next to `exec_description_variant` in the run
JSON and `arena-comparison.csv`, so token counts can be read against the tool-definition cost they include.
The env-var → IDE-JVM plumbing has its own agent-free Docker check in
`:test-integration` — `ExecCodeDescriptionVariantIntegrationTest` (two containers, empty project, no API spend).

## IMPROVEMENTS.md harness — agent self-feedback for prompt tuning

Pattern used by `FindDuplicatesPromptTest` (issue #33; lives in `:test-integration`). Reusable in any
agent-driven prompt test on either side of the split. Goal: capture the agent's own reflection on what
was hard / unclear / missing during the run, in a form a maintainer can diff and turn into prompt tweaks.

**Two tasks per run, one prompt.** The prompt asks the agent to (1) do the real work and (2) reflect on
how it went. Reflection is bracketed by `<<<IMPROVEMENTS>>>` ... `<<<END_IMPROVEMENTS>>>` markers so the
test can extract it without parsing the rest of stdout.

**Snapshot per agent.** Each `@Test` method (one per agent — `claude`, `codex`, `gemini`) runs against a
shared companion-object IDE container and writes its block to
`test-integration/build/improvements/IMPROVEMENTS-<test>-<agent>.md`. JUnit serializes `@Test` methods
within a class, so the three agents run sequentially against one container — satisfying the "one Docker
IDE at a time" rule without paying the IDE startup cost three times.

**Hard constraint stated in the prompt.** Agents are told: *"your suggestions must be about prompts only —
skill articles, tool descriptions, system-prompt text. We cannot add MCP tools or API methods as a fix
path."* This makes the feedback actionable as `mcp-steroid://...` edits.

**Iteration cadence.** After a run, read every produced `IMPROVEMENTS-*.md`, pick the prompt-only tweaks
that match the constraint, apply them, re-run. Different agents flag different things — Claude tends to
highlight discovery and threading issues; Codex tends to highlight ambiguity in step ordering and
output-format expectations.

The harness is currently wired into `FindDuplicatesPromptTest`. Extending to the rest of the
test-integration prompt suite (`ReferencesSearchPromptTest`, `FilenameIndexPromptTest`,
`PsiClassLookupPromptTest`, `MavenRunnerAdoptionTest`, …) is tracked separately so the pattern can
stabilize on one test first.
