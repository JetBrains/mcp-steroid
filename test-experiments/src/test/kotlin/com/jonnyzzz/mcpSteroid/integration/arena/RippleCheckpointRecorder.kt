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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The `PostToolUse` hook that turns a capture run into a counted, snapshotted trajectory.
 *
 * Counting AND snapshotting happen on every tool call. The pilot's grid is cut over the run's MEASURED
 * edit phase — the [RIPPLE_CHECKPOINT_FRACTIONS] even fractions between the first write and the final
 * state — and neither `n` nor the first write is known until it is over, so a hook that snapshots a
 * schedule computed in advance can only be right by luck. It was not: the two captures of 2026-08-18
 * came in at 23 and 51 tool calls against an assumed 32, which left the mcp arm without its fifth state
 * (the run ended before that step) and put every published position at a percentage nobody had chosen.
 * Recording every step decouples the instrument from the guess: [RippleCheckpointRecorder.plan] cuts the
 * grid afterwards, over the phase that actually happened, and it reads WHICH steps changed the work
 * tree — which is both how the first write is found and how a flat stretch of the trajectory is
 * published as such instead of being mistaken for progress.
 *
 * The hook also PERSISTS ITS OWN STDIN, and that is the only way the round-2 denominators exist at all.
 * The CLI hands a `PostToolUse` hook a JSON object naming the tool, its input, its response and the
 * path of the session transcript; nothing else in a capture records WHICH tool a step was, and the
 * streamed build log cannot be used as a substitute — summing the `usage` of every `assistant` event of
 * the round-1 mcp capture (build `1035363501`) yields 642 output tokens against the 59 164 its terminal
 * `result` event reports, so per-step upstream work is unrecoverable from the log. The record is
 * truncated with `head -c` because a `tool_response` carrying a whole file would otherwise be written
 * once per tool call.
 *
 * Three properties are non-negotiable, and each of them cost a real failure somewhere to learn:
 *
 * - **The snapshots go to a SHADOW git dir.** The agent may run `git status`/`git diff` on the
 *   project itself, and a solution is graded from the project's own tree; an instrument that stages
 *   files or writes commits there would change the thing being measured.
 * - **Every byte goes to stderr.** The hook runs as a child of the agent CLI, whose stdout is the
 *   JSON-RPC channel — one stray stdout byte corrupts the protocol and kills the run. The stdin record
 *   obeys the same rule: it is redirected into a file, never echoed.
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
    recordDir: String = counterFile.substringBeforeLast('/'),
): String = """
    #!/bin/sh
    # Count this tool call, record what the CLI told the hook about it, and snapshot the work tree
    # under the tag of that step.
    # The shadow git dir keeps the project's own repository untouched, everything goes to stderr so
    # no byte can reach the agent's JSON-RPC channel, and the exit code is always 0 so a broken
    # instrument cannot kill the agent run. See checkpointHookScript's docs for why.
    n=${'$'}(( ${'$'}(cat $counterFile 2>/dev/null || echo 0) + 1 ))
    echo "${'$'}n" > $counterFile

    # The hook's stdin is the ONLY place the tool identity and the transcript path exist. Truncated,
    # because a tool_response can hold a whole file; failures are announced on stderr rather than
    # swallowed, and never abort the run.
    head -c $HOOK_RECORD_MAX_BYTES > $recordDir/step-${'$'}n$HOOK_RECORD_SUFFIX ||
        echo "checkpoint hook: could not record stdin of step ${'$'}n" >&2

    cd $workTree || exit 0
    git --git-dir=$gitDir --work-tree=$workTree add -A >&2
    git --git-dir=$gitDir --work-tree=$workTree commit --allow-empty -q -m "step-${'$'}n" >&2
    git --git-dir=$gitDir --work-tree=$workTree tag "step-${'$'}n" >&2
    exit 0
""".trimIndent() + "\n"

/**
 * How much of the hook's stdin is kept per tool call.
 *
 * A `tool_response` can carry a whole file, and the record is written once per tool call of a 60-step
 * run, so the payload is capped. 20 000 bytes keeps the fields the analysis reads — `session_id`,
 * `transcript_path`, `cwd`, `hook_event_name`, `tool_name` and the head of `tool_input` — which the CLI
 * emits before the response.
 */
const val HOOK_RECORD_MAX_BYTES: Int = 20_000

/** The suffix of a per-step hook record, shared by the script that writes one and the code that reads it. */
const val HOOK_RECORD_SUFFIX: String = ".hook.json"

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
     * How large each exported patch turned out to be, remembered so [exportMetadata] can publish it.
     *
     * `patchChars` is what tells a reader of `checkpoints.json` which checkpoints hold the pristine
     * tree without diffing anything, and re-reading each patch out of the container to measure it would
     * be one more whole-tree exec per checkpoint inside the capture build's wall time.
     */
    private val exportedPatchChars: MutableMap<Int, Int> = linkedMapOf()

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
        exportedPatchChars[step] = patch.length
        println("[CHECKPOINT] exported $tag: ${patch.length} chars -> $checkpointDir/${patchFileName(step)}")
        return patch
    }

    /**
     * A patch for EVERY step the hook snapshotted, not only for the grid.
     *
     * Round 1 exported the ten grid fractions and nothing else, which fixed the checkpoint set to a
     * schedule chosen before the trajectory existed. Round 2 selects its states by a pre-registered rule
     * over the whole trajectory (`REPLICATION-2.md`), and that rule can only be applied to steps whose
     * state was exported. The case is a single Spring service, so a whole-tree diff per tool call is
     * cheap — this is not affordable on Keycloak and is not done there.
     *
     * Steps the counter saw but no snapshot exists for are REPORTED and skipped rather than throwing:
     * the last tool call of a killed run can leave a counter increment without a tag, and losing a paid
     * capture over its final step would be the expensive kind of correctness.
     */
    fun exportEveryStepPatch(nActual: Int): List<Int> {
        val snapshotted = stepTreeIds().keys.filter { it in 1..nActual }.sorted()
        val missing = (1..nActual).filterNot { it in snapshotted }
        if (missing.isNotEmpty()) {
            println(
                "[CHECKPOINT] no snapshot for steps $missing of $nActual — those states cannot be " +
                    "exported and every rule computed over the trajectory must treat them as absent"
            )
        }
        snapshotted.forEach { exportPatch(it) }
        println("[CHECKPOINT] exported ${snapshotted.size} of $nActual step patches")
        return snapshotted
    }

    /** The steps the hook left a stdin record for, read in ONE `ls` of the checkpoint directory. */
    fun hookRecordSteps(): Set<Int> {
        val listing = exec(
            "ls $checkpointDir",
            listOf("sh", "-c", "ls $checkpointDir"),
        )
        // An empty directory is not an error here: the caller decides what a recording with no hook
        // records means, and for the preflight that decision is a hard failure.
        return parseHookRecordSteps(listing.stdout)
    }

    /**
     * Publishes the CLI's own session transcript next to the patches, and reports what it published.
     *
     * The transcript is where the per-message `usage` lives, and it is the only exact source of the
     * upstream denominator round 2 is built on: cumulative Opus OUTPUT tokens before a state. Its path
     * is not guessable — the CLI names it per session — so it is read out of the hook records, which is
     * the whole reason those records exist.
     *
     * Never throws. A capture that produced states but no transcript is still a capture worth keeping;
     * it is the free preflight, not a $3.8 run, that must fail when this comes back empty.
     */
    fun exportStepRecords(): List<String> {
        val steps = hookRecordSteps()
        if (steps.isEmpty()) {
            println("[CHECKPOINT] no hook records in $checkpointDir — no transcript can be published")
            return emptyList()
        }
        // First and last only: one capture is one session, so every record names the same transcript,
        // and reading all 60 records back through `docker exec` would cost more than it can tell.
        val transcripts = listOf(steps.min(), steps.max()).distinct()
            .mapNotNull { step -> parseTranscriptPath(container.readFromContainer(recordPath(step))) }
            .distinct()
        if (transcripts.isEmpty()) {
            println(
                "[CHECKPOINT] the hook records of ${steps.size} steps name no transcript_path, so the " +
                    "per-message usage of this capture cannot be published"
            )
            return emptyList()
        }
        return transcripts.mapIndexedNotNull { index, path ->
            val name = transcriptFileName(index)
            val copy = exec("cp $path", listOf("cp", path, "$checkpointDir/$name"))
            if (copy.exitCode != 0) {
                println("[CHECKPOINT] could not publish the transcript $path: ${copy.stderr}")
                return@mapIndexedNotNull null
            }
            println("[CHECKPOINT] published transcript $path -> $checkpointDir/$name")
            name
        }
    }

    private fun recordPath(step: Int): String = "$checkpointDir/${hookRecordFileName(step)}"

    /**
     * Writes `checkpoints.json` next to [gitDir] and returns it, so the patches are never read without
     * the step count they have to be normalized by.
     *
     * Any planned step whose patch has not been exported yet is exported HERE, before the metadata is
     * written. The two artifacts are only meaningful together — the metadata names states, the patches
     * are those states — and a directory holding one without the other is exactly the half-copied shape
     * `checkpointResourceProblems` refuses. Making the completion the recorder's own job means no caller
     * can publish a metadata file naming a state nobody can start from.
     */
    fun exportMetadata(plan: CheckpointPlan): String {
        require(case.isNotBlank() && arm.isNotBlank() && model.isNotBlank()) {
            "metadata needs the capture identity: construct RippleCheckpointRecorder with case, arm " +
                "and model (got case='$case', arm='$arm', model='$model')"
        }
        // distinct(), because two fractions of a short edit phase round onto the same step: exporting
        // that step twice would be a second whole-tree `git diff` over Keycloak for identical bytes.
        plan.steps.distinct().filterNot { it in exportedPatchChars }.forEach { exportPatch(it) }
        val json = metadataJson(
            case = case,
            arm = arm,
            model = model,
            plan = plan,
            patchChars = exportedPatchChars.filterKeys { it in plan.steps },
        )
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
     * The checkpoints of THIS run: the [RIPPLE_CHECKPOINT_FRACTIONS] even fractions of its edit phase,
     * each carrying the state it holds.
     *
     * The gap check is the reason this is not a one-liner over [selectCheckpoints]. The hook's counter
     * is a read-modify-write, so two tool calls finishing in the same instant can share a number and
     * cost one snapshot; a missing `step-N` would then make the plan publish a state it never saw.
     * Reported, never guessed around — and checked over the WHOLE trajectory rather than over the grid
     * alone, because a gap anywhere means the counter drifted and every later step number is suspect.
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
        println(
            "[CHECKPOINT] plan for n=$nActual firstWriteStep=${plan.firstWriteStep} " +
                "fractions=${plan.fractions}: steps=${plan.steps}"
        )
        plan.checkpoints.filter { it.sameStateAs != null }.forEach {
            println(
                "[CHECKPOINT]   step ${it.step} holds the tree of step ${it.sameStateAs} — the agent " +
                    "wrote nothing in between, which is a measurement of this run and not a defect"
            )
        }
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

        /** The name the hook writes its stdin record of [step] under. */
        fun hookRecordFileName(step: Int): String = "step-$step$HOOK_RECORD_SUFFIX"

        /** The name [exportStepRecords] publishes the [index]-th session transcript under. */
        fun transcriptFileName(index: Int): String = "transcript-$index.jsonl"

        /**
         * The steps a directory listing shows a hook record for.
         *
         * Parsed from a listing rather than probed per step, and pinned by a unit test because the same
         * directory holds `step-N.patch` files whose names differ only by suffix: a parser that
         * confused the two would report a complete set of records for a capture that recorded none.
         */
        fun parseHookRecordSteps(listing: String): Set<Int> = listing.lines()
            .mapNotNull { line ->
                val name = line.trim().substringAfterLast('/')
                if (!name.endsWith(HOOK_RECORD_SUFFIX)) return@mapNotNull null
                name.removeSuffix(HOOK_RECORD_SUFFIX).removePrefix("step-").toIntOrNull()
            }
            .toSet()

        /**
         * The `transcript_path` a hook record names, or `null` when it names none.
         *
         * Records are TRUNCATED at [HOOK_RECORD_MAX_BYTES], so a record whose `tool_response` is large
         * is not valid JSON at all. The field is emitted by the CLI near the head of the payload, well
         * inside the cap, so a truncated record still carries it — hence the fallback: parse as JSON
         * first, and only when that fails read the field out of the text. Returning `null` from the
         * fallback would publish no transcript for exactly the busiest captures.
         */
        fun parseTranscriptPath(record: String): String? {
            val parsed = try {
                Json.parseToJsonElement(record).jsonObject[TRANSCRIPT_PATH_FIELD]?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                println("[CHECKPOINT] hook record is not valid JSON (${e.message}), reading its head instead")
                null
            }
            val path = parsed ?: TRUNCATED_TRANSCRIPT_PATH.find(record)?.groupValues?.get(1)
            return path?.takeIf { it.isNotBlank() }
        }

        /** The field of a `PostToolUse` payload that names the CLI's session transcript. */
        private const val TRANSCRIPT_PATH_FIELD: String = "transcript_path"

        private val TRUNCATED_TRANSCRIPT_PATH = Regex("\"$TRANSCRIPT_PATH_FIELD\"\\s*:\\s*\"([^\"]*)\"")

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
         * What the recording means, as JSON: the measured length of the trajectory, the edit phase the
         * fractions were cut over, and what each fraction held.
         *
         * [CheckpointPlan.firstWriteStep] and [CheckpointPlan.fractions] are written even though the
         * checkpoint list implies them, because a reader must be able to tell where this run stopped
         * reading and started writing: every published `editFraction` is measured from that boundary,
         * and it is unfalsifiable from a bare list of steps.
         *
         * [patchChars] is keyed by step and must cover every planned step; a missing entry throws rather
         * than publishing a zero, because zero is a real and meaningful value here — it is the pristine
         * tree — and an unknown size printed as one would invent a measurement.
         */
        fun metadataJson(
            case: String,
            arm: String,
            model: String,
            plan: CheckpointPlan,
            patchChars: Map<Int, Int>,
        ): String {
            require(plan.n > 0) { "a trajectory of ${plan.n} steps has no positions to normalize" }
            val metadata = buildJsonObject {
                put("case", case)
                put("arm", arm)
                put("model", model)
                put("n", plan.n)
                put("firstWriteStep", plan.firstWriteStep)
                put("fractions", plan.fractions)
                putJsonArray("checkpoints") {
                    plan.checkpoints.forEach { checkpoint ->
                        addJsonObject {
                            put("index", checkpoint.index)
                            put("step", checkpoint.step)
                            put("editFraction", checkpoint.editFraction)
                            put("position", publishedPosition(checkpoint.position))
                            put("tree", checkpoint.stateId)
                            put(
                                "patchChars",
                                patchChars[checkpoint.step] ?: error(
                                    "no patch was exported for step ${checkpoint.step}, so its size is " +
                                        "unknown and cannot be published"
                                ),
                            )
                            put("sameStateAs", checkpoint.sameStateAs)
                        }
                    }
                }
            }
            return checkpointJson.encodeToString(JsonObject.serializer(), metadata)
        }

        /**
         * `step/n` at the four decimals the published files carry.
         *
         * Rounded rather than printed raw because most of these fractions are non-terminating, and a
         * position is a JOIN KEY: the probe echoes it on its verdict line and the aggregator refuses a
         * checkpoint that reports two different positions. Two readers rounding a raw double their own
         * way would split one measured state into two rows of the published table.
         */
        private fun publishedPosition(position: Double): Double =
            BigDecimal(position).setScale(POSITION_DECIMALS, RoundingMode.HALF_UP).toDouble()

        /** Four decimals resolve every step of a trajectory shorter than 10 000 tool calls. */
        private const val POSITION_DECIMALS: Int = 4
    }
}
