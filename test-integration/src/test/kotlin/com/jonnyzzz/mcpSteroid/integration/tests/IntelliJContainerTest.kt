/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.buildDevrigImage
import com.jonnyzzz.mcpSteroid.integration.infra.buildIdeImage
import com.jonnyzzz.mcpSteroid.integration.infra.buildSharedBaseImage
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.resolveAndDownload
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Assertions

/**
 * Integration test for IdeContainerSession infrastructure.
 *
 * Verifies that the Docker container can be built and started,
 * all directories are properly mounted, and the IDE starts successfully.
 */
class IntelliJContainerTest {
    @Test
    fun `base container build is incremental`() {
        val rebuilds = List(6) {
            measureTimedValue { buildSharedBaseImage() }
        }.drop(1)

        println(rebuilds.map { it.duration })
        rebuilds.forEachIndexed { idx, (image, _) ->
            assertEveryLayerServedFromCache(image, "base image rebuild #${idx + 2}")
        }
    }

    @Test
    fun `container images are incremental`() {
        val rebuilds = List(7) {
            measureTimedValue {
                val distribution = IdeDistribution.fromSystemProperties()
                val ideArchive = distribution.resolveAndDownload()
                buildIdeImage("ide-agent", ideArchive)
            }
        }.drop(1)

        println(rebuilds.map { it.duration })
        rebuilds.forEachIndexed { idx, (image, _) ->
            assertEveryLayerServedFromCache(image, "ide image rebuild #${idx + 2}")
        }
    }

    @Test
    fun `container devrig images are incremental`() {
        // Unique suffix ensures parallel test runs each builds their own image and context dir,
        // preventing races in buildIdeImage when multiple tests start concurrently.
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val imageName = "ide-agent-test-$uniqueSuffix"

        val rebuilds = List(7) {
            measureTimedValue { buildDevrigImage("managed-backend-host", imageName) }
        }.drop(1)

        println(rebuilds.map { it.duration })
        rebuilds.forEachIndexed { idx, (image, _) ->
            assertEveryLayerServedFromCache(image, "devrig image rebuild #${idx + 2}")
        }
    }

    /**
     * Asserts a rebuild was served entirely from the BuildKit layer cache: every layer-creating
     * Dockerfile step (`RUN`/`COPY`/`ADD`) in the `--progress=plain` output must carry the
     * `#<id> CACHED` marker. Content-based replacement for the old "under 2 seconds" wall-clock
     * assertion, which was deterministically flaky on a loaded shared Docker daemon (a fully
     * cached build can take >2s when the daemon is busy).
     *
     * `FROM` steps are excluded on purpose: BuildKit may report them as `DONE` (base-image
     * resolve) even when nothing rebuilds. Metadata-only instructions (ENV/ARG/WORKDIR/...) emit
     * no layer step and need no marker.
     */
    private fun assertEveryLayerServedFromCache(image: ImageDriver, what: String) {
        val output = image.buildOutput
        // Plain-progress step header: `#<id> [<stage?> <k>/<n>] <INSTRUCTION> ...`
        // (k may be space-padded when n >= 10, e.g. `[ 2/12]`).
        val layerSteps = Regex("""^#(\d+) \[[^\]]*\d+/\d+]\s+(?:RUN|COPY|ADD)\b.*""", RegexOption.MULTILINE)
            .findAll(output)
            .map { it.groupValues[1] to it.value }
            .distinctBy { it.first }
            .toList()

        Assertions.assertTrue(layerSteps.isNotEmpty()) {
            "$what: no BuildKit RUN/COPY/ADD layer steps found in the plain-progress build output " +
                    "(is BuildKit enabled and --progress=plain passed?). Build output:\n$output"
        }

        val rebuilt = layerSteps.filter { (id, _) ->
            !Regex("""^#$id CACHED\s*$""", RegexOption.MULTILINE).containsMatchIn(output)
        }
        Assertions.assertTrue(rebuilt.isEmpty()) {
            "$what: these layers were rebuilt instead of being served from the BuildKit cache:\n" +
                    rebuilt.joinToString("\n") { it.second } +
                    "\n\nFull build output:\n$output"
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `container starts and IDE becomes ready`() = runWithCloseableStack { lifetime ->
        IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "ide-container",
        ))
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `xdotool input control works`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "ide-container-input"
        ))

        // Move the mouse to the center of the screen
        session.input.mouseMove(1920, 1080)

        // Click at the center
        session.input.mouseClick(1920, 1080)

        // Type some text (will go to whatever is focused)
        session.input.typeText("hello from xdotool")

        // Press Escape to dismiss any popup
        session.input.keyPress("Escape")

        // Verify we can query window info without crashing
        val activeWindow = session.input.getActiveWindowId()
        println("[test] Active window ID: $activeWindow")

        // Verify clipboard round-trip
        session.input.clipboardCopy("mcp-steroid-test")
        val pasted = session.input.clipboardPaste()
        check(pasted.contains("mcp-steroid-test")) {
            "Clipboard round-trip failed: expected 'mcp-steroid-test', got '$pasted'"
        }
    }
}
