/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * End-to-end Docker coverage for the devrig binary OWNING `~/.mcp-steroid/bin/devrig` — the launcher is
 * (re)written on EVERY start, symlinked onto PATH, and the whole chain (wrapper → DEVRIG_JAVA_HOME →
 * install-tree launcher) actually runs. Also pins the undocumented `DEVRIG_BIN_NO_AUTO_REGISTER` gate:
 * an explicit opt-out never writes on any lane; a passive start (env unset) follows the dist's build
 * lane — OFF on a SNAPSHOT dist, ON on a CI/release dist. The dist here is built by `installDist` in the
 * SAME Gradle invocation, so its baked version is a SNAPSHOT locally but a `-jb-`/`-gh-` CI version on
 * TeamCity (issue #410) — the passive-start test derives its expectation from the dist's own reported
 * version instead of assuming a lane.
 *
 * Runs the REAL devrig dist inside a throwaway Linux container (see [startDevrigCliContainer]); never on
 * the host, which would create the developer's real `~/.mcp-steroid`.
 */
class CliBinLauncherIntegrationTest {
    private val lifetime = CloseableStackHost()

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    fun `with the opt-in, every start writes bin devrig, symlinks it onto PATH, and the wrapper runs`() {
        val devrig = lifetime.startDevrigCliContainer()
        // One shell: make a PATH dir under HOME, opt in, start devrig once (self-heal fires), then prove
        // the launcher was written, symlinked, and the symlinked wrapper actually launches devrig — and
        // that the wrapper's DEVRIG_JAVA_HOME is the SOLE JDK source (no ambient java masking it).
        //
        // The proof for the JDK pin (PR #117 review): the container has a system `java` (the image's
        // temurin), which the FIRST run needs (the dist bundles no JDK — chicken-and-egg). So we seed the
        // wrapper with that system java, then for the proof run we POISON `java` on PATH with a stub that
        // exits 97 and UNSET JAVA_HOME. The wrapper exports DEVRIG_JAVA_HOME and the install-tree start
        // script resolves `$JAVA_HOME/bin/java` by absolute path, so it must NOT touch the poisoned PATH
        // java. If the wrapper ever stopped pinning DEVRIG_JAVA_HOME, JAVA_HOME would be empty → the start
        // script falls back to PATH `java` → the stub fires (exit 97, no version) → this test fails.
        // /usr/bin:/bin stay on PATH because the start script needs coreutils (dirname/uname/basename).
        val script = """
            set -eu
            mkdir -p "${'$'}HOME/.local/bin" "${'$'}HOME/poison"
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            export DEVRIG_BIN_NO_AUTO_REGISTER=false
            "${devrig.launcher}" version >/dev/null 2>&1 || true
            echo "WRAPPER_EXISTS=${'$'}([ -x "${'$'}HOME/.mcp-steroid/bin/devrig" ] && echo yes || echo no)"
            echo "SYMLINK_TARGET=${'$'}(readlink "${'$'}HOME/.local/bin/devrig" 2>/dev/null || echo none)"
            echo "===WRAPPER==="
            cat "${'$'}HOME/.mcp-steroid/bin/devrig"
            printf '#!/bin/sh\necho AMBIENT_JAVA_USED >&2\nexit 97\n' > "${'$'}HOME/poison/java"
            chmod +x "${'$'}HOME/poison/java"
            echo "===RUN_VIA_SYMLINK_NO_AMBIENT_JAVA==="
            env -u JAVA_HOME PATH="${'$'}HOME/poison:${'$'}HOME/.local/bin:/usr/bin:/bin" devrig version 2>/dev/null | head -1
        """.trimIndent()

        val out = devrig.runShell(script).assertExitCode(0, "bin-launcher self-heal").stdout

        assertTrue(out.contains("WRAPPER_EXISTS=yes"), out)
        assertTrue(Regex("SYMLINK_TARGET=.*/\\.mcp-steroid/bin/devrig").containsMatchIn(out), out)
        // The wrapper pins the JDK devrig runs under (via DEVRIG_JAVA_HOME) and execs the install-tree
        // launcher by absolute path.
        assertTrue(out.contains("DEVRIG_JAVA_HOME="), out)
        assertTrue(out.contains("exec \"${devrig.launcher}\""), out)
        // The whole chain works AND DEVRIG_JAVA_HOME is the sole JDK source: invoking the PATH symlink
        // (→ wrapper → DEVRIG_JAVA_HOME → devrig) prints a real version even with PATH `java` poisoned and
        // JAVA_HOME unset. A version means the poisoned ambient java was never used.
        val ranVersion = out.substringAfter("===RUN_VIA_SYMLINK_NO_AMBIENT_JAVA===")
        assertTrue(Regex("\\d+\\.\\d+").containsMatchIn(ranVersion), "the symlinked wrapper should launch devrig:\n$out")
    }

    @Test
    fun `without the opt-in, a passive start follows the dist's lane - no write on SNAPSHOT, self-heal on CI and release`() {
        val devrig = lifetime.startDevrigCliContainer()
        // The `devrig version` run IS the passive start under test (every start runs the launcher
        // self-heal), and its stdout is the dist's baked version — the lane oracle. Deriving the
        // expectation from it keeps this test meaningful on EVERY lane: a local SNAPSHOT dist must not
        // touch bin/devrig, while the TC/GH dist (non-SNAPSHOT -jb-/-gh- version) must write it.
        val script = """
            echo "VERSION=${'$'}("${devrig.launcher}" version 2>/dev/null || echo LAUNCH-FAILED)"
            echo "WRAPPER_EXISTS=${'$'}([ -e "${'$'}HOME/.mcp-steroid/bin/devrig" ] && echo yes || echo no)"
        """.trimIndent()

        val out = devrig.runShell(script).assertExitCode(0, "passive-start lane gate").stdout
        val version = out.lineSequence().first { it.startsWith("VERSION=") }.removePrefix("VERSION=").trim()
        assertTrue(
            Regex("\\d+\\.\\d+").containsMatchIn(version) && !version.contains("LAUNCH-FAILED"),
            "the passive `devrig version` start must print the dist's baked version:\n$out",
        )
        val expected = if (version.contains("SNAPSHOT", ignoreCase = true)) "no" else "yes"
        assertTrue(
            out.contains("WRAPPER_EXISTS=$expected"),
            "a passive start on the '$version' dist must ${if (expected == "yes") "self-heal" else "NOT write"} the launcher:\n$out",
        )
    }

    @Test
    fun `the explicit opt-out never writes bin devrig, regardless of the dist's lane`() {
        val devrig = lifetime.startDevrigCliContainer()
        // DEVRIG_BIN_NO_AUTO_REGISTER=yes wins on every lane (even over `devrig install`'s force) — so
        // this asserts the never-writes side deterministically on SNAPSHOT and CI/release dists alike.
        val script = """
            export DEVRIG_BIN_NO_AUTO_REGISTER=yes
            "${devrig.launcher}" version >/dev/null 2>&1 || true
            echo "WRAPPER_EXISTS=${'$'}([ -e "${'$'}HOME/.mcp-steroid/bin/devrig" ] && echo yes || echo no)"
        """.trimIndent()

        val out = devrig.runShell(script).assertExitCode(0, "opt-out gate").stdout
        assertTrue(out.contains("WRAPPER_EXISTS=no"), "the opt-out must never write the launcher:\n$out")
    }

    /** Run a `/bin/sh -c <script>` in the devrig container, returning the finished process result. */
    private fun DevrigCliContainer.runShell(script: String, timeoutSeconds: Long = 120): ProcessResult =
        container.startProcessInContainer {
            args("sh", "-c", script)
                .timeoutSeconds(timeoutSeconds)
                .description("bin-launcher shell")
                .quietly()
        }.awaitForProcessFinish()
}
