/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.asDockerClaudeSession
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The GUEST path of a container's run directory, derived from its own bind mounts.
 *
 * Read off the mount rather than hardcoded, because the pair (host run dir, guest mount point) is
 * decided by the container factory and a stale literal here would silently write a capture's patches
 * into a container-local directory that no artifact publisher ever sees.
 */
fun IntelliJContainer.guestRunDir(): String {
    val hostRunDir = runDirInContainer.absoluteFile
    return scope.volumes.firstOrNull { it.host.absoluteFile == hostRunDir }?.guest?.trimEnd('/')
        ?: error(
            "The run directory $hostRunDir is not bind-mounted into the container, so the checkpoint " +
                "patches written next to it could never be collected as artifacts"
        )
}

/**
 * Where a recorded run keeps its shadow repository — and therefore its patches and `checkpoints.json`,
 * which [RippleCheckpointRecorder] writes next to it.
 *
 * INSIDE the bind-mounted run directory, because only what lands there is published as a TeamCity
 * artifact, and a capture whose states stayed container-local has to be paid for twice.
 */
fun IntelliJContainer.checkpointGitDir(): String = "${guestRunDir()}/checkpoints/.git"

/**
 * Everything about a recording that can be checked without paying for one, expressed over the snapshot
 * tree ids the shadow repository holds.
 *
 * Pure, so the four shapes it must recognise are decided by unit tests instead of by a container run:
 * a gap (some step has no snapshot — `RippleCheckpointRecorder.plan` refuses such a recording, and
 * finding out at that point means the Opus run is already paid for), a recording whose snapshots never
 * differ (the hook fires, the counter advances, and the shadow repository never sees the agent's
 * writes — a capture like that yields five checkpoints that are all the pristine tree), a missing
 * `step-0` (nothing for a patch to be a diff against), and a step with no HOOK RECORD.
 *
 * The last one is new in round 2 and is the reason the round exists: without the hook's own stdin there
 * is no tool identity and no transcript path for a step, and therefore no upstream denominator —
 * exactly the hole that makes capture 1 unusable for the round-2 question. [hookRecords] is the set of
 * steps a record was found for; passing it explicitly rather than defaulting it keeps a caller from
 * silently opting out of the check.
 */
fun preflightProblems(steps: Int, trees: Map<Int, String>, hookRecords: Set<Int>): List<String> = buildList {
    if (0 !in trees) {
        add("there is no step-0 snapshot, so no checkpoint patch could be a diff against the pristine tree")
    }
    val missing = (1..steps).filterNot { it in trees }
    if (missing.isNotEmpty()) {
        add(
            "the hook counted $steps tool calls but left no snapshot for $missing — a capture with such " +
                "a gap cannot be planned, because the state at a checkpoint position would be unknown"
        )
    }
    if (trees.values.distinct().size < 2) {
        add(
            "every snapshot holds the same tree ${trees.values.firstOrNull()}, so the shadow repository " +
                "never changed — the recording would hand every probe the pristine tree while looking " +
                "healthy in the log"
        )
    }
    val unrecorded = (1..steps).filterNot { it in hookRecords }
    if (unrecorded.isNotEmpty()) {
        add(
            "the hook counted $steps tool calls but wrote no stdin record for $unrecorded — those steps " +
                "have no tool identity and no transcript path, so no upstream work can be attributed " +
                "to them"
        )
    }
}

/**
 * The cheapest thing that can go wrong, run BEFORE any capture: does the hook fire on every tool call,
 * does every one of those calls leave a snapshot, and do those snapshots really contain what the agent
 * wrote to disk?
 *
 * Each half is load-bearing. A counter that advances proves `n` measures the trajectory rather than
 * the schedule; a snapshot per step is what makes the checkpoint positions derivable from that `n` at
 * all; a non-empty patch proves the shadow repository sees the agent's writes — a hook that the
 * container user cannot execute, or one whose `git` never reaches the work tree, produces exactly the
 * artifacts of a run with NO hook, and discovering that after a $2 Opus capture is the failure this
 * check exists to prevent.
 *
 * The prompt is long enough for a real [RippleCheckpointRecorder.plan] to run on the result. That is the
 * point of its length: planning is where a recording's states are compared to each other, and a pilot
 * that has already published two byte-identical checkpoints does not get to leave that path untested
 * until the expensive run.
 *
 * It is case-independent on purpose and therefore shared by every capture test: it runs the same KIND of
 * container as a capture — an IDE container, whose image carries both git and the agent, and whose agents
 * already have the guest project dir as their working directory — but over [IntelliJProject.EmptyProject]
 * instead of a real repository: no JDK, no build-system import, no clone. The bare `claude-cli` image
 * cannot stand in for it, because it ships no git at all and the recorder is entirely git.
 */
fun runCheckpointHookPreflight() {
    val lifetime = CloseableStackHost()
    try {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "ripple-checkpoint-hook-preflight",
            project = IntelliJProject.EmptyProject,
            aiMode = AiMode.NONE,
            mcpConnectionMode = McpConnectionMode.None,
            mountDockerSocket = false,
        )).waitForProjectReady()

        val projectDir = session.intellijDriver.getGuestProjectDir()
        val claude = session.aiAgents.claude
        // The container is thrown away with the test, so the recorder's default directory is enough
        // here: nothing in this run has to survive as a published artifact.
        val recorder = RippleCheckpointRecorder(
            container = session.scope,
            projectDir = projectDir,
        )
        recorder.install(claude.asDockerClaudeSession())

        // The writes are spread ACROSS the prompt, not batched at its start: the states of consecutive
        // steps have to differ in the middle of the trajectory for the planning path to be exercised,
        // and a prompt that wrote everything first would leave every later snapshot identical — the very
        // shape that produced two byte-identical checkpoints in the discarded stage.
        val result = claude.runPrompt(
            prompt = """
                Perform exactly these steps, in this order, one tool call each, and then stop:
                1. Write a file named probe-1.txt in the current directory with the single line READY.
                2. List the files in the current directory.
                3. Write a file named probe-2.txt in the current directory with the single line READY.
                4. Read probe-1.txt back.
                5. Write a file named probe-3.txt in the current directory with the single line READY.
                6. List the files in the current directory.
                7. Read probe-3.txt back.
                Do not use any other tool, and do not summarise the repository.
            """.trimIndent(),
            timeoutSeconds = PREFLIGHT_AGENT_TIMEOUT_SECONDS,
        ).awaitForProcessFinish()
        println("[CHECKPOINT-PREFLIGHT] agent exit code: ${result.exitCode}")

        val steps = recorder.stepCount()
        println("[CHECKPOINT-PREFLIGHT] the hook counted $steps tool calls")
        assertTrue(steps >= PREFLIGHT_MIN_STEPS) {
            "The hook counted $steps tool calls for a $PREFLIGHT_MIN_STEPS-tool prompt. The counter is " +
                "the source of every capture's n, so an undercount here means every reported checkpoint " +
                "position would be wrong. Agent output:\n${result.stdout.take(4000)}"
        }

        val trees = recorder.stepTreeIds()
        println("[CHECKPOINT-PREFLIGHT] snapshot trees: $trees")
        val hookRecords = recorder.hookRecordSteps()
        println("[CHECKPOINT-PREFLIGHT] hook records for steps: ${hookRecords.sorted()}")
        val problems = preflightProblems(steps = steps, trees = trees, hookRecords = hookRecords)
        problems.forEach { println("[CHECKPOINT-PREFLIGHT] problem: $it") }
        assertTrue(problems.isEmpty()) {
            "The recording is not usable for a capture:\n${problems.joinToString("\n")}"
        }

        // The whole planning path on real data, including the distinct-state rule. A capture is the
        // wrong place to discover that this throws.
        val plan = recorder.plan(steps)
        println(
            "[CHECKPOINT-PREFLIGHT] plan: firstWriteStep=${plan.firstWriteStep} steps=${plan.steps}"
        )
        assertTrue(plan.checkpoints.isNotEmpty()) {
            "Planning a $steps-step recording produced no checkpoints at all"
        }

        val patch = recorder.exportPatch(plan.steps.last())
        println("[CHECKPOINT-PREFLIGHT] step-${plan.steps.last()} patch:\n${patch.take(2000)}")
        assertTrue(patch.isNotBlank()) {
            "The deepest planned snapshot is an empty diff, so the shadow repository never saw the " +
                "files the agent wrote. A capture run would hand the probe an unchanged tree and every " +
                "checkpoint would look like step 0."
        }

        // The transcript is the exact source of the upstream denominator of round 2, and its path is
        // only knowable through the hook records. A capture that publishes states but no transcript
        // measures nothing this round needs, so the gate for it belongs here — where it is free.
        val transcripts = recorder.exportStepRecords()
        println("[CHECKPOINT-PREFLIGHT] published transcripts: $transcripts")
        assertTrue(transcripts.isNotEmpty()) {
            "The hook records name no session transcript that could be copied, so a capture would " +
                "publish no per-message usage and the cumulative output tokens before each state " +
                "would be unrecoverable — the exact hole that makes capture 1 unusable for round 2."
        }
    } finally {
        lifetime.closeAllStacks()
    }
}

/**
 * Seven ordered instructions, so a working hook must have counted at least seven calls.
 *
 * Seven and not three: the prompt writes on its first, third and fifth call, so the recording carries a
 * first write followed by an edit phase several steps long — short enough that several fractions round
 * onto one step, which is exactly the [selectCheckpoints] path a capture must not be the first to run.
 */
private const val PREFLIGHT_MIN_STEPS: Int = 7

/** A seven-tool throwaway prompt; generous only so a cold model pull cannot fail the gate. */
private const val PREFLIGHT_AGENT_TIMEOUT_SECONDS: Long = 600
