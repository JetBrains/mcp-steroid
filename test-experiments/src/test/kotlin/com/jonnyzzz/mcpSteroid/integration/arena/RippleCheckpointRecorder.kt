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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `PostToolUse` hook that turns a capture run into a counted, snapshotted trajectory.
 *
 * Counting AND snapshotting happen on every tool call. The pilot's checkpoint positions are
 * `round(n·(i/6)^1.5)` of the run's MEASURED length, and `n` is only known once the run is over — so a
 * hook that snapshots a schedule computed in advance can only be right by luck. It was not: the two
 * captures of 2026-08-18 came in at 23 and 51 tool calls against an assumed 32, which left the mcp arm
 * without its fifth state (the run ended before that step) and put every published position at a
 * percentage nobody had chosen. Recording every step decouples the instrument from the guess:
 * [RippleCheckpointRecorder.plan] picks the five positions afterwards, from the length that actually
 * happened, and it can also see WHICH steps changed the work tree, so no two checkpoints of a curve
 * are the same state.
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
 * The counter is a read-modify-write of one file, so two tool calls that complete in the same instant
 * can share a number. Nothing corrects for that: the counter is what the instrument observed, and the
 * positions in the report are normalized by that same number, so an undercount shifts the whole curve
 * consistently instead of mislabeling one checkpoint. A number reused this way loses one snapshot —
 * `git tag` refuses to move an existing tag — and [RippleCheckpointRecorder.plan] then reports the
 * gap instead of silently probing the wrong state.
 */
fun checkpointHookScript(
    gitDir: String,
    workTree: String,
    counterFile: String,
): String = """
    #!/bin/sh
    # Count this tool call and snapshot the work tree under the tag of that step.
    # The shadow git dir keeps the project's own repository untouched, everything goes to stderr so
    # no byte can reach the agent's JSON-RPC channel, and the exit code is always 0 so a broken
    # instrument cannot kill the agent run. See checkpointHookScript's docs for why.
    n=${'$'}(( ${'$'}(cat $counterFile 2>/dev/null || echo 0) + 1 ))
    echo "${'$'}n" > $counterFile

    cd $workTree || exit 0
    git --git-dir=$gitDir --work-tree=$workTree add -A >&2
    git --git-dir=$gitDir --work-tree=$workTree commit --allow-empty -q -m "step-${'$'}n" >&2
    git --git-dir=$gitDir --work-tree=$workTree tag "step-${'$'}n" >&2
    exit 0
""".trimIndent() + "\n"

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
        println("[CHECKPOINT] installing recorder: gitDir=$gitDir workTree=$projectDir (every step)")
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
            checkpointHookScript(gitDir, projectDir, counterFile),
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
    fun exportMetadata(plan: CheckpointPlan): String {
        require(case.isNotBlank() && arm.isNotBlank() && model.isNotBlank()) {
            "metadata needs the capture identity: construct RippleCheckpointRecorder with case, arm " +
                "and model (got case='$case', arm='$arm', model='$model')"
        }
        val json = metadataJson(case = case, arm = arm, model = model, plan = plan)
        container.writeFileInContainer("$checkpointDir/$METADATA_FILE_NAME", json)
        println("[CHECKPOINT] exported $checkpointDir/$METADATA_FILE_NAME for n=${plan.n}")
        return json
    }

    /**
     * The tree id of every snapshotted step, read in ONE container exec.
     *
     * A `rev-parse step-N^{tree}` per step would be one exec per tool call of a 50-step run, all of it
     * inside the capture build's wall time. `for-each-ref` prints the same ids in one go, and the tree
     * id — not the commit id — is what identifies a STATE: consecutive snapshots of an unchanged work
     * tree are different commits (they are `--allow-empty`) with the very same tree.
     */
    fun stepTreeIds(): Map<Int, String> {
        val listing = exec(
            "git for-each-ref refs/tags",
            listOf(
                "git", "--git-dir=$gitDir", "for-each-ref",
                "--format=%(refname:strip=2) %(tree)", "refs/tags",
            ),
            timeoutSeconds = GIT_TREE_TIMEOUT_SECONDS,
        ).assertExitCode(0) { "Failed to list the snapshot tags of $gitDir: $stderr" }
        return parseStepTreeIds(listing.stdout)
    }

    /**
     * The checkpoints of THIS run: five positions of [nActual], each holding a state no earlier
     * checkpoint held.
     *
     * The gap check is the reason this is not a one-liner over [selectCheckpoints]. The hook's counter
     * is a read-modify-write, so two tool calls finishing in the same instant can share a number and
     * cost one snapshot; a missing `step-N` would then make the selection compare against the wrong
     * state and publish a position for a state it never saw. Reported, never guessed around.
     */
    fun plan(nActual: Int): CheckpointPlan {
        val trees = stepTreeIds()
        val missing = (1 until nActual).filterNot { it in trees }
        check(missing.isEmpty()) {
            "The hook counted $nActual steps but left no snapshot for $missing in $gitDir — the counter " +
                "was shared by tool calls finishing in the same instant. The capture cannot be planned " +
                "without those states."
        }
        val plan = selectCheckpoints(nActual) { step ->
            trees.getValue(step)
        }
        println("[CHECKPOINT] plan for n=$nActual: steps=${plan.steps} nominal=${plan.checkpoints.map { it.nominalStep }}")
        plan.corrections.forEach { println("[CHECKPOINT]   correction: $it") }
        return plan
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
         * Parses `<tag> <tree id>` lines into `step -> tree id`.
         *
         * Split out of [stepTreeIds] so the parsing is decided by a unit test instead of by a $2
         * capture run: a listing misread here yields a full, plausible, wrong set of checkpoints.
         */
        fun parseStepTreeIds(listing: String): Map<Int, String> = listing.lines()
            .mapNotNull { line ->
                val parts = line.trim().split(' ').filter { it.isNotBlank() }
                if (parts.size != 2) return@mapNotNull null
                val step = parts[0].removePrefix("step-").toIntOrNull() ?: return@mapNotNull null
                step to parts[1]
            }
            .toMap()

        /**
         * What the recording means, as JSON: the measured length of the trajectory, the steps probed,
         * and where along the trajectory each of them fell.
         *
         * Both the nominal position and the probed step are written, together with the plan's
         * [CheckpointPlan.corrections]. A checkpoint that had to move (its nominal step held a state
         * already probed) sits deeper in the trajectory than the schedule intended, and a curve read
         * without that fact would look like the schedule everyone expects while measuring something
         * else. The corrections are also how a report shows a curve of fewer than five points without
         * anyone having to diff patches to find out why.
         */
        fun metadataJson(
            case: String,
            arm: String,
            model: String,
            plan: CheckpointPlan,
        ): String {
            require(plan.n > 0) { "a trajectory of ${plan.n} steps has no positions to normalize" }
            val metadata = buildJsonObject {
                put("case", case)
                put("arm", arm)
                put("model", model)
                put("actualSteps", plan.n)
                putJsonArray("checkpoints") {
                    plan.checkpoints.forEach { checkpoint ->
                        addJsonObject {
                            put("index", checkpoint.index)
                            put("nominalStep", checkpoint.nominalStep)
                            put("step", checkpoint.step)
                            put("position", checkpoint.position)
                            put("patch", patchFileName(checkpoint.step))
                        }
                    }
                }
                putJsonArray("corrections") {
                    plan.corrections.forEach { add(it) }
                }
            }
            return checkpointJson.encodeToString(JsonObject.serializer(), metadata)
        }
    }
}
