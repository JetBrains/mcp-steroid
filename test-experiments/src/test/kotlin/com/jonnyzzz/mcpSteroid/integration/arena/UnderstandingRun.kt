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
import com.jonnyzzz.mcpSteroid.testHelper.docker.ExecContainerProcessRequest
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
    /**
     * True for the arm that has semantic tools, so the CLI puts their schemas in the model's context at
     * start-up instead of behind a `ToolSearch` call it may never make — see
     * [understandingHookSettingsJson]. False for the control arm, where the flag would be meaningless:
     * that session is started with no MCP server at all.
     */
    private val eagerMcpTools: Boolean = false,
    /**
     * Which tool names the gate lets through free.
     *
     * A parameter and not a constant because the two phases charge for different things — see
     * [UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS] — and because the alternative, a second gate
     * class, would be a second place for the counter-file and executable-bit mistakes this one
     * already learned about the hard way.
     */
    private val exemptTools: List<String> = UNDERSTANDING_BUDGET_EXEMPT_TOOLS,
    /** What the agent is told when the wall arrives; the two phases want opposite things next. */
    private val exhaustedMessage: String = UNDERSTANDING_BUDGET_EXHAUSTED_MESSAGE,
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
                exemptTools = exemptTools,
                exhaustedMessage = exhaustedMessage,
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

        if (eagerMcpTools) {
            // Belt AND braces, deliberately: the settings file's `env` block is the documented place,
            // and the process environment is the one the CLI is known to honour in every version. The
            // failure this guards against is silent by nature — a deferred tool list looks exactly like
            // an agent that chose not to use its tools — and one wasted trajectory costs more than the
            // duplication.
            claude.withSessionEnv(ENABLE_TOOL_SEARCH_KEY, "false")
        }
        claude.useSettings(
            understandingHookSettingsJson(
                hooks = listOf(AgentHook("PreToolUse", scriptPath)),
                eagerMcpTools = eagerMcpTools,
            ),
        )
        println(
            "[UNDERSTANDING] budget gate installed: $budget interactions, records in $recordDir, " +
                "eager mcp tools=$eagerMcpTools, free: ${exemptTools.joinToString(", ")}",
        )
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

    private fun exec(args: List<String>, description: String) =
        container.startProcessInContainer {
            understandingExecRequest(this, args, description, timeoutSeconds = 120)
        }.awaitForProcessFinish()
}

/**
 * Builds one in-container command, as ONE expression.
 *
 * `ExecContainerProcessRequest` is an immutable builder: every method returns a COPY, and the block
 * passed to `startProcessInContainer` contributes only the value of its last expression. Writing the
 * calls as separate statements therefore discards all but the last — the process runs as
 * `docker exec … bash -c ''`, an empty command that exits 0.
 *
 * That is not a hypothetical. It is what this experiment actually shipped: the budget counters read
 * back as zero while the in-container hook was correctly refusing calls, and — far worse — the
 * pristine check reported PRISTINE off an empty `git status`, so "the tree was untouched" was never
 * verified at all. Both failures are silent and both look like data.
 *
 * A named function so the chaining cannot be undone by an innocent-looking edit, and so it can be
 * unit-tested without a container: [UnderstandingHarnessTest] asserts the request really carries its
 * arguments.
 */
fun understandingExecRequest(
    base: ExecContainerProcessRequest,
    args: List<String>,
    description: String,
    timeoutSeconds: Long,
): ExecContainerProcessRequest {
    require(args.isNotEmpty()) { "an in-container command with no arguments would run an empty shell" }
    return base.args(args).timeoutSeconds(timeoutSeconds).description(description)
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
    onRawTranscript: (String) -> Unit = {},
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
            eagerMcpTools = withMcp,
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

        // `rawStdout`, never `stdout`. `AiProcessResult` carries both: `stdout` has been through
        // `AgentProgressOutputFilter`, which turns the NDJSON into a human-readable console stream and
        // drops the terminal `result` event — the one event that carries the final message. Every other
        // parser in the arena already reads `rawStdout` (`ArenaTestRunner.evaluate` does); this phase
        // did not, and that alone cost twenty paid Opus runs across two waves, each reporting "the
        // research run produced no final message" while its note sat complete in the build log.
        val rawStdout = resolveAgentRawOutput(
            runDir = session.runDirInContainer,
            agentName = "claude",
            fallbackStdout = agentResult.rawStdout,
        )
        // Handed over before anything can fail: the acquisition-curve family reads the WHOLE transcript,
        // not just the note, and a research run whose note was rejected still carries a usable
        // trajectory. Publishing it only after the checks below would throw away the expensive half of
        // the run to save the cheap one.
        onRawTranscript(rawStdout)
        session.runDirInContainer.resolve("$noteId.ndjson").writeText(rawStdout)
        val metrics = collectRunMetrics(
            runDir = session.runDirInContainer,
            agentName = "claude",
            fallbackStdout = agentResult.rawStdout,
        )
        val usage = gate.usage()
        val tools = gate.toolLog()
        val pristine = readUnderstandingPristineVerdict(session.scope, projectDir)
        val note = extractUnderstandingNote(
            finalMessage = decodeAgentFinalResponse(rawStdout),
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
        if (note.truncated) {
            // Loud, and NOT a failed check: the run is paid for and its note is still evidence about
            // the arm. But it must never be fed to a downstream cell as if the model had written it —
            // the tail past the limit was chosen by this harness, not by the agent, and the 1 000-char
            // round showed the arms separating on where that cut landed. Re-run the cell instead.
            println(
                "[UNDERSTANDING] OVERRUN: the note is ${note.originalChars} characters against a limit " +
                    "of $noteLimitChars, so ${note.originalChars - noteLimitChars} characters the agent " +
                    "wrote were cut by the harness. Do NOT commit this note for a downstream cell — " +
                    "re-run this research cell."
            )
        }
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
    /**
     * How many repository interactions this cell is allowed, or null for the unbudgeted shape the
     * note-bottleneck rounds ran.
     *
     * Null is not a default worth keeping for new work. An unbudgeted downstream agent does not read a
     * note, it re-derives it: the first floor anchor of this case reached seven of eight assertions
     * with NO note at all, on eighty-nine interactions, against a case whose own shell audit says ten
     * good commands reach four fifths of the checklist. Under that shape a note cannot be measured,
     * because nothing it could contain changes what the agent can find out for itself.
     */
    budget: Int? = null,
): UnderstandingDownstreamOutcome {
    val note = condition.noteText(case)
    println(
        "[UNDERSTANDING-DOWN] cell case=${case.instanceId} condition=${condition.label} " +
            "replicate=$replicate note=${note?.length ?: 0} chars budget=${budget ?: "unlimited"}"
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

        // Installed AFTER every piece of harness work, exactly as the research cell does it: the
        // reactor install is a build the HARNESS runs, and charging the agent for it would price a
        // Maven download against the note.
        val gate = budget?.let { allowance ->
            UnderstandingResearchGate(
                container = session.scope,
                recordDir = "${session.guestRunDir()}/downstream",
                budget = allowance,
                eagerMcpTools = false,
                exemptTools = UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS,
                exhaustedMessage = UNDERSTANDING_DOWNSTREAM_BUDGET_EXHAUSTED_MESSAGE,
            ).also { installed -> installed.install(claude) }
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
            promptBuilder = { dir -> buildUnderstandingDownstreamPrompt(case, dir, note, budget) },
            enforceFirstCallProjectMarker = false,
        )

        // Read before the oracle patch is applied and before anything can throw: the accounting is the
        // cell's other half, and a cell that lost its grading still answers "did the wall arrive".
        val usage = gate?.usage()
        // No `budget != null` here: the gate exists only when the budget does, and the compiler knows
        // it — spelling the implication out again is a warning, not a safety net.
        if (gate != null && usage != null) {
            println(
                "[UNDERSTANDING-DOWN] budget: ${usage.describe(budget)}; tools " +
                    "${gate.toolLog().groupingBy { it }.eachCount()}"
            )
        }

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
            return UnderstandingDownstreamOutcome(
                success = null,
                verdict = "LOST oracle-patch-conflict",
                oracleTestsPassed = 0,
                oracleTestsTotal = case.oracleTestCount,
                cost = UnderstandingCellCost(null, null, null),
                budget = budget,
                budgetUsed = usage?.used,
                budgetDenied = usage?.denied,
            )
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
                mavenAlsoMakeDependencies = case.gradingBuildsDependencyClosure,
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
            fallbackStdout = result.agentResult.rawStdout,
        )
        val success = verification?.let { it.objectiveSuccess && !it.failToPassTampered }
        val verdict = when {
            metrics.apiTransportError != null -> "LOST transport ${metrics.apiTransportError}"
            success == null -> "LOST ungraded"
            success -> "Y=1"
            else -> "Y=0"
        }
        val cost = UnderstandingCellCost(
            usd = metrics.tokenUsage?.costUsd,
            agentSeconds = result.agentDurationMs / 1000,
            outputTokens = metrics.tokenUsage?.outputTokens,
        )
        println(
            understandingDownstreamLine(
                case = case,
                condition = condition,
                replicate = replicate,
                cost = cost,
                verdict = verdict,
            )
        )
        val outcome = UnderstandingDownstreamOutcome(
            success = if (verdict.startsWith("LOST")) null else success,
            verdict = verdict,
            oracleTestsPassed = oracleAssertionsPassed(verification, case),
            oracleTestsTotal = case.oracleTestCount,
            compiled = verification?.compiled,
            cost = cost,
            toolCalls = extractToolCallStats(result.agentResult.rawStdout)?.totalToolCalls,
            budget = budget,
            budgetUsed = usage?.used,
            budgetDenied = usage?.denied,
        )
        // Only for the acquisition family. The note-bottleneck rounds published their tables off the
        // line above and are not re-read; a second verdict line under their cells would mean two
        // greppable answers to the same question, which is how an aggregate quietly double-counts.
        if (case.instanceId.startsWith("acquisition__")) {
            println(acquisitionDownstreamLine(case.instanceId, condition, replicate, outcome))
        }
        return outcome
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
 * How many of the oracle's assertions the agent's tree satisfies, or null when nothing could run.
 *
 * Read off surefire's own counters rather than off the pass/fail verdict, because the question this
 * round asks is how much work a note left undone and the verdict cannot answer it: seven assertions of
 * eight and a module that does not compile are the same `Y=0`.
 *
 * NULL for a tree that did not compile, and that distinction is the repair the third downstream round
 * paid for. Twelve note cells reported zero of ten obligations there and not one of them had failed an
 * assertion — every one failed `javac`. Averaging those zeros re-created, one level below the
 * assertions, exactly the cascade the oracles had been rebuilt to remove: N independent axes collapsing
 * into a single boolean. Compilation is now its OWN diagnostic — see [ArenaVerificationResult.compiled]
 * — and the obligations of a tree that never built are unmeasured, not zero.
 *
 * Everything else unknown still collapses to zero passed, deliberately. A cell that was never graded
 * and an agent that rewrote the oracle it is judged by are not evidence that anything worked, and both
 * would otherwise enter an average as a missing value that quietly raises it.
 */
fun oracleAssertionsPassed(verification: ArenaVerificationResult?, case: UnderstandingCase): Int? {
    if (verification == null || verification.failToPassTampered) return 0
    if (verification.compiled == false) return null
    val ran = verification.perClass.sumOf { it.testsRun - it.failures - it.errors }
    return ran.coerceIn(0, case.oracleTestCount)
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
