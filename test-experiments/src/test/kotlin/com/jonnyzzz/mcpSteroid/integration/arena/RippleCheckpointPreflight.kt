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
 * The cheapest thing that can go wrong, run BEFORE any capture: does the hook fire on every tool call,
 * and does a snapshot really contain what the agent wrote to disk?
 *
 * Both halves are load-bearing. A counter that advances proves `n` measures the trajectory rather than
 * the schedule; a non-empty patch proves the shadow repository sees the agent's writes — a hook that the
 * container user cannot execute, or one whose `git` never reaches the work tree, produces exactly the
 * artifacts of a run with NO hook, and discovering that after a $2 Opus capture is the failure this
 * check exists to prevent.
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

        // The WRITE comes first by design: the patch asserted below is the one at step
        // PREFLIGHT_TARGET_STEP, so a prompt that listed and read before writing would produce a
        // legitimately empty patch there and prove nothing about the instrument.
        val result = claude.runPrompt(
            prompt = """
                Perform exactly these three steps, in this order, one tool call each, and then stop:
                1. Write a file named probe.txt in the current directory with the single line READY.
                2. List the files in the current directory.
                3. Read probe.txt back.
                Do not use any other tool, and do not summarise the repository.
            """.trimIndent(),
            timeoutSeconds = PREFLIGHT_AGENT_TIMEOUT_SECONDS,
        ).awaitForProcessFinish()
        println("[CHECKPOINT-PREFLIGHT] agent exit code: ${result.exitCode}")

        val steps = recorder.stepCount()
        println("[CHECKPOINT-PREFLIGHT] the hook counted $steps tool calls")
        assertTrue(steps >= PREFLIGHT_MIN_STEPS) {
            "The hook counted $steps tool calls for a three-tool prompt. The counter is the source " +
                "of every capture's n, so an undercount here means every reported checkpoint " +
                "position would be wrong. Agent output:\n${result.stdout.take(4000)}"
        }

        val patch = recorder.exportPatch(PREFLIGHT_TARGET_STEP)
        println("[CHECKPOINT-PREFLIGHT] step-$PREFLIGHT_TARGET_STEP patch:\n${patch.take(2000)}")
        assertTrue(patch.isNotBlank()) {
            "The step-$PREFLIGHT_TARGET_STEP snapshot is an empty diff, so the shadow repository " +
                "never saw the file the agent wrote. A capture run would hand the probe an " +
                "unchanged tree and every checkpoint would look like step 0."
        }
    } finally {
        lifetime.closeAllStacks()
    }
}

/** The single snapshot position of the preflight — small enough that a cheap prompt reaches it. */
private const val PREFLIGHT_TARGET_STEP: Int = 2

/** Three ordered instructions, so a working hook must have counted at least three calls. */
private const val PREFLIGHT_MIN_STEPS: Int = 3

/** A three-tool throwaway prompt; generous only so a cold model pull cannot fail the gate. */
private const val PREFLIGHT_AGENT_TIMEOUT_SECONDS: Long = 600
