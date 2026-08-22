/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The environment paragraphs both phases share, verbatim.
 *
 * Shared and not copied: the research agent and the downstream agent look at the SAME tree, and a
 * difference in how the two briefs describe it would show up in the comparison as if it were a
 * difference in what the note taught. The wording is [buildRipplePrompt]'s, which is the version that
 * survived five measured rounds on this repository — including the two rules that were learned the
 * expensive way (never build the whole reactor, never end a turn while a build is running).
 */
fun understandingEnvironmentFacts(case: UnderstandingCase): List<String> {
    val jdkPrefix = "/usr/lib/jvm/temurin-${case.projectJdkVersion}-jdk-"
    return listOf(
        "- Use the project wrapper only: `./mvnw`",
        "- Configured project JDK version: **${case.projectJdkVersion}**",
        "- Resolve a concrete path whose name starts with `$jdkPrefix`, then run",
        "  `JAVA_HOME=<that exact path> ./mvnw ...`. Do not use wildcard JAVA_HOME assignments and",
        "  do not try a lower JDK first.",
        "- This project has many modules. Build one module at a time with",
        "  `./mvnw <goal> -pl <module>`; never ask the build to also build upstream dependencies,",
        "  which exhausts the container's memory.",
        "- The dependencies of every module are already installed locally, so `-pl` works on its own.",
        "- A full build-and-test run across the whole reactor does not fit this task's time budget.",
    )
}

/**
 * The brief of the RESEARCH phase: explore, change nothing, hand the next agent a note.
 *
 * Three things in it are the experiment rather than the wording, and none of them may drift:
 *
 * - **The budget is stated as a number and enforced by a hook.** Stating it is what lets the agent
 *   plan around it (a research agent that does not know it has five interactions spends them like it
 *   has fifty); the hook is what makes the number true. Neither alone is enough — round 3's captures
 *   show agents ignoring soft limits, and a hook without the announcement measures surprise instead
 *   of planning.
 * - **The note is the ONLY output.** No file is written, nothing is implemented, and the note has a
 *   hard character limit, so the phase is an information bottleneck: what survives is what the agent
 *   judged worth passing on, which is exactly the quantity being compared between arms.
 * - **The suggested content is a checklist of QUESTIONS, not a template.** A template would make both
 *   arms produce the same-shaped document and the comparison would be about filling in blanks. The
 *   list names what a reader of the note will need, and leaves the form to the agent.
 *
 * The mcp arm is told that resolved-program tools exist, for the reason [buildRipplePrompt] documents
 * at length: without that sentence the arm labelled "with IDE access" spends its budget on the shell
 * and the comparison measures a coin flip about tool choice. The arm without tools cannot be given the
 * paragraph, because for it it would be false.
 */
fun buildUnderstandingResearchPrompt(
    case: UnderstandingCase,
    projectDir: String,
    withMcp: Boolean,
    budget: Int,
    noteLimitChars: Int,
): String = buildString {
    appendLine("You are working on a large multi-module Java project located at: `$projectDir`")
    appendLine()
    appendLine("## Your role")
    appendLine()
    appendLine("You are NOT solving the task below. Another developer will solve it, working alone,")
    appendLine("from the same repository in its current, unmodified state. That developer has no")
    appendLine("access to you, to your tools or to anything you saw.")
    appendLine()
    appendLine("Your job is to study this repository and leave that developer a short hand-off note.")
    appendLine()
    appendLine("## Hard rules")
    appendLine()
    appendLine("1. Do NOT modify, create, delete or move any file in the repository. The tree must be")
    appendLine("   byte-identical when you finish; this is checked automatically and a run that changed")
    appendLine("   anything is discarded.")
    appendLine("2. Do NOT implement the task, not even partially, and not in a scratch file.")
    appendLine("3. You may use at most **$budget environment interactions** (tool calls that read or")
    appendLine("   query the project). After that every tool call is refused. Plan for that number from")
    appendLine("   the first call: decide what the single most informative question is, ask it, and only")
    appendLine("   then decide the next one.")
    appendLine("4. Your final message must contain the note and nothing else that matters, wrapped in")
    appendLine("   `$UNDERSTANDING_NOTE_OPEN_MARKER` and `$UNDERSTANDING_NOTE_CLOSE_MARKER` markers.")
    appendLine("5. The note must be at most **$noteLimitChars characters**. This is a budget you have to")
    appendLine("   meet yourself: count the characters of your note before you send it, and rewrite it")
    appendLine("   until it fits. A note that overruns is not edited down by an editor who knows what")
    appendLine("   matters — the tail is simply lost mid-sentence, and whatever you saved for the end is")
    appendLine("   the part that never arrives. Decide what earns its characters and drop the rest.")
    appendLine()
    appendLine("## The task the other developer will have to solve")
    appendLine()
    appendLine(case.problemStatement.trim())
    appendLine()
    appendLine("## What makes a good note")
    appendLine()
    appendLine("Write prose for a competent developer who does not know this repository. Do not paste")
    appendLine("tool output, listings, diffs or whole files — a note full of raw output has no room left")
    appendLine("for what you understood. Everything below is worth answering if you know the answer,")
    appendLine("and none of it is a template to fill in mechanically:")
    appendLine()
    appendLine("- which components, files and symbols are relevant, and what each is for;")
    appendLine("- how control or data flows through them;")
    appendLine("- whether something very similar already exists that should simply be imitated, and where;")
    appendLine("- what concretely has to change, and how those changes depend on each other;")
    appendLine("- what is easy to forget — a second place that must be touched, an invariant to preserve;")
    appendLine("- how the result can be verified, and which existing tests are the right pattern;")
    appendLine("- what must NOT be changed.")
    appendLine()
    appendLine("Say plainly when you are unsure about something; a confident wrong pointer costs the")
    appendLine("other developer more than an admitted gap.")
    appendLine()
    appendLine("## Environment Facts")
    appendLine()
    understandingEnvironmentFacts(case).forEach { appendLine(it) }
    appendLine("- Do not run builds or tests. They change nothing in the tree, but they consume your")
    appendLine("  interaction budget and take longer than the budget is worth.")
    if (withMcp) {
        appendLine()
        appendLine("## Available Tools")
        appendLine()
        appendLine("- Besides the shell, this session is connected to tools that expose THIS project as a")
        appendLine("  resolved, fully indexed program: they answer what a declaration is, what an expression")
        appendLine("  resolves to, and where a declaration is really used — as opposed to where its name")
        appendLine("  happens to occur as text. List the tools available to you before you start working.")
        appendLine("- Use them for every question about the code. A grep over this tree answers a different")
        appendLine("  question than the one you need answered.")
    }
    appendLine()
    appendLine("## Output")
    appendLine()
    appendLine("End with your note and nothing after it:")
    appendLine()
    appendLine("    $UNDERSTANDING_NOTE_OPEN_MARKER")
    appendLine("    ...at most $noteLimitChars characters...")
    appendLine("    $UNDERSTANDING_NOTE_CLOSE_MARKER")
}

/**
 * The paragraph that introduces a note to the downstream agent, and the only difference between the
 * three arms' briefs.
 *
 * Deliberately silent about where the note came from: the downstream agent must not learn the arm, the
 * research budget, that a comparison is running, or that an alternative note exists. "Another
 * developer" and "may be useful" is the whole framing — enough to make the note legitimate to use,
 * not so much that the agent defers to it against its own reading of the code.
 */
const val UNDERSTANDING_NOTE_INTRODUCTION: String =
    "Another developer studied this repository before you and left you the note below. It may be " +
        "useful, but it is not authoritative and it may be incomplete or wrong: check anything you " +
        "rely on against the code itself."

/**
 * The brief of the DOWNSTREAM phase: the same task, the same tree, with or without a note.
 *
 * The baseline arm is this same string with [note] null, so the three arms differ by exactly one
 * inserted block and nothing else — not by a word of the task, not by an environment fact, not by the
 * success markers. That is what makes `P(success | note)` attributable to the note.
 */
fun buildUnderstandingDownstreamPrompt(
    case: UnderstandingCase,
    projectDir: String,
    note: String?,
): String = buildString {
    appendLine("You are working on a large multi-module Java project located at: `$projectDir`")
    appendLine()
    appendLine("**OUTPUT REQUIREMENT** (read now, apply at the end): when the task is complete and the")
    appendLine("project compiles, your LAST message MUST contain `$ARENA_FIX_APPLIED_MARKER` on its own line.")
    appendLine("The harness detects only that exact string, not a build tool's own success output.")
    appendLine()
    appendLine("## Task")
    appendLine()
    appendLine(case.problemStatement.trim())
    if (note != null) {
        appendLine()
        appendLine("## Notes from another developer")
        appendLine()
        appendLine(UNDERSTANDING_NOTE_INTRODUCTION)
        appendLine()
        appendLine(note.trim())
    }
    appendLine()
    appendLine("## Environment Facts")
    appendLine()
    understandingEnvironmentFacts(case).forEach { appendLine(it) }
    appendLine("- Verify your work by building and testing the module you changed, with")
    appendLine("  `./mvnw test -pl <module>`; do not launch a reactor-wide test run to check yourself.")
    appendLine("- Never end your turn while a command you started is still running. If you start a build")
    appendLine("  in the background, poll it until it has finished and read its outcome before you answer.")
    appendLine("  Ending a turn to wait for a completion notification ends the whole run instead, with")
    appendLine("  your work unverified.")
    appendLine()
    appendLine("## Success Markers")
    appendLine()
    appendLine("End your last message with exactly:")
    appendLine()
    appendLine("    $ARENA_FIX_APPLIED_MARKER")
    appendLine("    ARENA_SUMMARY: <one line on what you changed and how you verified it>")
    appendLine()
    appendLine("If you could not complete the task, end with `ARENA_FIX_APPLIED: no` and one line saying why.")
}
