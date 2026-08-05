/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * End-to-end coverage of the **non-MCP** CLI surface — `--help`, `--version`, no-args, unknown-arg paths,
 * and the generated `devrig <tool>` subcommands issue #284 derives from tool metadata — driven through the
 * real `installDist` launcher (`bin/devrig`).
 *
 * The launcher runs INSIDE the shared `mcp-cli` container ([DevrigCliContainer]), never on the host — a
 * host run would create the developer's real `~/.mcp-steroid` even for `--help`/`--version` (devrig
 * resolves its hardcoded home at startup). The container is built once for the class.
 *
 * The unit tests in `DevrigCommandTest` / `DevrigCommandOutputTest` already pin command selection inside
 * the JVM; this class extends that to the shell launcher script. The same launcher is exercised in MCP
 * mode by `CliMcpStdioIntegrationTest` and for stdout-cleanliness by `CliMcpStdioStdoutCleanlinessTest`.
 */
@Suppress("FunctionName")
class CliOptionsIntegrationTest {

    private fun runLauncher(vararg args: String): ProcessResult =
        // The bin-launcher self-heal defaults ON for non-SNAPSHOT lanes (-jb-/-gh-/-r-) and narrates
        // its (re)write + PATH hint to stderr by design, so the stream-cleanliness assertions here
        // only hold with registration pinned off (the opt-out itself is contract-tested in
        // CliBinLauncherIntegrationTest).
        cli.runDevrig(*args, timeoutSeconds = 30, env = mapOf("DEVRIG_BIN_NO_AUTO_REGISTER" to "yes"))

    // -------------------------------- --help --------------------------------

    @Test
    fun `--help exits 0 with usage on stdout and clean stderr`() {
        val r = runLauncher("--help")
        assertEquals(0, r.exitCode, "--help must exit 0; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("Usage:"), "expected 'Usage:' in stdout; got:\n${r.stdout}")
        assertTrue(r.stdout.contains("devrig mcp"), "help banner must advertise the canonical mcp subcommand; got:\n${r.stdout}")
        assertTrue(r.stdout.contains("--version"), "help banner must advertise --version; got:\n${r.stdout}")
        assertTrue(r.stderr.isBlank(),
            "--help must keep stderr clean; got:\n${r.stderr}")
    }

    @Test
    fun `-h short form exits 0 with the same usage banner on stdout`() {
        val r = runLauncher("-h")
        assertEquals(0, r.exitCode, "-h must exit 0; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("Usage:"), "got:\n${r.stdout}")
    }

    @Test
    fun `no args at all also prints help and exits 0`() {
        // The launcher should be inspectable with bare `devrig` —
        // no stdin consumed, no NDJSON written, no client confusion.
        val r = runLauncher()
        assertEquals(0, r.exitCode, "bare invocation must exit 0; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("Usage:"), "got:\n${r.stdout}")
        assertTrue(r.stderr.isBlank(), "no-arg invocation must keep stderr clean; got:\n${r.stderr}")
    }

    // ------------------------------ --version -------------------------------

    @Test
    fun `--version prints a single non-empty line on stdout`() {
        val r = runLauncher("--version")
        assertEquals(0, r.exitCode, "--version must exit 0; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stderr.isBlank(), "--version must keep stderr clean; got:\n${r.stderr}")
        val lines = r.stdout.trimEnd().lines()
        assertEquals(1, lines.size,
            "--version must be a single line; got ${lines.size} lines:\n${r.stdout}")
        assertTrue(lines.single().isNotBlank(), "--version line must be non-empty")
    }

    @Test
    fun `-v short form behaves identically to --version`() {
        val long = runLauncher("--version")
        val short = runLauncher("-v")
        assertEquals(long.exitCode, short.exitCode, "long vs short version exit codes")
        assertEquals(long.stdout.trim(), short.stdout.trim(),
            "long vs short version output must match")
    }

    // --------------------------- unknown / error path -----------------------

    @Test
    fun `unknown arg exits 64 with error on stderr and clean stdout`() {
        val r = runLauncher("--no-such-flag")
        assertEquals(64, r.exitCode,
            "unknown flag must exit 64 (sysexits EX_USAGE); stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.isBlank(),
            "unknown-arg path must keep stdout clean so machine consumers aren't confused; got:\n${r.stdout}")
        assertTrue(r.stderr.contains("Error:"),
            "stderr should announce the parser error; got:\n${r.stderr}")
        assertTrue(r.stderr.contains("--no-such-flag"),
            "stderr should echo the offending token; got:\n${r.stderr}")
        assertTrue(r.stderr.contains("Usage:"),
            "stderr should include the usage banner for orientation; got:\n${r.stderr}")
    }

    @Test
    fun `multiple unknown args fail on the first parser error`() {
        val r = runLauncher("--alpha", "--beta")
        assertEquals(64, r.exitCode)
        assertTrue(r.stderr.contains("--alpha"), "got:\n${r.stderr}")
    }

    // --------------------- mixed-flag precedence (real binary) --------------

    @Test
    fun `unknown arg before --help remains a parser error`() {
        val r = runLauncher("--bogus", "--help")
        assertEquals(64, r.exitCode, "unknown arg should fail before help; stderr=\n${r.stderr}")
        assertTrue(r.stdout.isBlank(), "parse errors must keep stdout clean; got:\n${r.stdout}")
        assertTrue(r.stderr.contains("--bogus"), "got:\n${r.stderr}")
        assertTrue(r.stderr.contains("Usage:"), "got:\n${r.stderr}")
    }

    @Test
    fun `case-mismatch on --MCP is treated as Unknown, not as MCP`() {
        // Critical safety net: an accidental upper-case flag MUST NOT silently
        // commit stdout to NDJSON framing. The launcher should error out
        // visibly so the user fixes their wrapper script.
        val r = runLauncher("--MCP")
        assertEquals(64, r.exitCode, "--MCP (wrong case) must NOT trigger MCP mode")
        assertTrue(r.stderr.contains("Error:"), "got:\n${r.stderr}")
        assertTrue(r.stderr.contains("--MCP"), "got:\n${r.stderr}")
    }

    // --------------------------- backend subcommand ------------------------

    @Test
    fun `backend exits 0 and prints backend status`() {
        // The CI runner has no IDE markers in $HOME, so the no-backends branch is
        // the deterministic outcome here. The wire-level happy path
        // (IDE present + projects open) is covered by `BackendCommandFetchTest`
        // against an in-process Ktor mock.
        //
        // We can't isolate $HOME via env vars (the launcher reads `user.home`
        // directly), so this test asserts a forgiving condition: if there
        // happens to be a real IDE running on the dev workstation, the
        // launcher still exits 0 and produces non-empty stdout. Either way
        // stderr stays empty of catastrophic failure.
        val r = runLauncher("backend")
        assertEquals(0, r.exitCode,
            "backend must exit 0 even when no IDEs are running; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.isNotBlank(),
            "backend must produce at least one line of output; got:\n${r.stdout}")
        // One of the two expected shapes:
        val output = r.stdout.trimEnd()
        val backendStatus = output.removeOptionalHeadliner()
        // The no-backends shape is the message PLUS its promoted next step (BackendCommand prints
        // both; the headless-guidance work added the hint). Pinned whole, like the message itself.
        val isNoBackends = backendStatus ==
            "No backends detected.\nTo discover and install an IDE: devrig backend download --json"
        val looksLikeIdeListing = backendStatus.lines().any { line ->
            line.contains("Discovered ") && line.contains("backend")
        }
        assertTrue(
            isNoBackends || looksLikeIdeListing,
            "backend output must be either the no-backends message or a backend listing; got:\n$output",
        )
    }

    @Test
    fun `json commands do not print the headliner before JSON`() {
        val backend = runLauncher("backend", "--json")
        assertEquals(0, backend.exitCode, "backend --json failed; stdout=\n${backend.stdout}\nstderr=\n${backend.stderr}")
        assertTrue(backend.stdout.trimStart().startsWith("{"),
            "backend --json stdout must start with JSON object; got:\n${backend.stdout}")

        val project = runLauncher("project", "--json")
        assertEquals(0, project.exitCode, "project --json failed; stdout=\n${project.stdout}\nstderr=\n${project.stderr}")
        assertTrue(project.stdout.trimStart().startsWith("{"),
            "project --json stdout must start with JSON object; got:\n${project.stdout}")
    }

    @Test
    fun `backend --help prints help, NOT IDE listing`() {
        // Help wins over backend by parser precedence — confirm the real
        // launcher honors that so a future shell-launcher tweak can't
        // accidentally open connections in response to a help request.
        val r = runLauncher("backend", "--help")
        assertEquals(0, r.exitCode, "--help should win over backend; stderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("Usage:"), "got:\n${r.stdout}")
        assertTrue(!r.stdout.contains("No backends detected") && !r.stdout.contains("Discovered "),
            "help output must not include backend listing artifacts; got:\n${r.stdout}")
    }

    // ------------------------- generated MCP-tool subcommands (#284) -------------------------
    //
    // The schema-driven CLI turns every `steroid_*` tool into a `devrig <tool>` subcommand. Every defect
    // found in it so far was found by hand-running the built binary — the generated help, the `--json`
    // envelope and the parse-error path are all assembled at startup from tool metadata, so a unit test
    // that calls the renderer proves the renderer and not the command a user types. These cases close that
    // gap in the lane that already spawns the launcher.

    @Test
    fun `list_projects --json emits exactly one JSON document on stdout`() {
        // The frozen `--json` contract. Parsing the WHOLE of stdout is the assertion: a second document, a
        // banner line, or a progress line leaking off stderr would break every `devrig ... --json | jq`
        // caller, and JSON parsing rejects trailing content after the first document. The container has no
        // IDE, so the lister answers from an empty routing table and still exits 0.
        val r = runLauncher("list_projects", "--json")

        assertEquals(0, r.exitCode, "list_projects must exit 0 with no IDE; stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        val envelope = Json.parseToJsonElement(r.stdout).jsonObject
        assertEquals("list_projects", envelope.getValue("command").jsonPrimitive.content)
        assertEquals("false", envelope.getValue("isError").jsonPrimitive.content)
    }

    @Test
    fun `a generated subcommand's --help names the tool and its own declared flags`() {
        val r = runLauncher("execute_code", "--help")

        assertEquals(0, r.exitCode, "--help must win over the required parameters; stderr=\n${r.stderr}")
        assertTrue(r.stdout.contains("execute_code"), "help must name the command; got:\n${r.stdout}")
        for (flag in listOf("--project_name", "--code", "--code-file", "--task_id", "--reason", "--out")) {
            assertTrue(flag in r.stdout, "execute_code --help must document $flag; got:\n${r.stdout}")
        }
        assertTrue(r.stderr.isBlank(), "--help must keep stderr clean; got:\n${r.stderr}")
    }

    @Test
    fun `an unknown flag on a generated subcommand exits 64 with clean stdout`() {
        val r = runLauncher("list_windows", "--bogus")

        assertEquals(64, r.exitCode, "stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.isBlank(), "a parse error must keep stdout clean; got:\n${r.stdout}")
        assertTrue(r.stderr.contains("--bogus"), "stderr must echo the offending token; got:\n${r.stderr}")
    }

    @Test
    fun `the global banner carries the generated MCP-tools section`() {
        val r = runLauncher("--help")

        assertTrue(
            r.stdout.contains("MCP tools as CLI"),
            "the banner must embed the generated section; got:\n${r.stdout}",
        )
        for (tool in listOf("devrig list_projects", "devrig execute_code", "devrig take_screenshot")) {
            assertTrue(r.stdout.contains(tool), "the generated section must advertise `$tool`; got:\n${r.stdout}")
        }
    }

    @Test
    fun `a missing project_name exits 64 at parse time and does not blame the backend`() {
        // project_name is required by the generated command itself until cwd inference exists. This must
        // stop before dispatch, with the curated CLI hint rather than a tool/backend diagnosis.
        val r = runLauncher("execute_code", "--code=println(1)", "--task_id=t", "--reason=r")

        assertEquals(64, r.exitCode, "stdout=\n${r.stdout}\nstderr=\n${r.stderr}")
        assertTrue(r.stdout.isBlank(), "a CLI-level failure must keep stdout clean; got:\n${r.stdout}")
        assertTrue(
            r.stderr.contains("missing --project_name") && r.stderr.contains("devrig list_projects"),
            "the parse-time project hint must reach the user; got:\n${r.stderr}",
        )
        assertTrue(
            !r.stderr.contains("no IDE backend is reachable"),
            "a refused argument says nothing about the backend; got:\n${r.stderr}",
        )
    }

    private fun String.removeOptionalHeadliner(): String =
        if (startsWith("devrig v")) substringAfter("\n\n", this).trimStart('\n') else this

    companion object {
        private val lifetime by lazy { CloseableStackHost(CliOptionsIntegrationTest::class.java.simpleName) }
        private val cli by lazy { lifetime.startDevrigCliContainer() }

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            cli.toString()
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }
}
