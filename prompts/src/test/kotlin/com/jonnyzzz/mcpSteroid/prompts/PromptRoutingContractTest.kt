/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.McpSteroidInfoPrompt
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJContextApiPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJThreadingPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJVfsPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeOverviewPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionSlimPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two `steroid_execute_code` tool descriptions ship side by side: the full one, which spells every
 * recipe out inline, and the slim router, which names the gotcha that costs an agent a turn and links
 * the article carrying the full recipe. The server picks one per process (see
 * `ExecCodeDescriptionVariant`), so both need contract cover: shared edit-recipe invariants hold in
 * either, and every fact the router delegates has a reachable home.
 */
class PromptRoutingContractTest {

    private val contexts = listOf(PromptsContext.Generic, PromptsContext("IU", 253))

    private fun fullDescription(context: PromptsContext) =
        ExecuteCodeToolDescriptionPromptArticle().readPayload(context)

    private fun slimDescription(context: PromptsContext) =
        ExecuteCodeToolDescriptionSlimPromptArticle().readPayload(context)

    private fun bothVariants(context: PromptsContext) =
        mapOf("full" to fullDescription(context), "slim" to slimDescription(context))

    @Test
    fun `both tool description variants teach the write-action edit recipe and no removed API`() {
        for (context in contexts) {
            for ((variant, prompt) in bothVariants(context)) {
                assertFalse(
                    prompt.contains("steroid_apply_patch"),
                    "steroid_apply_patch was removed — the prompt must not name it ($variant, $context)",
                )
                assertFalse(
                    prompt.contains("applyPatch"),
                    "the applyPatch { } DSL was removed (#206) — the prompt must not route agents to it " +
                        "($variant, $context)",
                )
                assertFalse(
                    prompt.contains("mcp-steroid://ide/apply-patch"),
                    "the apply-patch recipe resource was removed (#206) — the prompt must not link it " +
                        "($variant, $context)",
                )
                assertTrue(
                    prompt.contains("writeAction { }") && prompt.contains("VfsUtil.saveText"),
                    "the in-place edit recipe must teach the writeAction { } + VfsUtil.saveText shape " +
                        "(pinned by MultiSiteEditRecipeTest, which executes the same shape) ($variant, $context)",
                )
                assertTrue(
                    prompt.contains("error(\"not found:"),
                    "lookup examples must teach `?: error(\"not found: ...\")` over `!!` (#156) " +
                        "($variant, $context)",
                )
            }
        }
    }

    /**
     * The read fork belongs to the **slim** variant only; the full one stays the measured reference text.
     *
     * The fork is what the slim router uses to keep the agent on the cheap read path: a one-shot look at a
     * file goes to native `Read`, and only work that touches the VFS, PSI, indexes or a build earns a
     * hand-written script. It demonstrably moves behaviour — with the fork the slim arm went from 0 to 7
     * native reads on `service-125x`. The full variant deliberately does NOT carry it: it is the arm every
     * earlier measurement was taken against, so changing its text destroys the baseline the slim arm is
     * compared to. Both halves of that split are asserted here.
     */
    @Test
    fun `the read fork lives in the slim variant and stays out of the full one`() {
        val forkMarkers = listOf(
            "Take the fork first" to
                "the routing guidance must open with the fork, so the IDE path reads as conditional " +
                "rather than as an unconditional order",
            "Read a file only to look at it" to
                "a one-shot read with no follow-up IDE operation must route to the native Read tool — " +
                "repo policy in prompts/CLAUDE.md, and the cheaper path",
            "reading does not write" to
                "the VFS/PSI staleness rule must stay scoped to Edit/Write; without this the agent " +
                "generalises it to reads and scripts every read",
            "in the SAME script" to
                "the edit/inspect/walk side of the fork must keep the read inside the one script that " +
                "acts on the file",
        )

        for (context in contexts) {
            val slim = slimDescription(context)
            val full = fullDescription(context)
            for ((marker, why) in forkMarkers) {
                assertTrue(slim.contains(marker), "$why (slim, $context)")
            }
            assertFalse(
                full.contains("Take the fork first"),
                "the full variant is the reference text every earlier arena run was measured against — " +
                    "editing it moves the baseline instead of the variable under test ($context)",
            )
        }
    }

    @Test
    fun `the full variant carries the multi-site recipe inline while the slim one links it`() {
        for (context in contexts) {
            val full = fullDescription(context)
            val slim = slimDescription(context)

            assertTrue(
                full.contains("Multi-site edits") && full.contains("groupBy { it.first }"),
                "the full variant spells the grouped multi-site recipe out inline — that is what makes it " +
                    "the full variant ($context)",
            )
            assertTrue(
                slim.contains("mcp-steroid://skill/execute-code-overview"),
                "the slim router delegates multi-file edits to the overview article — the link must be " +
                    "present ($context)",
            )
            assertTrue(
                slim.length * 5 < full.length * 3,
                "the slim router must render below 60% of the full description, otherwise it is not " +
                    "buying back enough tool-definition context to be worth a second variant " +
                    "(slim=${slim.length}, full=${full.length}, $context)",
            )
        }
    }

    @Test
    fun `the grouped multi-site recipe lives in the overview article`() {
        for (context in contexts) {
            val overview = ExecuteCodeOverviewPromptArticle().readPayload(context)

            assertTrue(
                overview.contains("groupBy { it.first }"),
                "the overview article must carry the grouped multi-site recipe the tool description " +
                    "links to — same-file edits are folded and applied in order ($context)",
            )
            assertTrue(
                overview.contains("writeAction") && overview.contains("VfsUtil.saveText"),
                "the grouped recipe must save every file inside one writeAction ($context)",
            )
        }
    }

    /**
     * A fact the slim router delegates instead of spelling out, plus the article that now owns it.
     *
     * [readIn] is rendered per context because a fact can live behind an IDE-gated fence: the Gradle and
     * Maven articles are `[IU]`-only by design (their plugins ship only in IDEA — see
     * `PerIdeAvailabilityContractTest.EXPECTED_UNAVAILABLE`), so their facts are asserted only in the
     * contexts where `steroid_fetch_resource` would serve the article at all.
     */
    private class SecondHome(
        val fact: String,
        val marker: String,
        val contexts: List<PromptsContext>,
        val readIn: (PromptsContext) -> String,
    )

    private val ideaOnly = listOf(PromptsContext("IU", 253))

    private val secondHomes: List<SecondHome> = listOf(
        SecondHome(
            "the errors=false, aborted=true compile trap (Gradle)",
            "COMPILE_ABORTED",
            ideaOnly,
        ) { ExecuteCodeGradlePromptArticle().readPayload(it) },
        SecondHome(
            "the errors=false, aborted=true compile trap (Maven)",
            "COMPILE_ABORTED",
            ideaOnly,
        ) { ExecuteCodeMavenPromptArticle().readPayload(it) },
        SecondHome(
            "the two-call launch-then-poll shape for Maven tests",
            "ProgramRunnerUtil",
            ideaOnly,
        ) { ExecuteCodeMavenPromptArticle().readPayload(it) },
        SecondHome(
            "why java.io / java.nio must not reach project files",
            "Reaching Project Files Behind the VFS",
            contexts,
        ) { ExecuteCodeOverviewPromptArticle().readPayload(it) },
        SecondHome(
            "which wrap CommandProcessor.executeCommand needs",
            "CommandProcessor",
            contexts,
        ) { CodingWithIntelliJThreadingPromptArticle().readPayload(it) },
        SecondHome(
            "the modality primitives and their differing failure behaviour",
            "monitorAndCloseModalDialogs",
            contexts,
        ) { CodingWithIntelliJContextApiPromptArticle().readPayload(it) },
        SecondHome(
            "what MCP Steroid already refreshes around a call",
            "markDirtyAndRefresh",
            contexts,
        ) { CodingWithIntelliJVfsPromptArticle().readPayload(it) },
        SecondHome(
            "end-to-end token accounting for IDE-side edits",
            "never cross the MCP boundary",
            contexts,
        ) { McpSteroidInfoPrompt().readPrompt() },
    )

    @Test
    fun `every fact the slim router delegates has a second home`() {
        for (home in secondHomes) {
            for (context in home.contexts) {
                assertTrue(
                    home.readIn(context).contains(home.marker),
                    "\"${home.fact}\" was moved out of the slim execute-code tool description; its " +
                        "destination article must still contain \"${home.marker}\" ($context)",
                )
            }
        }
    }

    @Test
    fun `every resource either tool description variant links resolves in the index`() {
        val known = ResourcesIndex().roots.flatMap { it.value.articles.values }.map { it.uri }.toSet()

        for (context in contexts) {
            for ((variant, prompt) in bothVariants(context)) {
                val linked = Regex("mcp-steroid://[A-Za-z0-9/_-]+")
                    .findAll(prompt)
                    .map { it.value }
                    .toSortedSet()

                assertTrue(
                    linked.isNotEmpty(),
                    "the description must link at least one resource ($variant, $context)",
                )
                for (uri in linked) {
                    assertTrue(
                        uri in known,
                        "the tool description links $uri, which is not a resource in ResourcesIndex — " +
                            "a dead link sends the agent nowhere ($variant, $context)",
                    )
                }
            }
        }
    }
}
