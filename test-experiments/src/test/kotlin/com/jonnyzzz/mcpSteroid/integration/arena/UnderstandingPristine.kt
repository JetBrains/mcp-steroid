/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer

/**
 * Paths that a research run may leave behind without being called dirty, and why each is here.
 *
 * These are written by the ENVIRONMENT, not by the agent: the IDE persists its own project metadata
 * while it indexes, and the agent CLI keeps its session state under the project directory. Charging
 * them to the agent would fail every mcp research run for the crime of having an IDE attached — which
 * is the one arm the experiment exists to measure.
 *
 * Everything else counts, including a file the agent wrote outside the source tree: the rule the agent
 * was given is "the tree must be byte-identical", and an instrument that quietly forgave scratch files
 * would let a run smuggle its findings onto disk where the next phase could read them.
 */
val UNDERSTANDING_PRISTINE_IGNORED_PREFIXES: List<String> = listOf(
    ".idea/",
    ".claude/",
    ".mvn/wrapper/maven-wrapper.jar",
)

/** Suffixes of the same kind as [UNDERSTANDING_PRISTINE_IGNORED_PREFIXES]. */
val UNDERSTANDING_PRISTINE_IGNORED_SUFFIXES: List<String> = listOf(".iml")

/**
 * Printed by the pristine check after `git status`, and required to be present in its output.
 *
 * A clean tree and a command that never ran both answer with an empty string, and this experiment
 * already shipped the second one while believing the first: the request builder silently dropped the
 * arguments and every research run was certified pristine off `bash -c ''`. The marker removes the
 * ambiguity — no marker, no verdict.
 */
const val UNDERSTANDING_PRISTINE_MARKER: String = "___pristine-check-completed___"

/**
 * What a `git status --porcelain` of the work tree says about who changed what.
 *
 * [violations] is what invalidates a research run. [ignored] is published next to it rather than
 * dropped, because "the IDE wrote three files" and "the tree was untouched" are different facts and a
 * reader of the record must be able to tell which one they are looking at.
 */
data class UnderstandingPristineVerdict(
    val pristine: Boolean,
    val violations: List<String>,
    val ignored: List<String>,
) {
    fun describe(): String = when {
        pristine && ignored.isEmpty() -> "PRISTINE (nothing changed)"
        pristine -> "PRISTINE (${ignored.size} environment-owned paths ignored: ${ignored.take(5)})"
        else -> "DIRTY — the research run modified ${violations.size} path(s): ${violations.take(10)}"
    }
}

/**
 * Reads the porcelain output into a verdict.
 *
 * `--porcelain=v1 --untracked-files=all` is the required shape: the short format's two status columns
 * are followed by the path, a rename carries `orig -> new`, and untracked files must be listed
 * individually or a whole directory of scratch files hides behind one `??  scratch/` line.
 *
 * Pure so the rules can be pinned by a unit test. The alternative — discovering that the mcp arm's
 * every run is "dirty" because of `.idea/` — costs an Opus run per discovery.
 */
fun understandingPristineVerdict(porcelain: String): UnderstandingPristineVerdict {
    val entries = porcelain.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != UNDERSTANDING_PRISTINE_MARKER }
        // `XY path` or `XY orig -> new`; the arrow form reports the destination, which is what exists now.
        .map { line -> line.substringAfter(' ').trim().substringAfterLast(" -> ").trim().removeSurrounding("\"") }
    val ignored = entries.filter { path ->
        UNDERSTANDING_PRISTINE_IGNORED_PREFIXES.any { path.startsWith(it) } ||
            UNDERSTANDING_PRISTINE_IGNORED_SUFFIXES.any { path.endsWith(it) }
    }
    val violations = entries - ignored.toSet()
    return UnderstandingPristineVerdict(
        pristine = violations.isEmpty(),
        violations = violations,
        ignored = ignored,
    )
}

/**
 * Asks the deployed clone itself what changed under it.
 *
 * The project's OWN git is used, and only for reading: it already knows the base commit and honours the
 * repository's `.gitignore`, so a build's `target/` output cannot be mistaken for an agent edit. A
 * shadow repository would need a whole-tree `add -A` over a Keycloak checkout twice per run, which
 * costs minutes and gigabytes for an answer this one command gives — see [RippleCheckpointRecorder] for
 * why the shadow repository exists there (it snapshots EVERY step) and why it is the wrong instrument
 * here (this phase asks one question, once).
 */
fun readUnderstandingPristineVerdict(container: ContainerDriver, projectDir: String): UnderstandingPristineVerdict {
    // One expression, via [understandingExecRequest]: the request builder is immutable, so separate
    // statements here would send `bash -c ''` and this check would confirm a pristine tree off an empty
    // answer — which is exactly what it did until commit b8e6e4dfa's successor.
    val result = container.startProcessInContainer {
        understandingExecRequest(
            base = this,
            args = listOf(
                "sh", "-c",
                "git -C '$projectDir' status --porcelain=v1 --untracked-files=all && " +
                    "echo $UNDERSTANDING_PRISTINE_MARKER",
            ),
            description = "Research-phase pristine check of $projectDir",
            timeoutSeconds = 600,
        )
    }.awaitForProcessFinish()
    check(result.exitCode == 0) {
        "could not read the work tree state of $projectDir (exit ${result.exitCode}): ${result.stderr}. " +
            "Without it there is no evidence the research run left the tree alone, and a note from an " +
            "unverified run must not be handed to a downstream cell."
    }
    check(result.stdout.contains(UNDERSTANDING_PRISTINE_MARKER)) {
        "the pristine check did not print its completion marker, so `git status` never ran — an empty " +
            "answer would otherwise read as a clean tree. Output was: '${result.stdout.take(200)}'"
    }
    return understandingPristineVerdict(result.stdout)
}
