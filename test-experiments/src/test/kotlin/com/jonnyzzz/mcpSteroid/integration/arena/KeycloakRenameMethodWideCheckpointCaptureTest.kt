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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The CAPTURE half of the solution-readiness pilot: one recorded `rename-method-wide` run per arm,
 * whose intermediate states become the checkpoints a bare probe is later restarted from.
 *
 * It does not extend [RippleScenarioBaseTest] on purpose. A subclass would inherit that class's four
 * graded `@Test` methods, so `--tests '*CheckpointCaptureTest*'` would spend four extra Opus runs that
 * record nothing at all. Instead it HOLDS an arm flow — an anonymous [RippleScenarioBaseTest], which
 * JUnit's own `IsPotentialTestContainer` never treats as a test class — and calls `runArm` with a
 * recorder. The graded run is therefore byte-identical to the family's normal one, which is the whole
 * point: a checkpoint is only meaningful if the trajectory it was cut from is a trajectory this
 * experiment really measures.
 *
 * Nothing here asserts admission. [admitCapture]'s verdict is printed by the arm flow, and a capture
 * that misses the v3 representativeness band is a real measurement of this arm — the operator, not the
 * test, decides whether another Opus run is worth its price.
 */
class KeycloakRenameMethodWideCheckpointCaptureTest {

    /**
     * The recorded mcp arm. Same 180-minute budget as the graded family for the reason
     * [RippleScenarioBaseTest] documents: the agent's own 90 minutes plus a cold image build, clone,
     * Maven import and grading already exceed 124 minutes before any headroom.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = capture(withMcp = true)

    /** The recorded shell arm — the positive control the mcp curve is read against. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = capture(withMcp = false)

    /**
     * The cheapest thing that can go wrong, run BEFORE either capture: does the hook fire on every
     * tool call, and does a snapshot really contain what the agent wrote to disk?
     *
     * Both halves are load-bearing. A counter that advances proves `n` measures the trajectory rather
     * than the schedule; a non-empty patch proves the shadow repository sees the agent's writes — a
     * hook that the container user cannot execute, or one whose `git` never reaches the work tree,
     * produces exactly the artifacts of a run with NO hook, and discovering that after a $2 Opus
     * capture is the failure this test exists to prevent.
     *
     * It runs the same KIND of container as a capture — an IDE container, whose image carries both git
     * and the agent, and whose agents already have the guest project dir as their working directory —
     * but over [IntelliJProject.EmptyProject] instead of Keycloak: no JDK, no build-system import, no
     * clone. The bare `claude-cli` image cannot stand in for it, because it ships no git at all and the
     * recorder is entirely git.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `hook counts every call and snapshots at the target step`() {
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
            // The container is thrown away with the test, so the default `/checkpoints` is enough here:
            // nothing in this run has to survive as a published artifact.
            val recorder = RippleCheckpointRecorder(
                container = session.scope,
                projectDir = projectDir,
                targetSteps = listOf(PREFLIGHT_TARGET_STEP),
            )
            recorder.install(claude.asDockerClaudeSession())

            // The WRITE comes first by design: the only snapshot this run takes is at step
            // PREFLIGHT_TARGET_STEP, so a prompt that listed and read before writing would produce a
            // legitimately empty patch and prove nothing about the instrument.
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

    /**
     * One recorded arm.
     *
     * The recorder is built inside the arm flow's own callback because it addresses things that only
     * exist there: the live container and the guest project dir it snapshots. `gitDir` points INSIDE
     * the container's bind-mounted run directory — [RippleCheckpointRecorder] writes the patches and
     * `checkpoints.json` next to it, and only what lands under the run dir is published as a TeamCity
     * artifact. A capture whose patches stayed container-local would have to be repeated.
     *
     * The model comes off the resolved session rather than from the system property, so the metadata
     * records the model that really ran instead of the one the operator meant to select.
     */
    private fun capture(withMcp: Boolean) {
        val arm = if (withMcp) "mcp" else "none"
        renameMethodWideArm().runArm("claude", withMcp) { session, projectDir ->
            RippleCheckpointRecorder(
                container = session.scope,
                projectDir = projectDir,
                targetSteps = rippleCheckpointSteps(RIPPLE_EXPECTED_STEPS.getValue(arm)),
                gitDir = "${session.guestRunDir()}/checkpoints/.git",
                case = RippleCases.renameMethodWide.instanceId,
                arm = arm,
                model = session.aiAgents.claude.asDockerClaudeSession().model,
            )
        }
    }

    companion object {
        /** The single snapshot position of the preflight — small enough that a cheap prompt reaches it. */
        private const val PREFLIGHT_TARGET_STEP: Int = 2

        /** Three ordered instructions, so a working hook must have counted at least three calls. */
        private const val PREFLIGHT_MIN_STEPS: Int = 3

        /** A three-tool throwaway prompt; generous only so a cold model pull cannot fail the gate. */
        private const val PREFLIGHT_AGENT_TIMEOUT_SECONDS: Long = 600
    }
}

/**
 * The `rename-method-wide` arm flow, as an ANONYMOUS [RippleScenarioBaseTest].
 *
 * Anonymity is the mechanism, not an accident: JUnit Jupiter's `IsPotentialTestContainer` rejects
 * anonymous classes, so the four graded `@Test` methods this object inherits can never be discovered
 * and run alongside the capture. A named subclass — even a private top-level one, which Kotlin compiles
 * to a package-private JVM class that JUnit's private-class filter does NOT exclude — would be picked
 * up by classpath scanning.
 */
private fun renameMethodWideArm(): RippleScenarioBaseTest = object : RippleScenarioBaseTest() {
    override val case: RippleCase = RippleCases.renameMethodWide
}

/**
 * The GUEST path of the run directory, derived from the container's own bind mounts.
 *
 * Read off the mount rather than hardcoded, because the pair (host run dir, guest mount point) is
 * decided by the container factory and a stale literal here would silently write the capture's patches
 * into a container-local directory that no artifact publisher ever sees.
 */
private fun IntelliJContainer.guestRunDir(): String {
    val hostRunDir = runDirInContainer.absoluteFile
    return scope.volumes.firstOrNull { it.host.absoluteFile == hostRunDir }?.guest?.trimEnd('/')
        ?: error(
            "The run directory $hostRunDir is not bind-mounted into the container, so the checkpoint " +
                "patches written next to it could never be collected as artifacts"
        )
}
