/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Coverage for the instrument that records a capture run — no Docker, no agent, no API spend.
 *
 * Only one thing here is left to the capture test's preflight, because only a real Claude Code can
 * decide it: whether the CLI fires a `PostToolUse` hook on every tool call. Everything else is
 * decidable offline, and every one of these properties can be wrong while looking right — a hook that
 * snapshots through the project's own `.git` instead of the shadow one, a tag the schedule was never
 * generated for, a settings file that registers the hook for one tool instead of all of them, a
 * report that normalizes positions by the assumed step count rather than the measured one. Each of
 * those yields a full, plausible, wrong measurement, and each would otherwise be found by reading the
 * artifacts of a finished Opus capture.
 *
 * So the shape of the generated text is asserted, AND the generated script is executed by a real
 * `/bin/sh` against a real `git` — see the run test below for what only execution can decide.
 */
class RippleCheckpointRecorderTest {
    @Test
    fun `hook script snapshots every call under the step's own tag`() {
        val script = checkpointHookScript(
            gitDir = "/checkpoints/.git", workTree = "/work/project",
            counterFile = "/checkpoints/steps",
        )
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("--git-dir=/checkpoints/.git"))
        assertTrue(script.contains("--work-tree=/work/project"))
        assertTrue(script.contains("/checkpoints/steps")) { "the counter is the source of n" }
        assertTrue(script.contains("step-\$n")) { "the tag must be the step the hook just counted" }
        assertFalse(script.contains("case ")) { "no schedule may survive in the hook: $script" }
        assertFalse(script.contains("git -C /work/project")) { "the project's own .git must stay untouched" }
    }

    @Test
    fun `hook script never writes stdout and never fails the run`() {
        val script = checkpointHookScript(
            gitDir = "/checkpoints/.git", workTree = "/work/project",
            counterFile = "/checkpoints/steps",
        )
        val gitLines = script.lines().map { it.trim() }.filter { it.startsWith("git ") }
        assertTrue(gitLines.isNotEmpty()) { "the hook does not snapshot at all: $script" }
        gitLines.forEach { line ->
            assertTrue(line.endsWith(">&2")) { "git output would reach the agent's protocol channel: $line" }
        }
        assertTrue(script.trimEnd().endsWith("exit 0")) { "a broken hook must not kill a paid agent run" }
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

    /**
     * The generated script, run by a real `/bin/sh` against a real `git`. No Docker and no agent are
     * needed for it, and the properties it decides are the ones a text assertion cannot reach: that the
     * counter really advances on every invocation, that EVERY step is snapshotted and each snapshot
     * holds the state as of that step, that the project's own repository is left alone, and that nothing
     * at all reaches stdout. Each of those is otherwise discovered by reading the artifacts of a
     * finished Opus capture — the most expensive place in this pilot to find a bug.
     *
     * The hook is deliberately started from OUTSIDE the work tree, because a hook process inherits the
     * agent CLI's working directory and nothing guarantees what that is.
     */
    @Test
    fun `the generated script snapshots every single step into the shadow repository`(@TempDir tmp: Path) {
        val work = tmp.resolve("work")
        val checkpoints = tmp.resolve("checkpoints").createDirectories()
        val gitDir = checkpoints.resolve(".git").toString()
        val counterFile = checkpoints.resolve("steps").toString()
        git(tmp, "init", "-q", "work")
        work.resolve("a.txt").writeText("before\n")

        // The same preparation RippleCheckpointRecorder.install performs in the container: a bare repo
        // with the bare flag cleared, so `--work-tree` may drive its index, and the pristine tree tagged.
        git(tmp, "init", "--bare", "-q", gitDir)
        git(tmp, "--git-dir=$gitDir", "config", "core.bare", "false")
        git(tmp, "--git-dir=$gitDir", "config", "user.email", "ripple-checkpoints@mcp-steroid.invalid")
        git(tmp, "--git-dir=$gitDir", "config", "user.name", "Ripple checkpoint recorder")
        git(tmp, "--git-dir=$gitDir", "--work-tree=$work", "add", "-A")
        git(tmp, "--git-dir=$gitDir", "--work-tree=$work", "commit", "--allow-empty", "-q", "-m", "step-0")
        git(tmp, "--git-dir=$gitDir", "--work-tree=$work", "tag", "step-0")

        val script = checkpoints.resolve("snapshot.sh")
        script.writeText(checkpointHookScript(gitDir, work.toString(), counterFile))
        script.toFile().setExecutable(true)

        val runs = mutableListOf(runHook(script, tmp))
        work.resolve("a.txt").writeText("after the first tool call\n")
        runs += runHook(script, tmp)
        work.resolve("b.txt").writeText("written after the snapshot\n")
        runs += runHook(script, tmp)

        runs.forEachIndexed { index, run ->
            assertEquals(0, run.exitCode) { "call ${index + 1} failed the run: ${run.stderr}" }
            assertEquals("", run.stdout) { "call ${index + 1} wrote to the agent's protocol channel" }
        }
        assertEquals("3", Path.of(counterFile).readText().trim()) { "the counter must see every call" }

        val tags = git(tmp, "--git-dir=$gitDir", "tag", "--list").lines().filter { it.isNotBlank() }
        assertEquals(listOf("step-0", "step-1", "step-2", "step-3"), tags.sorted()) { "missing snapshots: $tags" }

        assertEquals("", git(tmp, "--git-dir=$gitDir", "diff", "step-0", "step-1").trim()) {
            "the first call changed nothing, so its snapshot must equal the pristine tree"
        }

        val second = git(tmp, "--git-dir=$gitDir", "diff", "step-0", "step-2")
        assertTrue(second.contains("after the first tool call")) { "the snapshot missed the edit:\n$second" }
        assertFalse(second.contains("written after the snapshot")) { "the snapshot leaked a later state:\n$second" }

        val third = git(tmp, "--git-dir=$gitDir", "diff", "step-0", "step-3")
        assertTrue(third.contains("written after the snapshot")) { "the last step was not recorded:\n$third" }

        assertEquals("", git(work, "diff", "--cached", "--name-only").trim()) {
            "the instrument staged files in the project's own repository"
        }
    }

    /**
     * The states a plan is built from come out of the shadow repository as tree ids, and equal tree ids
     * are what the selection has to treat as one state. A `git rev-parse step-N^{tree}` per step would
     * be one container exec per tool call, so the recorder reads them all at once — this test pins the
     * parsing of that one listing, including the pristine `step-0` the plan must never probe.
     */
    @Test
    fun `step tree ids are parsed from one listing of the shadow tags`() {
        val trees = RippleCheckpointRecorder.parseStepTreeIds(
            """
            step-0 aaaa111
            step-1 aaaa111
            step-2 bbbb222
            step-10 cccc333
            """.trimIndent()
        )
        assertEquals(mapOf(0 to "aaaa111", 1 to "aaaa111", 2 to "bbbb222", 10 to "cccc333"), trees)
    }

    @Test
    fun `metadata carries the measured n, the probed steps and every correction`() {
        val plan = selectCheckpoints(n = 29) { step -> if (step < 8) "pristine" else "state-$step" }
        val json = Json.parseToJsonElement(
            RippleCheckpointRecorder.metadataJson(
                case = "dpaia__spring__boot__microshop-18", arm = "mcp",
                model = "claude-opus-5", plan = plan,
            )
        ).jsonObject
        assertEquals(29, json["actualSteps"]!!.jsonPrimitive.int)
        val checkpoints = json["checkpoints"]!!.jsonArray.map { it.jsonObject }
        assertEquals(plan.steps, checkpoints.map { it["step"]!!.jsonPrimitive.int })
        assertEquals(listOf(2, 6, 10, 16, 22), checkpoints.map { it["nominalStep"]!!.jsonPrimitive.int })
        assertEquals(plan.steps.first().toDouble() / 29.0, checkpoints[0]["position"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(plan.corrections.size, json["corrections"]!!.jsonArray.size)
    }

    private data class HookRun(val exitCode: Int, val stdout: String, val stderr: String)

    /** Both streams go to files: reading two pipes in sequence deadlocks as soon as one of them fills. */
    private fun runHook(script: Path, workingDir: Path): HookRun {
        val stdout = Files.createTempFile("checkpoint-hook-stdout", ".txt")
        val stderr = Files.createTempFile("checkpoint-hook-stderr", ".txt")
        val exitCode = ProcessBuilder(script.toString())
            .directory(workingDir.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start()
            .waitFor()
        return HookRun(exitCode, stdout.readText(), stderr.readText())
    }

    private fun git(workingDir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${args.joinToString(" ")} in $workingDir failed with $exitCode:\n$output" }
        return output
    }
}
