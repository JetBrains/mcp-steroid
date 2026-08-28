/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Runs the budget gate through a REAL `sh`, with the payloads the CLI actually sends.
 *
 * Every other test of this script asserts on its TEXT, and text assertions cannot catch the failure
 * that matters here: the script is written to exit 0 on any internal failure, deliberately, so that an
 * instrument which cannot count never turns into one that blocks everything. The cost of that choice
 * is that a mistyped `sed` expression or a `case` glob that matches nothing does not fail — it silently
 * lets every call through, and the run looks like an agent with an unlimited allowance while the table
 * says twenty.
 *
 * So the behaviour is exercised rather than described: exit codes, and what the counter file holds
 * afterwards.
 */
class UnderstandingBudgetGateShellTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a repository read is charged and a write is not`() {
        val gate = gate(budget = 3)
        assertEquals(0, gate.run(payload("Read", """"file_path":"/home/agent/project-home/pom.xml"""")))
        assertEquals(1, gate.used())
        assertEquals(0, gate.run(payload("Bash", """"command":"grep -r Foo ."""")))
        assertEquals(2, gate.used())
        // The answer is free: charging for it would price a note in keystrokes.
        assertEquals(0, gate.run(payload("Write", """"file_path":"/home/agent/project-home/A.java"""")))
        assertEquals(2, gate.used())
    }

    @Test
    fun `a subagent is refused outright and costs nothing`() {
        val gate = gate(budget = 5)
        val exit = gate.run(payload("Agent", """"description":"Find the token endpoint""""))
        assertEquals(2, exit, "a subagent must be blocked, not counted")
        assertEquals(0, gate.used(), "a refused call must not spend the allowance it was refused by")
        assertEquals(2, gate.run(payload("Task", """"description":"same tool, other name"""")))
        // And the reason has to reach the model, or it retries the same call until the run ends.
        assertTrue(gate.lastStderr.contains("DELEGATION IS NOT AVAILABLE"), gate.lastStderr)
        assertEquals(0, gate.used())
    }

    @Test
    fun `polling a background build is free, and the exemption does not reach the repository`() {
        val gate = gate(budget = 5)
        val taskOutput = "/tmp/claude-1000/-home-agent-project-home/992965e6/tasks/bu01xz9if.output"
        assertEquals(0, gate.run(payload("Read", """"file_path":"$taskOutput"""")))
        assertEquals(0, gate.used(), "reading the CLI's own task output is not a repository interaction")

        // The narrowness is the point: a blanket "outside the project is free" would make one
        // `cp -r project /tmp` buy unlimited reads.
        assertEquals(0, gate.run(payload("Read", """"file_path":"/tmp/copy-of-project/pom.xml"""")))
        assertEquals(1, gate.used(), "an ordinary /tmp path must still be charged")
        assertEquals(0, gate.run(payload("Read", """"file_path":"/tmp/claude-1000/x/tasks/a.output.java"""")))
        assertEquals(2, gate.used(), "a path that merely starts like the task output is charged")
    }

    @Test
    fun `scheduling a wakeup reads nothing and is free`() {
        val gate = gate(budget = 5)
        assertEquals(0, gate.run(payload("ScheduleWakeup", """"delaySeconds":60""")))
        assertEquals(0, gate.used())
    }

    @Test
    fun `the wall arrives after exactly the allowance, and denials are counted`() {
        val gate = gate(budget = 2)
        assertEquals(0, gate.run(payload("Bash", """"command":"ls"""")))
        assertEquals(0, gate.run(payload("Bash", """"command":"ls"""")))
        assertEquals(2, gate.used())
        assertEquals(2, gate.run(payload("Bash", """"command":"ls"""")), "the third call is past the wall")
        assertEquals(2, gate.used(), "a denied call must not advance the counter")
        assertEquals(1, gate.denied())
        assertTrue(gate.lastStderr.contains("BUDGET EXHAUSTED"), gate.lastStderr)
        // Editing still works after the wall: an agent that believes it cannot act stops acting, and
        // the cell would measure the wall instead of the note.
        assertEquals(0, gate.run(payload("Edit", """"file_path":"/home/agent/project-home/A.java"""")))
    }

    @Test
    fun `a repair turn reads the files it was handed for free, and nothing else`() {
        val gate = gate(budget = 1)
        val broken = "/home/agent/project-home/services/src/main/java/org/keycloak/A.java"
        assertEquals(0, gate.run(payload("Bash", """"command":"ls"""")))
        assertEquals(1, gate.used(), "the allowance is spent, so every read is past the wall now")

        // Before the repair turn there is no exemption: the wall applies to the same file.
        assertEquals(2, gate.run(payload("Read", """"file_path":"$broken"""")))

        gate.allowFreeReads(listOf(broken))
        assertEquals(0, gate.run(payload("Read", """"file_path":"$broken"""")), "the named file is free")
        assertEquals(1, gate.used(), "a free read must not advance the counter")
        // The exemption is a whole-line match on the paths javac named — not a prefix, not the folder.
        assertEquals(2, gate.run(payload("Read", """"file_path":"/home/agent/project-home/pom.xml"""")))
        assertEquals(2, gate.run(payload("Read", """"file_path":"$broken.orig"""")))

        // And it lasts exactly one turn.
        gate.clearFreeReads()
        assertEquals(2, gate.run(payload("Read", """"file_path":"$broken"""")))
    }

    @Test
    fun `an unused repair list leaves an ordinary read charged`() {
        val gate = gate(budget = 3)
        gate.allowFreeReads(emptyList())
        assertEquals(0, gate.run(payload("Read", """"file_path":"/home/agent/project-home/pom.xml"""")))
        assertEquals(1, gate.used(), "an empty allowlist exempts nothing")
    }

    @Test
    fun `nothing the gate does reaches stdout`() {
        val gate = gate(budget = 1)
        gate.run(payload("Bash", """"command":"ls""""))
        assertEquals("", gate.lastStdout, "stdout is the CLI's JSON-RPC channel")
        gate.run(payload("Agent", """"description":"x""""))
        assertEquals("", gate.lastStdout)
    }

    private fun payload(tool: String, input: String) =
        """{"tool_name":"$tool","tool_input":{$input}}"""

    private fun gate(budget: Int): Gate {
        val dir = File(tempDir, "r").also { it.mkdirs() }
        val script = File(dir, "gate.sh")
        script.writeText(
            understandingBudgetHookScript(
                budget = budget,
                counterFile = File(dir, "used").absolutePath,
                deniedFile = File(dir, "denied").absolutePath,
                recordDir = dir.absolutePath,
                exemptTools = UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS,
                exhaustedMessage = UNDERSTANDING_DOWNSTREAM_BUDGET_EXHAUSTED_MESSAGE,
            )
        )
        script.setExecutable(true)
        return Gate(dir, script)
    }

    class Gate(private val dir: File, private val script: File) {
        var lastStderr: String = ""
        var lastStdout: String = ""

        fun run(payload: String): Int {
            val process = ProcessBuilder("sh", script.absolutePath).directory(dir).start()
            process.outputStream.bufferedWriter().use { it.write(payload) }
            lastStdout = process.inputStream.bufferedReader().readText()
            lastStderr = process.errorStream.bufferedReader().readText()
            return process.waitFor()
        }

        /** What [UnderstandingResearchGate.allowFreeReads] writes, without a container to write it in. */
        fun allowFreeReads(paths: List<String>) {
            File(dir, UNDERSTANDING_REPAIR_READABLE_FILE)
                .writeText(if (paths.isEmpty()) "" else paths.joinToString("\n") + "\n")
        }

        fun clearFreeReads() {
            File(dir, UNDERSTANDING_REPAIR_READABLE_FILE).writeText("")
        }

        fun used(): Int = File(dir, "used").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
        fun denied(): Int = File(dir, "denied").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
    }
}
