/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.readFromContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `PostToolUse` hook that turns a capture run into a counted, snapshotted trajectory.
 *
 * Two jobs, deliberately unequal in cost. **Counting** happens on EVERY tool call, so the counter's
 * final value is the run's `n` — the number every reported position is normalized by. **Snapshotting**
 * happens only at [targetSteps]: `add -A` over Keycloak is a full tree scan, and doing one per tool
 * call would put tens of them inside the agent loop this run exists to measure faithfully.
 *
 * Three properties are non-negotiable, and each of them cost a real failure somewhere to learn:
 *
 * - **The snapshots go to a SHADOW git dir.** The agent may run `git status`/`git diff` on the
 *   project itself, and a solution is graded from the project's own tree; an instrument that stages
 *   files or writes commits there would change the thing being measured.
 * - **Every byte goes to stderr.** The hook runs as a child of the agent CLI, whose stdout is the
 *   JSON-RPC channel — one stray stdout byte corrupts the protocol and kills the run.
 * - **`exit 0` unconditionally.** A hook that fails hard would abort a $2 Opus run; a missing tag is
 *   visible later, in [RippleCheckpointRecorder.exportPatch], and is a far cheaper failure.
 *
 * One `case` arm per target step, each naming its own tag: the tag names are then part of the text
 * that can be asserted before the run instead of being assembled at run time, when nobody is reading.
 *
 * The counter is a read-modify-write of one file, so two tool calls that complete in the same instant
 * can share a number. Nothing corrects for that: the counter is what the instrument observed, and the
 * positions in the report are normalized by that same number, so an undercount shifts the whole curve
 * consistently instead of mislabeling one checkpoint.
 */
fun checkpointHookScript(
    gitDir: String,
    workTree: String,
    counterFile: String,
    targetSteps: List<Int>,
): String {
    require(targetSteps.isNotEmpty()) { "a hook with no target steps counts a trajectory it never records" }
    require(targetSteps == targetSteps.sorted() && targetSteps.distinct() == targetSteps) {
        "target steps must be strictly increasing and distinct: $targetSteps"
    }
    val arms = targetSteps.joinToString("\n") { step -> "$step) snapshot \"step-$step\" ;;" }
    return """
        #!/bin/sh
        # Count this tool call, then snapshot the work tree only at the precomputed checkpoint steps.
        # The shadow git dir keeps the project's own repository untouched, everything goes to stderr so
        # no byte can reach the agent's JSON-RPC channel, and the exit code is always 0 so a broken
        # instrument cannot kill the agent run. See checkpointHookScript's docs for why.
        n=${'$'}(( ${'$'}(cat $counterFile 2>/dev/null || echo 0) + 1 ))
        echo "${'$'}n" > $counterFile

        snapshot() {
          cd $workTree || return 0
          git --git-dir=$gitDir --work-tree=$workTree add -A >&2
          git --git-dir=$gitDir --work-tree=$workTree commit --allow-empty -q -m "${'$'}1" >&2
          git --git-dir=$gitDir --work-tree=$workTree tag "${'$'}1" >&2
        }

        case "${'$'}n" in
${arms.prependIndent("          ")}
        esac
        exit 0
    """.trimIndent() + "\n"
}

/**
 * The Claude Code settings file that runs [scriptPath] after every tool call.
 *
 * `matcher = "*"` is the measurement: `n` must count the agent's WHOLE trajectory, and a matcher that
 * named tools would silently drop every call the list forgot — including the MCP tools, whose names
 * differ per arm, which would make the two arms' step counts incomparable.
 */
fun checkpointHookSettingsJson(scriptPath: String): String {
    val settings = buildJsonObject {
        putJsonObject("hooks") {
            putJsonArray("PostToolUse") {
                addJsonObject {
                    put("matcher", "*")
                    putJsonArray("hooks") {
                        addJsonObject {
                            put("type", "command")
                            put("command", scriptPath)
                        }
                    }
                }
            }
        }
    }
    return checkpointJson.encodeToString(JsonObject.serializer(), settings)
}

private val checkpointJson = Json { prettyPrint = true }

/**
 * Records one capture run: how many tool calls it took, and what the work tree looked like at the
 * checkpoint positions of that trajectory.
 *
 * Everything lives NEXT TO [gitDir] — the counter, the hook script, the exported patches, the
 * metadata — so pointing [gitDir] inside the session's run dir is all it takes to have the whole
 * recording collected as a test artifact. The default sits under [DEFAULT_CHECKPOINT_DIR] and is meant
 * for the preflight, which throws its container away.
 *
 * [case], [arm] and [model] are the identity the exported metadata carries. They are not needed to
 * record a run, only to describe one, so they stay optional and [exportMetadata] refuses to write a
 * nameless artifact rather than emitting empty strings into a published measurement.
 */
class RippleCheckpointRecorder(
    private val container: ContainerDriver,
    private val projectDir: String,
    private val targetSteps: List<Int>,
    private val gitDir: String = "$DEFAULT_CHECKPOINT_DIR/.git",
    private val case: String = "",
    private val arm: String = "",
    private val model: String = "",
) {
    private val checkpointDir: String = gitDir.substringBeforeLast('/')
    private val counterFile: String = "$checkpointDir/steps"
    private val scriptPath: String = "$checkpointDir/snapshot.sh"

    /**
     * Prepares the shadow repository, tags the pristine tree as `step-0`, and hands the hook to
     * [claude] — in that order, because the hook may fire on the agent's very first tool call and a
     * snapshot taken before `step-0` exists would have nothing to be a diff against.
     */
    fun install(claude: DockerClaudeSession) {
        println("[CHECKPOINT] installing recorder: gitDir=$gitDir workTree=$projectDir steps=$targetSteps")
        container.mkdirs(checkpointDir).assertExitCode(0) { "Failed to create $checkpointDir: $stderr" }

        // Both repositories need a safe.directory entry, for the reason GitDriver.cloneFromCachedBare
        // documents at length: the run-dir bind mount is owned by the host uid while git inside the
        // container runs as `agent` (uid 1000), Linux bind mounts do not remap uids, and `-c
        // safe.directory=…` on the command itself is NOT honoured for the ownership check by the git
        // builds on the TC agents — only a persisted `git config --global --add` is.
        registerSafeDirectory(gitDir)
        registerSafeDirectory(projectDir)

        exec("git init --bare $gitDir", listOf("git", "init", "--bare", gitDir))
            .assertExitCode(0) { "Failed to init the shadow repository $gitDir: $stderr" }
        // A bare repository refuses every index operation, and `add`/`commit` through `--work-tree` are
        // index operations. The repository is otherwise exactly a bare one — it has no work tree of its
        // own and never checks anything out — so clearing the flag is what makes `--work-tree` usable.
        git("config", "core.bare", "false")
        // Without an identity, `commit` fails with "Please tell me who you are". Set LOCALLY: a global
        // identity would also apply to the git the agent itself runs in the project.
        git("config", "user.email", "ripple-checkpoints@mcp-steroid.invalid")
        git("config", "user.name", "Ripple checkpoint recorder")

        snapshot("step-0")

        container.writeFileInContainer(
            scriptPath,
            checkpointHookScript(gitDir, projectDir, counterFile, targetSteps),
            executable = true,
        )
        // The script is staged from the host, so its mode and owner come from `docker cp`; the agent
        // that has to EXECUTE it is a different uid. Checked from inside the container, because a hook
        // the agent cannot execute produces exactly the same artifacts as a run with no hook at all.
        exec("test -x $scriptPath", listOf("test", "-x", scriptPath))
            .assertExitCode(0) { "The hook script $scriptPath is not executable by the container user" }
        // Seeded from INSIDE the container so the file belongs to the user the hook runs as — a
        // host-written counter can end up unwritable for the agent, and then n stays 0 all run.
        exec("seed $counterFile", listOf("sh", "-c", "echo 0 > $counterFile"))
            .assertExitCode(0) { "Failed to seed the step counter $counterFile: $stderr" }

        claude.useSettings(checkpointHookSettingsJson(scriptPath))
    }

    /**
     * The run's `n`: how many tool calls the hook saw. Read from the counter file rather than derived
     * from the commit count — the commits are only the five snapshots, so counting them would report
     * the schedule back instead of the trajectory.
     */
    fun stepCount(): Int {
        val raw = container.readFromContainer(counterFile).trim()
        return raw.toIntOrNull() ?: error("The step counter $counterFile does not hold a number: '$raw'")
    }

    /**
     * The diff from the pristine tree to the snapshot at [step], also written next to [gitDir] as
     * `step-<step>.patch` so the run dir carries the state a probe will be started from.
     *
     * A missing tag fails loudly. An absent snapshot and a snapshot in which the agent had changed
     * nothing yet both produce an empty diff, and the first is a broken instrument while the second is
     * a real, publishable measurement — so they must never arrive at the report as the same thing.
     */
    fun exportPatch(step: Int): String {
        val tag = "step-$step"
        val tagExists = exec(
            "git rev-parse $tag",
            listOf("git", "--git-dir=$gitDir", "rev-parse", "--verify", "--quiet", "refs/tags/$tag"),
        )
        check(tagExists.exitCode == 0) {
            "The capture run has no $tag snapshot in $gitDir — the run ended before step $step, or the " +
                "hook never fired there. Reported patches must not be empty for that reason."
        }

        val diff = exec(
            "git diff step-0 $tag",
            listOf("git", "--git-dir=$gitDir", "--work-tree=$projectDir", "diff", "step-0", tag),
            timeoutSeconds = GIT_TREE_TIMEOUT_SECONDS,
        ).assertExitCode(0) { "Failed to diff step-0..$tag in $gitDir: $stderr" }

        val patch = diff.stdout
        container.writeFileInContainer("$checkpointDir/${patchFileName(step)}", patch)
        println("[CHECKPOINT] exported $tag: ${patch.length} chars -> $checkpointDir/${patchFileName(step)}")
        return patch
    }

    /**
     * Writes `checkpoints.json` next to [gitDir] and returns it, so the patches are never read without
     * the step count they have to be normalized by.
     */
    fun exportMetadata(nActual: Int): String {
        require(case.isNotBlank() && arm.isNotBlank() && model.isNotBlank()) {
            "metadata needs the capture identity: construct RippleCheckpointRecorder with case, arm " +
                "and model (got case='$case', arm='$arm', model='$model')"
        }
        val json = metadataJson(
            case = case,
            arm = arm,
            model = model,
            expectedSteps = RIPPLE_EXPECTED_STEPS,
            actualSteps = nActual,
            steps = targetSteps,
        )
        container.writeFileInContainer("$checkpointDir/$METADATA_FILE_NAME", json)
        println("[CHECKPOINT] exported $checkpointDir/$METADATA_FILE_NAME for n=$nActual")
        return json
    }

    private fun snapshot(tag: String) {
        worktreeGit("add", "-A")
        worktreeGit("commit", "--allow-empty", "-q", "-m", tag)
        worktreeGit("tag", tag)
        println("[CHECKPOINT] tagged $tag in $gitDir")
    }

    private fun registerSafeDirectory(path: String) {
        exec(
            "git config safe.directory $path",
            listOf("git", "config", "--global", "--add", "safe.directory", path),
        ).assertExitCode(0) { "Failed to register safe.directory=$path: $stderr" }
    }

    private fun git(vararg args: String) {
        exec(
            "git ${args.joinToString(" ")}",
            listOf("git", "--git-dir=$gitDir") + args,
        ).assertExitCode(0) { "Failed: git --git-dir=$gitDir ${args.joinToString(" ")}: $stderr" }
    }

    private fun worktreeGit(vararg args: String) {
        exec(
            "git ${args.joinToString(" ")} in $projectDir",
            listOf("git", "--git-dir=$gitDir", "--work-tree=$projectDir") + args,
            timeoutSeconds = GIT_TREE_TIMEOUT_SECONDS,
        ).assertExitCode(0) { "Failed: git ${args.joinToString(" ")} over $projectDir: $stderr" }
    }

    private fun exec(
        description: String,
        args: List<String>,
        timeoutSeconds: Long = GIT_CONFIG_TIMEOUT_SECONDS,
    ): ProcessResult = container.startProcessInContainer {
        this
            .args(args)
            .timeoutSeconds(timeoutSeconds)
            .quietly()
            .description(description)
    }.awaitForProcessFinish()

    companion object {
        /**
         * Where a throwaway recording goes when the caller does not choose a directory.
         *
         * Under `/tmp` and not at the filesystem root: the agent container runs as an unprivileged user,
         * so `mkdir /checkpoints` fails with `Permission denied` — measured, not assumed (TeamCity build
         * 1034576458 died exactly there, before the hook could ever fire).
         */
        const val DEFAULT_CHECKPOINT_DIR: String = "/tmp/ripple-checkpoints"

        /** The metadata file [exportMetadata] writes next to the shadow git dir. */
        const val METADATA_FILE_NAME: String = "checkpoints.json"

        /** A whole-tree git operation over Keycloak — an `add -A` there is minutes, not seconds. */
        private const val GIT_TREE_TIMEOUT_SECONDS: Long = 600

        private const val GIT_CONFIG_TIMEOUT_SECONDS: Long = 30

        /** The name [exportPatch] writes the snapshot of [step] under. */
        fun patchFileName(step: Int): String = "step-$step.patch"

        /**
         * What the recording means, as JSON: the checkpoint steps of this run and where along the
         * trajectory each of them fell.
         *
         * `position = step / actualSteps` — normalized by the MEASURED step count, never by
         * [expectedSteps]. The schedule had to be computed before the run from the arm's historical
         * mean, so a run that ends at 29 steps has its `24` at 83%, not at the 75% the schedule
         * assumed. [expectedSteps] is kept in the artifact for exactly that reason: the difference
         * between assumed and measured is what a reader needs to judge the run's representativeness.
         */
        fun metadataJson(
            case: String,
            arm: String,
            model: String,
            expectedSteps: Int,
            actualSteps: Int,
            steps: List<Int>,
        ): String {
            require(actualSteps > 0) { "a trajectory of $actualSteps steps has no positions to normalize" }
            val metadata = buildJsonObject {
                put("case", case)
                put("arm", arm)
                put("model", model)
                put("expectedSteps", expectedSteps)
                put("actualSteps", actualSteps)
                putJsonArray("checkpoints") {
                    steps.forEach { step ->
                        addJsonObject {
                            put("step", step)
                            put("position", step.toDouble() / actualSteps)
                            put("patch", patchFileName(step))
                        }
                    }
                }
            }
            return checkpointJson.encodeToString(JsonObject.serializer(), metadata)
        }
    }
}
