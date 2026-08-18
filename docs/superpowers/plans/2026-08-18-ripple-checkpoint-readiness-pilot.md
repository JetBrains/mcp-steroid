# Ripple checkpoint solution-readiness pilot — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure `V(s_i)` — the probability that a bare Haiku probe finishes the
`rename-method-wide` keycloak ripple task from five intermediate states of one Opus trajectory — for
both the `mcp` and the `none` arm, and report the curve plus its AUC.

**Architecture:** One extra Opus run per arm counts tool calls in a Claude Code `PostToolUse` hook and
snapshots the repository into a shadow git dir at exactly five precomputed positions
`a_i = round(n̂·(i/6)^1.5)`, where `n̂` is the historical mean step count of that arm (`mcp` 32,
`none` 40) — snapshotting every step would put tens of full Keycloak tree scans inside the measured
agent loop. A probe build recreates the pristine container, captures gold on the
pristine tree, applies one checkpoint diff, and runs bare Haiku on the blind continuation prompt; the
existing ripple grading decides `Y ∈ {0,1}`. An aggregator turns the 50 verdicts into `V(s_i)` and the
trapezoidal AUC over the observed range.

**Tech Stack:** Kotlin 2.3.20 / JUnit 5 / `:test-experiments` Docker IDE harness / Claude Code CLI
2.1.159 in-container / TeamCity `mcp_steroid_IntegrationTests`.

Design: `docs/superpowers/specs/2026-08-18-ripple-checkpoint-readiness-pilot-design.md`.

**Amendment (after Task 1 landed).** Everything below derives the checkpoint positions per arm, from
each arm's own historical mean step count (`mcp` 32 → 2, 6, 11, 17, 24; `none` 40 → 3, 8, 14, 22, 30).
That was wrong: `V_mcp` and `V_shell` are only comparable when both are measured after the SAME number
of tool calls, so a per-arm schedule would compare readiness after 24 mcp calls against readiness after
30 shell calls and call the difference an arm effect. The implemented code uses one shared schedule —
`RIPPLE_CHECKPOINT_STEPS = 2, 6, 11, 17, 24` from the scalar `RIPPLE_EXPECTED_STEPS = 32`, the deepest
value the shorter arm's admission band can reach. The sketches below are left as written so the change
stays readable; where they disagree with the code, the code is right.

## Global Constraints

- Branch `worktree-semantic-ripple-pilot`; all code lives in this worktree.
- Case: `RippleCases.renameMethodWide`. Agent: `claude`. Capture model: `claude-opus-5`.
  Probe model: `claude-haiku-4-5`.
- Checkpoint positions are precomputed from `n̂`: `mcp` → `2, 6, 11, 17, 24` (`n̂ = 32`);
  `none` → `3, 8, 14, 22, 30` (`n̂ = 40`). Reports normalize by the capture run's ACTUAL `n`.
- Probe arms are ALWAYS `withMcp = false`, including probes of the `mcp` capture.
- Probe prompt = shell-variant ripple prompt + exactly one fixed continuation paragraph. No checkpoint
  index, step count, percentage, arm name, or summary of prior actions may reach the probe.
- Agent budget identical in all runs: `SemanticRippleSpec.agentTimeoutSeconds` (90 min), no turn cap.
- Never weaken or skip grading. Instrument failures must be reported as LOST, never as `Y = 0`.
- No `internal` modifier, no `runCatching{}.onFailure{}`, no empty catch, no `@Suppress("DEPRECATION")`.
- Scope Gradle invocations: `./gradlew :test-experiments:test --tests '<pattern>'`. Never run
  `:test-experiments:test` twice concurrently.

---

### Task 1: Checkpoint positions, `V`, and AUC

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointMath.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointMathTest.kt`

**Interfaces:**
- Produces:
  - `fun rippleCheckpointSteps(n: Int, count: Int = 5): List<Int>`
  - `fun checkpointReadiness(successes: Int, runs: Int): Double`
  - `data class ReadinessPoint(val position: Double, val readiness: Double)`
  - `data class ReadinessCurve(val points: List<ReadinessPoint>) { val auc: Double; val rangeFrom: Double; val rangeTo: Double }`

- [ ] **Step 1: Write the failing test**

```kotlin
class RippleCheckpointMathTest {
    @Test
    fun `positions follow the 1_5 power schedule`() {
        assertEquals(listOf(2, 6, 11, 17, 24), rippleCheckpointSteps(32))
        assertEquals(listOf(3, 8, 14, 22, 30), rippleCheckpointSteps(40))
    }

    @Test
    fun `the pilot's arms use the v3 mean step counts`() {
        assertEquals(listOf(2, 6, 11, 17, 24), rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS.getValue("mcp")))
        assertEquals(listOf(3, 8, 14, 22, 30), rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS.getValue("none")))
    }

    @Test
    fun `short trajectories are nudged into a strictly increasing sequence`() {
        val steps = rippleCheckpointSteps(8)
        assertEquals(steps.sorted(), steps)
        assertEquals(steps.distinct(), steps)
        assertTrue(steps.all { it in 1..7 }) { "no checkpoint may be the final state: $steps" }
    }

    @Test
    fun `every trajectory length yields five distinct in-range checkpoints`() {
        (6..100).forEach { n ->
            val steps = rippleCheckpointSteps(n)
            assertEquals(5, steps.size, "n=$n")
            assertEquals(steps.distinct(), steps, "n=$n -> $steps")
            assertEquals(steps.sorted(), steps, "n=$n -> $steps")
            assertTrue(steps.last() < n, "n=$n -> $steps")
        }
    }

    @Test
    fun `n below six cannot carry five distinct checkpoints`() {
        assertThrows(IllegalArgumentException::class.java) { rippleCheckpointSteps(5) }
    }

    @Test
    fun `readiness is the success fraction`() {
        assertEquals(0.0, checkpointReadiness(0, 5))
        assertEquals(0.4, checkpointReadiness(2, 5))
        assertEquals(1.0, checkpointReadiness(5, 5))
    }

    @Test
    fun `auc integrates only the observed range`() {
        val curve = ReadinessCurve(listOf(
            ReadinessPoint(0.1, 0.0),
            ReadinessPoint(0.2, 0.5),
            ReadinessPoint(0.5, 1.0),
        ))
        // trapezoids: (0+0.5)/2*0.1 + (0.5+1.0)/2*0.3 = 0.025 + 0.225
        assertEquals(0.25, curve.auc, 1e-9)
        assertEquals(0.1, curve.rangeFrom, 1e-9)
        assertEquals(0.5, curve.rangeTo, 1e-9)
    }

    @Test
    fun `auc refuses to extrapolate a single point`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadinessCurve(listOf(ReadinessPoint(0.1, 1.0))).auc
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointMathTest*'`
Expected: FAIL — unresolved reference `rippleCheckpointSteps`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
/**
 * Where along a trajectory of [n] tool calls the readiness probes start.
 *
 * `round(n·(i/6)^1.5)` puts the five checkpoints at ≈7/19/35/54/76% — dense at the beginning, where a
 * trajectory changes the most per step, and still reaching deep into the solution. The final state is
 * deliberately excluded: the source run's own outcome is a separate fact, not a probe point.
 *
 * Rounding collides on short trajectories, and a collision would silently measure the same state
 * twice, so a duplicate is pushed up by the smallest amount that keeps the sequence strictly
 * increasing and below [n].
 */
fun rippleCheckpointSteps(n: Int, count: Int = 5): List<Int> {
    require(n > count) { "a trajectory of $n steps cannot carry $count distinct pre-final checkpoints" }
    val raw = (1..count).map { i ->
        Math.round(n * Math.pow(i.toDouble() / (count + 1), 1.5)).toInt().coerceAtLeast(1)
    }
    val fixed = mutableListOf<Int>()
    raw.forEach { candidate ->
        val previous = fixed.lastOrNull() ?: 0
        fixed += maxOf(candidate, previous + 1)
    }
    require(fixed.last() < n) { "checkpoints $fixed reach the final state of a $n-step trajectory" }
    return fixed
}

/**
 * The step count a capture run of each arm is EXPECTED to have, rounded from the v3 sample means
 * (mcp 31.6, none 39.9). Checkpoint positions are derived from these before the run, so the hook can
 * snapshot five states instead of every one of them.
 */
val RIPPLE_EXPECTED_STEPS: Map<String, Int> = mapOf("mcp" to 32, "none" to 40)

fun checkpointReadiness(successes: Int, runs: Int): Double {
    require(runs > 0) { "a readiness value needs at least one run" }
    require(successes in 0..runs) { "$successes successes out of $runs runs" }
    return successes.toDouble() / runs
}

data class ReadinessPoint(val position: Double, val readiness: Double)

/**
 * The measured readiness curve and its area, integrated ONLY between the first and the last
 * checkpoint. Nothing is extrapolated to 0 or past the last checkpoint: readiness there was not
 * measured, and an implied value would be the loudest number in the report.
 */
data class ReadinessCurve(val points: List<ReadinessPoint>) {
    val rangeFrom: Double get() = ordered.first().position
    val rangeTo: Double get() = ordered.last().position

    val auc: Double
        get() {
            require(ordered.size >= 2) { "an area needs at least two measured checkpoints" }
            return ordered.zipWithNext().sumOf { (a, b) ->
                (a.readiness + b.readiness) / 2.0 * (b.position - a.position)
            }
        }

    private val ordered: List<ReadinessPoint> get() = points.sortedBy { it.position }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointMathTest*'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointMath*.kt
git commit -m "test-experiments: checkpoint positions, readiness and observed-range AUC"
```

---

### Task 2: Blind continuation prompt

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointProbePrompt.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointProbePromptTest.kt`

**Interfaces:**
- Consumes: `buildRipplePrompt(case: RippleCase, projectDir: String, withMcp: Boolean): String` (existing).
- Produces: `const val CHECKPOINT_CONTINUATION_PARAGRAPH`, `fun buildCheckpointProbePrompt(case: RippleCase, projectDir: String): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
class RippleCheckpointProbePromptTest {
    private val case = RippleCases.renameMethodWide

    @Test
    fun `probe prompt is the shell prompt plus one continuation paragraph`() {
        val probe = buildCheckpointProbePrompt(case, "/work/keycloak")
        val shell = buildRipplePrompt(case, "/work/keycloak", withMcp = false)
        assertEquals(CHECKPOINT_CONTINUATION_PARAGRAPH + "\n\n" + shell, probe)
        assertEquals(1, Regex(Regex.escape(CHECKPOINT_CONTINUATION_PARAGRAPH)).findAll(probe).count())
    }

    @Test
    fun `probe prompt leaks neither the checkpoint nor the source arm`() {
        val probe = buildCheckpointProbePrompt(case, "/work/keycloak").lowercase()
        listOf("checkpoint", "step 1", "steps", "% of", "mcp", "steroid", "intellij", "opus",
               "previous agent", "another agent", "trajectory")
            .forEach { leak -> assertFalse(probe.contains(leak)) { "probe prompt leaks '$leak'" } }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointProbePromptTest*'`
Expected: FAIL — unresolved reference `buildCheckpointProbePrompt`. If the leak test fails on a word
the SHELL prompt itself contains (e.g. `intellij` in a path), narrow the banned list to words the
continuation paragraph could add and record why in a comment — never by deleting the assertion.

- [ ] **Step 3: Write minimal implementation**

```kotlin
/**
 * The only sentence a probe learns about the state it inherits.
 *
 * Blindness is the experiment: the probe must not know which arm produced the state, how far along
 * the trajectory it is, or what was tried. Anything beyond "this is mid-attempt, continue" would let
 * the probe's own competence be confused with a hint from the harness.
 */
const val CHECKPOINT_CONTINUATION_PARAGRAPH: String =
    "You are given an intermediate state of an ongoing attempt to solve this task. Some " +
        "investigation and/or modifications may already have been performed. Continue from the " +
        "current repository state and complete the original task."

fun buildCheckpointProbePrompt(case: RippleCase, projectDir: String): String =
    CHECKPOINT_CONTINUATION_PARAGRAPH + "\n\n" + buildRipplePrompt(case, projectDir, withMcp = false)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointProbePromptTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointProbePrompt*.kt
git commit -m "test-experiments: blind continuation prompt for checkpoint probes"
```

---

### Task 3: `--settings` seam on the Claude session

**Files:**
- Modify: `test-helper/src/main/kotlin/com/jonnyzzz/mcpSteroid/testHelper/DockerClaudeSession.kt:119-154`
- Create: `test-helper/src/main/kotlin/com/jonnyzzz/mcpSteroid/testHelper/ClaudePromptArgs.kt`
- Test: `test-helper/src/test/kotlin/com/jonnyzzz/mcpSteroid/testHelper/ClaudePromptArgsTest.kt`

**Interfaces:**
- Produces:
  - `fun claudeRunPromptArgs(model: String, mcpConfigFile: String?, settingsFile: String?, prompt: String): List<String>`
  - `fun DockerClaudeSession.useSettings(settingsJson: String)` — writes the file into the container and
    makes every later `runPrompt` pass `--settings <file>`.

- [ ] **Step 1: Write the failing test**

```kotlin
class ClaudePromptArgsTest {
    @Test
    fun `settings file is passed only when configured`() {
        val without = claudeRunPromptArgs("claude-opus-5", null, null, "hi")
        assertFalse(without.contains("--settings"))

        val with = claudeRunPromptArgs("claude-opus-5", null, "/tmp/s.json", "hi")
        assertEquals(listOf("--settings", "/tmp/s.json"), with.zipWithNext()
            .first { it.first == "--settings" }.let { listOf(it.first, it.second) })
    }

    @Test
    fun `prompt stays the last argument so nothing can be parsed as a flag`() {
        val args = claudeRunPromptArgs("claude-haiku-4-5", "/tmp/mcp.json", "/tmp/s.json", "--do-things")
        assertEquals("--do-things", args.last())
        assertEquals("-p", args[args.size - 2])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-helper:test --tests '*ClaudePromptArgsTest*'`
Expected: FAIL — unresolved reference `claudeRunPromptArgs`.

- [ ] **Step 3: Write minimal implementation**

Extract the existing `buildList` of `DockerClaudeSession.runPrompt` verbatim into
`claudeRunPromptArgs`, adding the two settings arguments right before `-p`. Then in
`DockerClaudeSession`: a `private var settingsFile: String? = null`, a `fun useSettings(settingsJson: String)`
that calls `session.writeFileInContainer("/tmp/claude-settings.json", settingsJson)` and stores the
path, and a `runPrompt` that delegates to `claudeRunPromptArgs(model, mcpConfigFile?.let { … },
settingsFile, prompt)`. Keep the existing Windows-quoting comment on the MCP config file — it explains
why the config is a file and not inline JSON, and the same reasoning is why settings are a file too.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :test-helper:test --tests '*ClaudePromptArgsTest*'`
Then the existing regression: `./gradlew :test-helper:test`
Expected: PASS, no other test changed behaviour.

- [ ] **Step 5: Commit**

```bash
git add test-helper/src/main/kotlin/com/jonnyzzz/mcpSteroid/testHelper/ClaudePromptArgs.kt \
        test-helper/src/main/kotlin/com/jonnyzzz/mcpSteroid/testHelper/DockerClaudeSession.kt \
        test-helper/src/test/kotlin/com/jonnyzzz/mcpSteroid/testHelper/ClaudePromptArgsTest.kt
git commit -m "test-helper: let a Claude session run under an explicit settings file"
```

---

### Task 4: Per-step recorder (shadow git + hook)

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointRecorder.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointRecorderTest.kt`

**Interfaces:**
- Consumes: `ContainerDriver`, `startProcessInContainer`, `writeFileInContainer`, `DockerClaudeSession.useSettings`.
- Produces:
  - `fun checkpointHookScript(gitDir: String, workTree: String, counterFile: String, targetSteps: List<Int>): String`
  - `fun checkpointHookSettingsJson(scriptPath: String): String`
  - `class RippleCheckpointRecorder(container: ContainerDriver, projectDir: String, targetSteps: List<Int>, gitDir: String = "/checkpoints/.git")`
    with `fun install(claude: DockerClaudeSession)`, `fun stepCount(): Int`,
    `fun exportPatch(step: Int): String`, `fun exportMetadata(nActual: Int): String`,
    and `companion object { fun metadataJson(case: String, arm: String, model: String, expectedSteps: Int, actualSteps: Int, steps: List<Int>): String }`.

- [ ] **Step 1: Write the failing test** (pure text/JSON shape — the container half is proven by Task 5's preflight)

```kotlin
class RippleCheckpointRecorderTest {
    @Test
    fun `hook script counts every call but commits only at the target steps`() {
        val script = checkpointHookScript(
            gitDir = "/checkpoints/.git", workTree = "/work/keycloak",
            counterFile = "/checkpoints/steps", targetSteps = listOf(2, 6, 11, 17, 24),
        )
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("--git-dir=/checkpoints/.git"))
        assertTrue(script.contains("--work-tree=/work/keycloak"))
        assertTrue(script.contains("/checkpoints/steps")) { "the counter is the source of n" }
        listOf("2", "6", "11", "17", "24").forEach { step ->
            assertTrue(script.contains("step-$step")) { "no tag for step $step" }
        }
        assertFalse(script.contains("git -C /work/keycloak")) { "the project's own .git must stay untouched" }
    }

    @Test
    fun `hook settings register the script for every tool`() {
        val json = Json.parseToJsonElement(checkpointHookSettingsJson("/checkpoints/snapshot.sh")).jsonObject
        val postToolUse = json["hooks"]!!.jsonObject["PostToolUse"]!!.jsonArray
        assertEquals(1, postToolUse.size)
        val entry = postToolUse[0].jsonObject
        assertEquals("*", entry["matcher"]!!.jsonPrimitive.content)
        val hook = entry["hooks"]!!.jsonArray[0].jsonObject
        assertEquals("command", hook["type"]!!.jsonPrimitive.content)
        assertEquals("/checkpoints/snapshot.sh", hook["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `metadata normalizes by the actual step count, not by the assumed one`() {
        val json = Json.parseToJsonElement(
            RippleCheckpointRecorder.metadataJson(
                case = "ripple__keycloak__rename-method-wide", arm = "mcp",
                model = "claude-opus-5", expectedSteps = 32, actualSteps = 29,
                steps = listOf(2, 6, 11, 17, 24),
            )
        ).jsonObject
        assertEquals(32, json["expectedSteps"]!!.jsonPrimitive.int)
        assertEquals(29, json["actualSteps"]!!.jsonPrimitive.int)
        val positions = json["checkpoints"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(2, 6, 11, 17, 24), positions.map { it["step"]!!.jsonPrimitive.int })
        assertEquals(2.0 / 29.0, positions[0]["position"]!!.jsonPrimitive.double, 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointRecorderTest*'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write minimal implementation**

The hook script (kept tiny on purpose — a hook that fails must not kill the run, so it exits 0
unconditionally, and it prints to stderr so a stdout byte can never reach the agent's protocol):

```sh
#!/bin/sh
# Count this tool call; snapshot the work tree only at the five precomputed checkpoint positions.
# Counting is cheap, `add -A` over Keycloak is not: snapshotting every call would put tens of full tree
# scans inside the agent loop this run is supposed to measure faithfully.
# The SHADOW git dir keeps the project's own repository — which the agent may inspect with
# `git status` — untouched by the instrument. Everything goes to stderr: a stray stdout byte would
# corrupt the agent's JSON-RPC channel.
n=$(( $(cat <counterFile> 2>/dev/null || echo 0) + 1 ))
echo "$n" > <counterFile>
case "$n" in
  2|6|11|17|24)
    git --git-dir=<gitDir> --work-tree=<workTree> add -A >&2
    git --git-dir=<gitDir> --work-tree=<workTree> commit --allow-empty -q -m "step-$n" >&2
    git --git-dir=<gitDir> --work-tree=<workTree> tag "step-$n" >&2
    ;;
esac
exit 0
```

The `case` arms are generated from `targetSteps`, joined by `|`. The script exits 0 unconditionally: a
broken hook must not kill a $2 agent run — a missing tag is visible later and is a far cheaper failure.

`install(claude)` writes the script (`chmod +x`), initialises `gitDir` (`git init --bare`, then commit
the work tree through `--work-tree`), seeds `safe.directory` the way `GitDriver.cloneFromCachedBare`
documents, commits the pristine tree as the `step-0` root commit, then calls
`claude.useSettings(checkpointHookSettingsJson(scriptPath))`.

`stepCount()` reads the counter file — that, not the commit count, is the run's `n`.
`exportPatch(step)` = `git --git-dir=… diff step-0 step-<step>`; a missing tag is an error naming the
step, never an empty patch.
`exportMetadata(nActual)` writes the JSON from `metadataJson(...)` into the run dir.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointRecorderTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointRecorder*.kt
git commit -m "test-experiments: shadow-git per-step recorder for ripple checkpoints"
```

---

### Task 5: Capture run (with hook preflight and the representativeness gate)

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCaptureAdmission.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCaptureAdmissionTest.kt`
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/KeycloakRenameMethodWideCheckpointCaptureTest.kt`
- Modify: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleScenarioBaseTest.kt:78-333`

**Interfaces:**
- Consumes: `rippleCheckpointSteps`, `RippleCheckpointRecorder`, `DockerClaudeSession.useSettings`.
- Produces: `data class CaptureReference(val arm: String, val stepsMin: Int, val stepsMax: Int, val stepsMean: Double, val stepsSd: Double, val secondsMean: Double, val secondsSd: Double, val tokensMean: Double, val tokensSd: Double)`,
  `val v3RenameMethodWideReference: Map<String, CaptureReference>`,
  `fun admitCapture(reference: CaptureReference, success: Boolean, steps: Int, seconds: Long, endContextTokens: Long, lastCheckpointStep: Int): CaptureAdmission`
  where `CaptureAdmission` holds `admitted: Boolean` and `reasons: List<String>`.

- [ ] **Step 1: Write the failing admission test**

The reference numbers are the measured v3 series (claude, `rename-method-wide`) and must be written
verbatim: `mcp` — steps 22..41, mean 31.6, sd 7.1; seconds mean 870.6, sd 410.5; end tokens mean
75069.6, sd 11820.4. `none` — steps 31..56, mean 39.9, sd 8.1; seconds mean 749.2, sd 265.3; end
tokens mean 66364.4, sd 7009.0.

```kotlin
class RippleCaptureAdmissionTest {
    private val mcp = v3RenameMethodWideReference.getValue("mcp")

    @Test
    fun `a median-looking successful run is admitted`() {
        val verdict = admitCapture(mcp, true, steps = 30, seconds = 685, endContextTokens = 73019, lastCheckpointStep = 24)
        assertTrue(verdict.admitted) { verdict.reasons.toString() }
    }

    @Test
    fun `a failed capture is rejected however typical its numbers look`() {
        val verdict = admitCapture(mcp, false, steps = 30, seconds = 685, endContextTokens = 73019, lastCheckpointStep = 24)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.any { it.contains("SUCCESS") })
    }

    @Test
    fun `an outlier is rejected and says which metric was out of band`() {
        val verdict = admitCapture(mcp, true, steps = 61, seconds = 685, endContextTokens = 73019, lastCheckpointStep = 24)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.single().contains("steps"))
    }

    @Test
    fun `a run that ended before the last snapshot point is rejected`() {
        val verdict = admitCapture(mcp, true, steps = 23, seconds = 685, endContextTokens = 73019, lastCheckpointStep = 24)
        assertFalse(verdict.admitted)
        assertTrue(verdict.reasons.any { it.contains("24") })
    }

    @Test
    fun `every reference arm carries the v3 sample it was derived from`() {
        assertEquals(setOf("mcp", "none"), v3RenameMethodWideReference.keys)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*RippleCaptureAdmissionTest*'`
Expected: FAIL — unresolved reference `admitCapture`.

- [ ] **Step 3: Implement admission, then the capture test**

`admitCapture` returns one reason per violated criterion: `SUCCESS` false; steps outside
`[stepsMin, stepsMax]` or outside mean±1sd; seconds outside mean±1sd; end-context tokens outside
mean±1sd; `steps <= lastCheckpointStep` (the run ended before the last snapshot point, so the curve
has no fifth checkpoint).

`KeycloakRenameMethodWideCheckpointCaptureTest` reuses the arm flow. Refactor
`RippleScenarioBaseTest.runArm` minimally: extract the body into
`protected fun runArm(agentName: String, withMcp: Boolean, recorder: RippleCheckpointRecorder? = null)`,
and inside it, right before `runner.runTest(...)`, add:

```kotlin
recorder?.install(session.aiAgents.claude)
```

and right after grading:

```kotlin
recorder?.let { rec ->
    val nActual = rec.stepCount()
    val steps = rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS.getValue(modeLabel))
    val admission = admitCapture(
        reference = v3RenameMethodWideReference.getValue(modeLabel),
        success = success,
        steps = nActual,
        seconds = result.agentDurationMs / 1000,
        endContextTokens = metrics.tokenUsage?.totalTokens ?: 0L,
        lastCheckpointStep = steps.last(),
    )
    println("[CHECKPOINT] n=$nActual steps=$steps admitted=${admission.admitted}")
    admission.reasons.forEach { println("[CHECKPOINT]   rejected: $it") }
    steps.forEach { step -> rec.exportPatch(step) }   // writes into session.runDirInContainer
    rec.exportMetadata(nActual)
}
```

The capture test must NOT assert admission — a rejected capture is a real, reportable measurement;
the operator decides whether to repeat. It asserts only that the counter file was written and that
every snapshot tag that the run reached produced a patch file.

Preflight first, cheapest thing that can fail: a
`@Test fun `hook counts every call and snapshots at the target step`` that runs the same container with
`targetSteps = listOf(2)` and a three-tool throwaway prompt (`list the files, read one, then write a
file called probe.txt`), and asserts `stepCount() >= 3` plus a non-empty `exportPatch(2)`. Both halves
matter: the counter proves the hook fires on every call, the patch proves a snapshot really captured
the agent's disk writes. Run this alone before spending an Opus capture.

- [ ] **Step 4: Run the preflight, then the two captures**

```bash
./gradlew :test-experiments:test --tests '*CheckpointCaptureTest.hookPreflight'
./gradlew :test-experiments:test --tests '*CheckpointCaptureTest.captureMcpArm'
./gradlew :test-experiments:test --tests '*CheckpointCaptureTest.captureShellArm'
```

The method names carry no spaces on purpose: TeamCity splits `gradleParams` on whitespace with no shell to
re-join a quoted name, and a filter that fails to narrow would run all three methods — two Opus captures
plus the preflight — in one build.

Expected: preflight PASS with `stepCount() >= 3`; each capture prints `[CHECKPOINT] n=… admitted=true`
and drops five `step-<a_i>.patch` files plus `checkpoints.json` into the run dir. Never run two of
these concurrently. If a capture is rejected, rerun that arm (max 3 attempts) and keep every attempt's
`[CHECKPOINT]`/`[RIPPLE]` block for the report.

- [ ] **Step 5: Commit code, then commit the admitted patches**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/
git commit -m "test-experiments: record and admit a representative ripple capture run"
mkdir -p test-experiments/src/test/resources/ripple-checkpoints/rename-method-wide/{mcp,none}
# copy the admitted step-*.patch + checkpoints.json from the run dirs, then:
git add test-experiments/src/test/resources/ripple-checkpoints
git commit -m "test-experiments: admitted rename-method-wide checkpoint states (both arms)"
```

---

### Task 6: Probe run

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointProbeTest.kt`

**Interfaces:**
- Consumes: `buildCheckpointProbePrompt`, the committed patches, `GitDriver`, the existing gold/grading
  path of `RippleScenarioBaseTest`.
- Produces: one `[CHECKPOINT-PROBE]` log block per run, parsed by Task 7.

- [ ] **Step 1: Write the failing selection test**

```kotlin
@Test
fun `probe coordinates come from system properties and reject an unknown checkpoint`() {
    assertEquals(ProbeCoordinates("mcp", 3, 2), probeCoordinates("mcp", "3", "2"))
    assertThrows(IllegalArgumentException::class.java) { probeCoordinates("mcp", "6", "1") }
    assertThrows(IllegalArgumentException::class.java) { probeCoordinates("shell", "1", "1") }
}

@Test
fun `every committed checkpoint patch is a readable diff that spares the oracle`() {
    listOf("mcp", "none").forEach { arm ->
        val dir = File("src/test/resources/ripple-checkpoints/rename-method-wide/$arm")
        val patches = dir.listFiles { f -> f.name.endsWith(".patch") }!!.sortedBy { it.name }
        assertEquals(5, patches.size, arm)
        patches.forEach { patch ->
            val text = patch.readText()
            assertTrue(text.contains("diff --git") || text.isBlank()) { "${patch.name} is not a diff" }
            RippleCases.renameMethodWide.dpaiaCase().failToPass.forEach { ftp ->
                assertFalse(text.contains(ftp.substringAfterLast('.') + ".java")) {
                    "${patch.name} touches the FAIL_TO_PASS oracle — the capture run tampered"
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointProbeTest.probe coordinates*'`
Expected: FAIL — unresolved reference `probeCoordinates`.

- [ ] **Step 3: Implement the probe**

Copy the arm flow, changing exactly six things and nothing else:

1. `claude.model` is forced to `claude-haiku-4-5` (via the existing `claude.model` sysprop plumbing —
   assert the resolved model is a haiku so a forgotten property cannot silently buy an Opus round).
2. After the gold capture and BEFORE `normalizeFormattingBeforeSnapshot`:
   `git.applyPatch(projectDir, patchText)` then refresh the IDE VFS with a `steroid_execute_code`
   call — reuse whatever `waitForProjectReady`/refresh helper the harness already has, and fail the
   run with a clear message if the apply exits non-zero.
3. `aiMode = AiMode.NONE`, `mcpConnectionMode = McpConnectionMode.None` for BOTH arms' probes.
4. `promptBuilder = { dir -> buildCheckpointProbePrompt(case, dir) }`.
5. Drop the `usedMcpSteroid` assertion (a bare probe never touches the IDE).
6. Print, in addition to the normal `[RIPPLE]` block:
   `[CHECKPOINT-PROBE] arm=<arm> checkpoint=<i> step=<a_i> position=<a_i/n> replicate=<j> Y=<0|1>`.

- [ ] **Step 4: Run one probe end-to-end**

```bash
./gradlew :test-experiments:test --tests '*RippleCheckpointProbeTest.probe' \
  -Dripple.checkpoint.arm=mcp -Dripple.checkpoint.index=5 -Dripple.checkpoint.replicate=1
```

Expected: the patch applies, Haiku runs, grading prints a `[CHECKPOINT-PROBE] … Y=…` line. Checkpoint 5
first on purpose: it is the state most likely to grade `Y=1`, so a `Y=0` there is a signal to debug the
instrument before queueing 49 more runs.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointProbeTest.kt
git commit -m "test-experiments: bare-Haiku probe continuing from a ripple checkpoint"
```

---

### Task 7: Aggregator and report

**Files:**
- Create: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointReport.kt`
- Test: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointReportTest.kt`

**Interfaces:**
- Consumes: `ReadinessCurve`, `checkpointReadiness`, `[CHECKPOINT-PROBE]` lines.
- Produces: `data class ProbeVerdict(val arm: String, val checkpoint: Int, val step: Int, val position: Double, val replicate: Int, val success: Boolean)`,
  `fun parseProbeVerdicts(logText: String): List<ProbeVerdict>`,
  `fun renderCheckpointReport(verdicts: List<ProbeVerdict>): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `verdicts are parsed off the log line`() {
    val log = """
        [:test-experiments:test] [CHECKPOINT-PROBE] arm=mcp checkpoint=1 step=2 position=0.0667 replicate=1 Y=0
        noise
        [CHECKPOINT-PROBE] arm=mcp checkpoint=5 step=23 position=0.7667 replicate=3 Y=1
    """.trimIndent()
    val verdicts = parseProbeVerdicts(log)
    assertEquals(2, verdicts.size)
    assertEquals(ProbeVerdict("mcp", 5, 23, 0.7667, 3, true), verdicts[1])
}

@Test
fun `report prints V per checkpoint and the AUC with its range`() {
    val verdicts = (1..5).flatMap { i ->
        (1..5).map { j -> ProbeVerdict("mcp", i, i * 5, i * 0.1, j, success = j <= i) }
    }
    val report = renderCheckpointReport(verdicts)
    assertTrue(report.contains("| 1 | 5 | 0.100 | 1 | 5 | 0.20 |"))
    assertTrue(report.contains("AUC")); assertTrue(report.contains("0.100..0.500"))
}

@Test
fun `a checkpoint with fewer than five verdicts is reported as incomplete, not averaged away`() {
    val report = renderCheckpointReport(listOf(ProbeVerdict("mcp", 1, 2, 0.07, 1, true)))
    assertTrue(report.contains("INCOMPLETE"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointReportTest*'`
Expected: FAIL — unresolved reference `parseProbeVerdicts`.

- [ ] **Step 3: Implement**

Regex `\[CHECKPOINT-PROBE] arm=(\S+) checkpoint=(\d+) step=(\d+) position=(\S+) replicate=(\d+) Y=([01])`,
grouped by `(arm, checkpoint)`; `V` via `checkpointReadiness`; the curve and `auc` via `ReadinessCurve`;
one markdown table per arm plus a line naming the integration range. A group with `runs != 5` renders
`INCOMPLETE` instead of a `V` value, because a partial group's mean is not the metric this pilot defines.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :test-experiments:test --tests '*RippleCheckpointReportTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/RippleCheckpointReport*.kt
git commit -m "test-experiments: aggregate checkpoint probes into V and observed-range AUC"
```

---

### Task 8: TeamCity plumbing and the 50-run launch

**Files:**
- Modify (separate repo): `~/Work/mcp-steroid-teamcity` — one probe build configuration parameterised by
  `env.RIPPLE_CHECKPOINT_ARM`, `env.RIPPLE_CHECKPOINT_INDEX`, `env.RIPPLE_CHECKPOINT_REPLICATE`,
  `env.CLAUDE_MODEL`, plus one capture configuration.
- Modify: `test-experiments/build.gradle.kts:82-101` — forward `ripple.checkpoint.arm`,
  `ripple.checkpoint.index`, `ripple.checkpoint.replicate` with the same
  system-property-then-`env` fallback the model keys already use.

- [ ] **Step 1: Forward the new properties**

Add the three keys to the existing `mapOf(...)` block with env spellings
`RIPPLE_CHECKPOINT_ARM` / `RIPPLE_CHECKPOINT_INDEX` / `RIPPLE_CHECKPOINT_REPLICATE`.

- [ ] **Step 2: Prove the forwarding**

Extend `ModelSyspropForwardingTest` with the three keys.
Run: `./gradlew :test-experiments:test --tests '*ModelSyspropForwardingTest*'` — Expected: PASS.

- [ ] **Step 3: Land the branch where TeamCity can see it**

```bash
git push origin worktree-semantic-ripple-pilot
```

TeamCity's VCS root pulls from `jb`; follow the root `CLAUDE.md` `jb-merge` procedure if the pilot
branch must be visible there, and land the Gradle property change BEFORE the DSL change that passes it
(an unknown `-P`/`-E` is silently ignored).

- [ ] **Step 4: Queue the runs**

Order matters: 1 preflight → 2 captures → admission → patches committed → 1 smoke probe
(`arm=mcp index=5 replicate=1`) → the remaining 49. Record every build id in
`docs/ripple-checkpoint-pilot/RUN-IDS.md` the way `RIPPLE-RUN-IDS.md` does. The copy-pasteable `jb tc`
commands, the admission bands and the aggregation recipe live in `docs/ripple-checkpoint-pilot/RUNBOOK.md`.

- [ ] **Step 5: Commit**

```bash
git add test-experiments/build.gradle.kts docs/ripple-checkpoint-pilot/RUN-IDS.md
git commit -m "test-experiments: forward checkpoint probe coordinates from TeamCity"
```

---

### Task 9: Pilot report

**Files:**
- Create: `docs/ripple-checkpoint-pilot/REPORT.md`

- [ ] **Step 1: Collect the logs**

Download every probe build log (same recipe as `docs/ripple-trajectory-spike`), concatenate, and run
the aggregator over it.

- [ ] **Step 2: Write the report**

It must contain, per arm: the capture run's admission numbers against the v3 reference table; the
checkpoint table (checkpoint, `a_i`, `a_i/n`, successes, runs, `V`); the curve; `AUC_V` with its
explicit integration range and the width-normalised value; the capture run's own final outcome, stated
separately; instrument failures separated from graded zeros; and measured cost/time totals.

- [ ] **Step 3: Answer the six method questions**

State plainly whether states restored, whether Haiku could continue, whether `V` moved monotonically,
whether readiness grew along the trajectory, how stable the five repeats were (report the
`V`-resolution of 0.2 as the floor on what five runs can distinguish), and what the pilot cost.
Finish with a go/no-go for the 500-run stage.

- [ ] **Step 4: Commit**

```bash
git add docs/ripple-checkpoint-pilot/REPORT.md
git commit -m "docs: solution-readiness checkpoint pilot results"
```

---

## Self-review notes

- Spec coverage: §2→Task 5, §2.1→Task 5, §3→Task 1, §4→Tasks 3–5, §5→Task 6, §5.1→Task 2, §6→Tasks 1+7,
  §7→Task 9, §8 risks→preflight in Task 5 and the FAIL_TO_PASS assertion in Task 6, §9→Tasks 1–7.
- The probe is bare in both arms (Task 6 step 3.3), and the prompt cannot name the arm (Task 2).
- AUC never extrapolates (Task 1, `ReadinessCurve`), and an incomplete group never averages into `V`
  (Task 7).
