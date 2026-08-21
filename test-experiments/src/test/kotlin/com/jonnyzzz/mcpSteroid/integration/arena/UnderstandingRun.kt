/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.asDockerClaudeSession
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.readFromContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

/**
 * The budget gate of one research run: the hook script, its counters, and the record it leaves.
 *
 * Everything lives under [recordDir], which the caller points inside the session's run directory, so
 * the whole recording — the counters, the per-attempt payloads, the tool log — is published as a build
 * artifact. A research run whose accounting stayed inside a discarded container would have to be paid
 * for twice.
 */
class UnderstandingResearchGate(
    private val container: ContainerDriver,
    private val recordDir: String,
    private val budget: Int,
) {
    private val counterFile: String = "$recordDir/budget-used"
    private val deniedFile: String = "$recordDir/budget-denied"
    private val toolLogFile: String = "$recordDir/tools.log"
    private val scriptPath: String = "$recordDir/budget-gate.sh"

    /**
     * Writes the gate into the container and hands it to the session as its ONLY settings file.
     *
     * `useSettings` writes one file and a second call overwrites it, so every hook a research run needs
     * has to be composed here — see [understandingHookSettingsJson].
     */
    fun install(claude: DockerClaudeSession) {
        container.mkdirs(recordDir).assertExitCode(0) { "Failed to create $recordDir: $stderr" }
        container.writeFileInContainer(
            scriptPath,
            understandingBudgetHookScript(
                budget = budget,
                counterFile = counterFile,
                deniedFile = deniedFile,
                recordDir = recordDir,
            ),
            executable = true,
        )
        // Checked from inside the container: a hook the agent's uid cannot execute produces exactly the
        // artifacts of a run with no hook at all — an unbudgeted research run that looks budgeted.
        exec(listOf("test", "-x", scriptPath), "verify the gate is executable")
            .assertExitCode(0) { "The budget gate $scriptPath is not executable by the container user" }
        // Seeded from inside the container so the files belong to the uid the hook runs as; a
        // host-written counter can be unwritable for the agent, and the budget would never advance.
        exec(
            listOf("sh", "-c", "echo 0 > $counterFile; echo 0 > $deniedFile; : > $toolLogFile"),
            "seed the budget counters",
        ).assertExitCode(0) { "Failed to seed the budget counters in $recordDir: $stderr" }

        claude.useSettings(understandingHookSettingsJson(listOf(AgentHook("PreToolUse", scriptPath))))
        println("[UNDERSTANDING] budget gate installed: $budget interactions, records in $recordDir")
    }

    fun usage(): UnderstandingBudgetUsage = parseUnderstandingBudgetUsage(
        counterFileContent = readOrNull(counterFile),
        deniedFileContent = readOrNull(deniedFile),
    )

    /** Every tool the agent reached for, in order, including the exempt and the refused ones. */
    fun toolLog(): List<String> =
        readOrNull(toolLogFile)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun readOrNull(path: String): String? {
        val result = exec(listOf("sh", "-c", "cat $path 2>/dev/null"), "read $path")
        return result.stdout.takeIf { result.exitCode == 0 }
    }

    private fun exec(args: List<String>, description: String) = container.startProcessInContainer {
        args(args)
        timeoutSeconds(120)
        description(description)
    }.awaitForProcessFinish()
}

/**
 * One research cell, end to end: deploy, gate, explore, hand back a note.
 *
 * The run is NOT graded and NOT verified against the oracle — it produces no solution to grade. What it
 * produces is a note plus the accounting that makes the note comparable: how many interactions were
 * spent, how many the agent still wanted, how many output tokens it burned, and the proof that the tree
 * it looked at is the same tree every downstream cell will start from.
 *
 * The oracle test is NOT deployed into the tree. It names classes the solution has yet to create and
 * lives in the package they belong to, so a research agent that could read it would be handed the
 * localization this experiment exists to measure. It is applied at grading time only — see
 * [runUnderstandingDownstream].
 */
fun runUnderstandingResearch(
    case: UnderstandingCase,
    arm: String,
    budget: Int,
    noteLimitChars: Int,
    replicate: Int,
): UnderstandingNoteRecord {
    check(arm == "mcp" || arm == "none") { "a research arm is `mcp` or `none`, got '$arm'" }
    val withMcp = arm == "mcp"
    val noteId = understandingNoteId(arm, budget, noteLimitChars, replicate)
    println("[UNDERSTANDING] research cell $noteId of ${case.instanceId}")

    val lifetime = CloseableStackHost()
    try {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "understand-$arm-b$budget",
            project = IntelliJProject.ProjectFromGitCommitAndPatch(
                cloneUrl = case.cloneUrl,
                repoOwnerAndName = case.repoOwnerAndName,
                baseCommit = case.baseCommit,
                // Empty on purpose: the oracle stays out of the tree in BOTH phases.
                testPatch = "",
                displayName = case.instanceId,
                buildSystem = "maven",
            ),
            aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
            mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
            mountDockerSocket = false,
        )).waitForProjectReady(
            timeoutMillis = case.projectReadyTimeoutMs,
            projectJdkVersion = case.projectJdkVersion,
            buildSystem = BuildSystem.MAVEN,
            compileProject = true,
            // The tree is pristine, so it does compile; the prewarm is still a warm-up and not an
            // assertion, for the same reason every other family states it — an unrelated module's
            // failure must not cost the cell.
            requireCleanCompile = false,
        )

        val projectDir = session.intellijDriver.getGuestProjectDir()
        val claude = session.aiAgents.claude.asDockerClaudeSession()
        println("[UNDERSTANDING] resolved agent model: ${claude.model}")
        check(!claude.model.contains("haiku", ignoreCase = true)) {
            "the research phase resolved '${claude.model}'. The research agent is the STRONG model; a " +
                "haiku here would measure the wrong side of the experiment. Set -Dclaude.model."
        }

        // The IDE is what the mcp arm queries, and it must be open on the very tree the note describes.
        val openProjects = session.mcpSteroid.mcpListProjects()
        check(openProjects.any { it.path == projectDir }) {
            "no IDE project open at $projectDir before the research run " +
                "(open: ${openProjects.joinToString { "${it.name}@${it.path}" }})"
        }

        // Installed AFTER every piece of harness work, so the budget counts the agent's interactions
        // and nothing the harness did on its behalf.
        val gate = UnderstandingResearchGate(
            container = session.scope,
            recordDir = "${session.guestRunDir()}/understanding",
            budget = budget,
        )
        gate.install(claude)

        val prompt = buildUnderstandingResearchPrompt(
            case = case,
            projectDir = projectDir,
            withMcp = withMcp,
            budget = budget,
            noteLimitChars = noteLimitChars,
        )
        val startMs = System.currentTimeMillis()
        val agentResult = claude.runPrompt(prompt, timeoutSeconds = case.researchTimeoutSeconds)
            .awaitForProcessFinish()
        val durationMs = System.currentTimeMillis() - startMs

        val metrics = collectRunMetrics(
            runDir = session.runDirInContainer,
            agentName = "claude",
            fallbackStdout = agentResult.stdout,
        )
        val usage = gate.usage()
        val tools = gate.toolLog()
        val pristine = readUnderstandingPristineVerdict(session.scope, projectDir)
        // The SAME text the metrics above were parsed from, and deliberately not the captured stdout:
        // that stream is console-filtered and the filter drops the terminal `result` event the note
        // travels in. Reading it cost this experiment its whole first research wave — eight paid Opus
        // runs that had each written a perfectly good note reported "no final message" (builds
        // 1038399360..374).
        val note = extractUnderstandingNote(
            finalMessage = decodeAgentFinalResponse(
                resolveAgentRawOutput(
                    runDir = session.runDirInContainer,
                    agentName = "claude",
                    fallbackStdout = agentResult.stdout,
                )
            ),
            limitChars = noteLimitChars,
        )

        val record = UnderstandingNoteRecord(
            noteId = noteId,
            case = case.instanceId,
            arm = arm,
            budget = budget,
            limitChars = noteLimitChars,
            replicate = replicate,
            model = claude.model,
            note = note,
            budgetedCalls = usage.used,
            deniedCalls = usage.denied,
            rawToolCalls = tools.size,
            researchOutputTokens = metrics.tokenUsage?.outputTokens,
            researchCostUsd = metrics.tokenUsage?.costUsd,
            researchSeconds = durationMs / 1000,
            pristine = pristine.pristine,
            pristineViolations = pristine.violations,
        )

        // Published before anything is asserted: a note whose run turned out to be inadmissible is
        // still evidence about the arm, and the operator decides what to do with it.
        session.runDirInContainer.resolve("$noteId.md").writeText(note.text)
        session.runDirInContainer.resolve("$noteId.json").writeText(record.toJson())

        println("[UNDERSTANDING] tools: ${tools.groupingBy { it }.eachCount()}")
        println("[UNDERSTANDING] note: ${note.describe()}")
        println("[UNDERSTANDING] tree: ${pristine.describe()}")
        println(record.logLine())

        check(usage.used > 0) {
            "the research run spent zero budgeted interactions, so it never looked at the repository " +
                "and its note describes nothing. Tools attempted: ${tools.groupingBy { it }.eachCount()}"
        }
        check(pristine.pristine) {
            "INVALID RESEARCH RUN: the tree is not pristine. ${pristine.describe()}. A note produced " +
                "while editing the repository cannot be compared with one produced by looking at it."
        }
        return record
    } finally {
        lifetime.closeAllStacks()
    }
}

/**
 * One downstream cell: the same pristine tree, the same task, a note or nothing — then the oracle.
 *
 * The oracle patch is applied AFTER the agent has finished and BEFORE the graded run. That ordering is
 * the design, not a convenience:
 *
 * - during the run it keeps the hidden test out of the agent's reach, so no cell can read the answer's
 *   class names and package out of the file that grades it;
 * - after the run it makes tampering impossible rather than merely detectable — the file the agent
 *   would have had to rewrite did not exist while the agent was running;
 * - a patch that does not apply is reported as a LOST measurement, never as a zero: the agent created
 *   something at the oracle's path, and that cell was never graded at all.
 */
fun runUnderstandingDownstream(
    case: UnderstandingCase,
    condition: UnderstandingCondition,
    replicate: Int,
): Boolean? {
    val note = condition.noteText(case)
    println(
        "[UNDERSTANDING-DOWN] cell case=${case.instanceId} condition=${condition.label} " +
            "replicate=$replicate note=${note?.length ?: 0} chars"
    )

    val lifetime = CloseableStackHost()
    try {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "understand-down-${condition.label}".take(40),
            project = IntelliJProject.ProjectFromGitCommitAndPatch(
                cloneUrl = case.cloneUrl,
                repoOwnerAndName = case.repoOwnerAndName,
                baseCommit = case.baseCommit,
                testPatch = "",
                displayName = case.instanceId,
                buildSystem = "maven",
            ),
            // No IDE access in ANY downstream arm: a cell measures what the NOTE is worth, and giving
            // one of them the tools back would measure the note and the tools together.
            aiMode = AiMode.NONE,
            mcpConnectionMode = McpConnectionMode.None,
            mountDockerSocket = false,
        )).waitForProjectReady(
            timeoutMillis = case.projectReadyTimeoutMs,
            projectJdkVersion = case.projectJdkVersion,
            buildSystem = BuildSystem.MAVEN,
            compileProject = true,
            requireCleanCompile = false,
        )

        val projectDir = session.intellijDriver.getGuestProjectDir()
        val claude = session.aiAgents.claude.asDockerClaudeSession()
        println("[UNDERSTANDING-DOWN] resolved agent model: ${claude.model}")
        check(claude.model.contains("haiku", ignoreCase = true)) {
            "the downstream agent resolved '${claude.model}'. Every downstream cell runs on the same " +
                "weak model — an Opus cell would answer a different question, at ten times the price. " +
                "Set -D${RippleCheckpointProbeTest.CLAUDE_MODEL_PROPERTY} to a haiku, or leave it unset."
        }

        if (case.needsReactorInstall) {
            installReactorWithNetworkRetries(session.scope, projectDir)
        }

        val testCase = case.dpaiaCase()
        val runner = ArenaTestRunner(container = session.scope, projectGuestDir = projectDir)
        val result = runner.runTest(
            testCase = testCase,
            agent = session.aiAgents.claude,
            withMcp = false,
            timeoutSeconds = case.downstreamTimeoutSeconds,
            predeployedProjectDir = projectDir,
            logDir = session.runDirInContainer,
            promptBuilder = { dir -> buildUnderstandingDownstreamPrompt(case, dir, note) },
            enforceFirstCallProjectMarker = false,
        )

        val verifier = ArenaVerifier(session.scope, projectDir, testCase.buildSystem)
        val oracleApplied = try {
            GitDriver(session.scope).applyPatch(projectDir, testCase.testPatch)
            true
        } catch (e: Exception) {
            println("[UNDERSTANDING-DOWN] MEASUREMENT LOST: the oracle patch did not apply after the run")
            println("[UNDERSTANDING-DOWN]   — the agent occupies a path the oracle needs, so this cell was")
            println("[UNDERSTANDING-DOWN]   never graded. Cause: ${e::class.simpleName}: ${e.message}")
            false
        }
        if (!oracleApplied) {
            println(understandingDownstreamLine(case, condition, replicate, null, "LOST oracle-patch-conflict"))
            return null
        }

        // Snapshotted right after the patch: the oracle did not exist while the agent ran, so this is
        // both the pre-agent state and the current one, and the tamper check can only ever pass. It is
        // still taken, because `verify` refuses to grade without it.
        val oracleSnapshot = verifier.snapshotTestFiles(testCase.testPatch)
        val oracleContents = verifier.snapshotOracleContents(testCase.testPatch, testCase.failToPass)
        val verification = try {
            verifier.verify(
                failToPass = testCase.failToPass,
                projectJdkVersion = case.projectJdkVersion,
                testPatch = testCase.testPatch,
                preAgentSnapshot = oracleSnapshot,
                // No whole-suite baseline on a tree this size; regressions read as UNKNOWN, which is
                // what they are. The scoped module build is the only regression evidence available.
                baseline = null,
                mavenProjectSelector = case.gradingScopeSelector,
                preAgentOracleContents = oracleContents,
                purgeScopedBuildOutput = true,
            )
        } catch (e: Exception) {
            System.err.println(
                "[UNDERSTANDING-DOWN] verification failed for ${case.instanceId}: ${e.message}"
            )
            e.printStackTrace()
            null
        }

        val metrics = collectRunMetrics(
            runDir = session.runDirInContainer,
            agentName = "claude",
            fallbackStdout = result.agentResult.stdout,
        )
        val success = verification?.let { it.objectiveSuccess && !it.failToPassTampered }
        val verdict = when {
            metrics.apiTransportError != null -> "LOST transport ${metrics.apiTransportError}"
            success == null -> "LOST ungraded"
            success -> "Y=1"
            else -> "Y=0"
        }
        println(
            understandingDownstreamLine(
                case = case,
                condition = condition,
                replicate = replicate,
                cost = UnderstandingCellCost(
                    usd = metrics.tokenUsage?.costUsd,
                    agentSeconds = result.agentDurationMs / 1000,
                    outputTokens = metrics.tokenUsage?.outputTokens,
                ),
                verdict = verdict,
            )
        )
        return if (verdict.startsWith("LOST")) null else success
    } finally {
        lifetime.closeAllStacks()
    }
}

/**
 * The one line an aggregate reads per downstream cell.
 *
 * Same shape as the checkpoint probe's line and for the same reason: a build log is the only artifact
 * an operator reads before deciding whether to queue the next twenty cells, and a verdict that has to
 * be reconstructed from prose is a verdict that gets reconstructed wrongly.
 */
fun understandingDownstreamLine(
    case: UnderstandingCase,
    condition: UnderstandingCondition,
    replicate: Int,
    cost: UnderstandingCellCost?,
    verdict: String,
): String = buildString {
    append("[UNDERSTANDING-DOWN] case=${case.instanceId} condition=${condition.label} ")
    append("replicate=$replicate $verdict")
    cost?.usd?.let { append(" usd=%.4f".format(java.util.Locale.ROOT, it)) }
    cost?.agentSeconds?.let { append(" agentSeconds=$it") }
    cost?.outputTokens?.let { append(" outputTokens=$it") }
}

/**
 * What one downstream cell cost, in the three currencies a reader of the aggregate compares.
 *
 * Its own type rather than the checkpoint probe's `ProbeRunCost`, which is private to that file: the
 * two experiments publish different verdict lines and a shared type would tie their formats together
 * for no gain. Every field is nullable because a run whose stream ended without the terminal usage
 * event really has an unknown price, and a zero there would quietly lower every average that includes
 * it.
 */
data class UnderstandingCellCost(
    val usd: Double?,
    val agentSeconds: Long?,
    val outputTokens: Long?,
)
