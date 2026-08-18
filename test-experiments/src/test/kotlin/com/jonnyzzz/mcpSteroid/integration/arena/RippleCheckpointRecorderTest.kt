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
    fun `hook script counts every call but commits only at the target steps`() {
        val script = checkpointHookScript(
            gitDir = "/checkpoints/.git", workTree = "/work/keycloak",
            counterFile = "/checkpoints/steps", targetSteps = listOf(2, 6, 11, 17, 24),
        )
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("--git-dir=/checkpoints/.git"))
        assertTrue(script.contains("--work-tree=/work/keycloak"))
        assertTrue(script.contains("/checkpoints/steps")) { "the counter is the source of n" }
        listOf("2", "6", "11", "17", "24").forEach { step ->
            assertTrue(script.contains("step-$step")) { "no tag for step $step" }
        }
        assertFalse(script.contains("git -C /work/keycloak")) { "the project's own .git must stay untouched" }
    }

    @Test
    fun `hook script never writes stdout and never fails the run`() {
        val script = checkpointHookScript(
            gitDir = "/checkpoints/.git", workTree = "/work/keycloak",
            counterFile = "/checkpoints/steps", targetSteps = listOf(2),
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
     * counter really advances on every invocation, that exactly one snapshot lands at the target step
     * and holds the state as of THAT step, that the project's own repository is left alone, and that
     * nothing at all reaches stdout. Each of those is otherwise discovered by reading the artifacts of
     * a finished Opus capture — the most expensive place in this pilot to find a bug.
     *
     * The hook is deliberately started from OUTSIDE the work tree, because a hook process inherits the
     * agent CLI's working directory and nothing guarantees what that is.
     */
    @Test
    fun `the generated script counts every run and snapshots once into the shadow repository`(@TempDir tmp: Path) {
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
        script.writeText(checkpointHookScript(gitDir, work.toString(), counterFile, listOf(2)))
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
        assertEquals(listOf("step-0", "step-2"), tags.sorted()) { "snapshots outside the schedule: $tags" }

        val patch = git(tmp, "--git-dir=$gitDir", "diff", "step-0", "step-2")
        assertTrue(patch.contains("after the first tool call")) { "the snapshot missed the edit:\n$patch" }
        assertFalse(patch.contains("written after the snapshot")) { "the snapshot leaked a later state:\n$patch" }

        assertEquals("", git(work, "diff", "--cached", "--name-only").trim()) {
            "the instrument staged files in the project's own repository"
        }
    }

    @Test
    fun `metadata normalizes by the actual step count, not by the assumed one`() {
        val json = Json.parseToJsonElement(
            RippleCheckpointRecorder.metadataJson(
                case = "ripple__keycloak__rename-method-wide", arm = "mcp",
                model = "claude-opus-5", expectedSteps = 32, actualSteps = 29,
                steps = listOf(2, 6, 11, 17, 24),
            )
        ).jsonObject
        assertEquals(32, json["expectedSteps"]!!.jsonPrimitive.int)
        assertEquals(29, json["actualSteps"]!!.jsonPrimitive.int)
        val positions = json["checkpoints"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(2, 6, 11, 17, 24), positions.map { it["step"]!!.jsonPrimitive.int })
        assertEquals(2.0 / 29.0, positions[0]["position"]!!.jsonPrimitive.double, 1e-9)
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
