/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies [escapeShellArgs] quotes every shell-meaningful character so
 * `docker exec … bash -c "<joined>"` doesn't silently rewrite tokens.
 *
 * The TC CI failure mode that motivated these tests: `git -c safe.directory=*`
 * was shell-expanded to `git -c safe.directory=<cwd-file-1>` before reaching
 * git, so the "dubious ownership" check kept firing despite the flag.
 *
 * Every test here runs the output through `bash -c "echo <joined>"` locally
 * to confirm bash actually sees the expected tokens back — catches any
 * future quoting regression end-to-end.
 */
class EscapeShellArgsTest {

    @Test
    fun `plain words pass through unquoted`() {
        assertEquals("git clone file://foo bar", escapeShellArgs(listOf("git", "clone", "file://foo", "bar")))
        roundTripBash(listOf("git", "clone", "file://foo", "bar"))
    }

    @Test
    fun `asterisk glob is quoted`() {
        val out = escapeShellArgs(listOf("git", "-c", "safe.directory=*"))
        assertEquals("git -c 'safe.directory=*'", out)
        roundTripBash(listOf("git", "-c", "safe.directory=*"))
    }

    @Test
    fun `question mark glob is quoted`() {
        roundTripBash(listOf("ls", "fil?.txt"))
    }

    @Test
    fun `square brackets are quoted`() {
        roundTripBash(listOf("ls", "fil[12].txt"))
    }

    @Test
    fun `dollar variable is quoted`() {
        roundTripBash(listOf("echo", "\$HOME"))
        roundTripBash(listOf("echo", "\${PATH}"))
    }

    @Test
    fun `whitespace forces quoting`() {
        roundTripBash(listOf("echo", "two words"))
        roundTripBash(listOf("echo", "tab\there"))
    }

    @Test
    fun `single quote is escaped`() {
        roundTripBash(listOf("echo", "it's"))
        roundTripBash(listOf("echo", "multiple'quotes'here"))
    }

    @Test
    fun `double quote and backtick are quoted`() {
        roundTripBash(listOf("echo", "say \"hi\""))
        roundTripBash(listOf("echo", "`date`"))
    }

    @Test
    fun `shell operators are quoted`() {
        roundTripBash(listOf("echo", "a;b"))
        roundTripBash(listOf("echo", "a&b"))
        roundTripBash(listOf("echo", "a|b"))
        roundTripBash(listOf("echo", "a<b"))
        roundTripBash(listOf("echo", "a>b"))
        roundTripBash(listOf("echo", "a(b)"))
    }

    @Test
    fun `history expansion bang is quoted`() {
        roundTripBash(listOf("echo", "hello!"))
    }

    @Test
    fun `empty arg is quoted`() {
        assertEquals("''", escapeShellArgs(listOf("")))
    }

    /**
     * Feed the escaped string through `bash -c "echo <joined>"` and assert the
     * echoed tokens round-trip back as the original args (verbatim, no glob
     * expansion, no variable substitution, no word splitting).
     */
    private fun roundTripBash(args: List<String>) {
        val escaped = escapeShellArgs(args)
        // Use printf with a separator that cannot appear inside the args
        // themselves to split output back into tokens safely.
        val sep = "\u0001"
        // Wrap into a script that prints each positional arg separated by $sep.
        // sh -c 'printf "%s$sep" "$@"' _ <escaped tokens>
        val scriptContent = "printf '%s$sep' \"\$@\""
        val cmd = listOf("bash", "-c", scriptContent, "_") + args  // pass ORIGINAL args as $1, $2, … — baseline
        val baseline = runBashPositional(scriptContent, args).split(sep).dropLast(1)

        // Now prove that `bash -c "<escaped>"` yields the same tokens when they're
        // passed through the shell interpreter rather than positional args.
        // The script is `printf '%s\x01' <escaped>` — if escaping is correct,
        // the shell sees each token intact.
        val roundTripped = runBashEval("printf '%s$sep' $escaped").split(sep).dropLast(1)

        assertEquals(baseline, roundTripped, "escapeShellArgs($args) = <$escaped> → bash interpretation mismatch")
    }

    private fun runBashPositional(script: String, args: List<String>): String {
        val p = ProcessBuilder(listOf("bash", "-c", script, "_") + args)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "bash failed: $out" }
        return out
    }

    private fun runBashEval(command: String): String {
        val p = ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "bash failed: $out" }
        return out
    }
}
