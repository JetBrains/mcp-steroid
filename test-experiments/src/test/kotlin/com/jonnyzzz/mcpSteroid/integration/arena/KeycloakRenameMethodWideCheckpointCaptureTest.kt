/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.asDockerClaudeSession
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
 *
 * The three test methods break the package's backticked-sentence naming convention on purpose: each is
 * selected INDIVIDUALLY by a TeamCity build (`-PtestFilter=*CheckpointCaptureTest.<method>`), and TeamCity
 * splits `gradleParams` on whitespace with no shell to re-join it, so a name containing spaces cannot be
 * addressed from CI. A filter that fails to narrow would run all three methods in one build — two Opus
 * captures plus the preflight, well past the build's own timeout.
 */
class KeycloakRenameMethodWideCheckpointCaptureTest {

    /**
     * The recorded mcp arm. Same 180-minute budget as the graded family for the reason
     * [RippleScenarioBaseTest] documents: the agent's own 90 minutes plus a cold image build, clone,
     * Maven import and grading already exceed 124 minutes before any headroom.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun captureMcpArm() = capture(withMcp = true)

    /** The recorded shell arm — the positive control the mcp curve is read against. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.MINUTES)
    fun captureShellArm() = capture(withMcp = false)

    /**
     * The instrument, proven on a throwaway container before either capture is paid for.
     *
     * The body lives in [runCheckpointHookPreflight] because nothing about it is case-specific — the
     * pilot's DPAIA capture asks the same two questions of the same hook — and see there for what each
     * half of it decides.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun hookPreflight() = runCheckpointHookPreflight()

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
                gitDir = session.checkpointGitDir(),
                case = RippleCases.renameMethodWide.instanceId,
                arm = arm,
                model = session.aiAgents.claude.asDockerClaudeSession().model,
            )
        }
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
