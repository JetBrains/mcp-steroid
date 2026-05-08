/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.McpWindowInfo
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Validates that MCP Steroid's [com.jonnyzzz.mcpSteroid.execution.VcsConfirmationSilencer]
 * keeps the IDE responsive when an agent creates new unversioned files in a
 * Git-tracked project.
 *
 * Without the silencer, [steroid_execute_code] scripts that call
 * `vf.findOrCreateChildData(...)` + `VfsUtil.saveText(...)` on a Git project
 * (the pattern captured under `~/Work/mcp-steroid-data/eid_*-step3-index-manager-tests/`)
 * surface IntelliJ's "Add files to Git?" confirmation: the platform default for
 * `VcsConfiguration.StandardConfirmation.ADD` is `SHOW_CONFIRMATION`, the new
 * VFS file goes through the Git plugin's `VcsVFSListener`, and a modal pops on
 * the EDT, blocking every subsequent agent step.
 *
 * `VcsConfirmationSilencer` runs on `postStartupActivity` and flips that value
 * to `DO_NOTHING_SILENTLY` once per project. This test confirms:
 * 1. the silencer ran (`ADD_CONFIRMATION=2`);
 * 2. creating an unversioned file via the same agent-style script does NOT
 *    surface a modal dialog within 30 s.
 */
class GitVcsAddFileDialogTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `creating new file via execute_code in git-tracked project does not show VCS add modal dialog`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            "ide-agent",
            consoleTitle = "Git VCS Add Dialog",
        )
        val console = session.console
        val gitProjectPath = "/home/agent/git-vcs-add-dialog-repro"

        console.writeStep(1, "Initialize a Git-tracked secondary project on disk")
        session.scope.startProcessInContainer {
            this
                .args(
                    "bash", "-lc",
                    """
                        set -euo pipefail
                        rm -rf "$gitProjectPath"
                        mkdir -p "$gitProjectPath/src/main/java/com/example"
                        printf '# Git VCS Add-File Dialog Repro\n' > "$gitProjectPath/README.md"
                        printf 'rootProject.name = "vcs-add-repro"\n' > "$gitProjectPath/settings.gradle.kts"
                        printf 'plugins { java }\n' > "$gitProjectPath/build.gradle.kts"
                        printf 'package com.example;\npublic class Hello {}\n' > "$gitProjectPath/src/main/java/com/example/Hello.java"
                        git -C "$gitProjectPath" init -q -b main
                        git -C "$gitProjectPath" config user.email repro@local
                        git -C "$gitProjectPath" config user.name repro
                        git -C "$gitProjectPath" -c commit.gpgsign=false add -A
                        git -C "$gitProjectPath" -c commit.gpgsign=false commit -qm "init"
                        chown -R agent:agent "$gitProjectPath"
                    """.trimIndent(),
                )
                .user("0:0")
                .timeoutSeconds(60)
                .description("Initialize Git-tracked secondary project")
        }.awaitForProcessFinish().assertExitCode(0, "Failed to initialize Git-tracked project")

        console.writeStep(2, "Open the Git-tracked project via MCP")
        session.mcpSteroid.mcpOpenProject(gitProjectPath, trustProject = true)

        console.writeStep(3, "Wait for the project to finish indexing")
        waitForProjectIndexed(session, gitProjectPath)

        val gitProjectName = session.mcpSteroid.mcpListProjects()
            .single { it.path == gitProjectPath }
            .name

        console.writeStep(4, "Confirm Git VCS detected and silencer flipped ADD confirmation to DO_NOTHING_SILENTLY")
        val vcsCheck = session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.vcs.ProjectLevelVcsManager
                import com.intellij.openapi.vcs.VcsConfiguration

                val pm = ProjectLevelVcsManager.getInstance(project)
                val vcss = pm.getAllActiveVcss()
                vcss.forEach { println("ACTIVE_VCS=${'$'}{it.name}") }
                println("HAS_GIT=${'$'}{vcss.any { it.name.equals("Git", ignoreCase = true) }}")
                val addConfirm = pm.getStandardConfirmation(
                    VcsConfiguration.StandardConfirmation.ADD,
                    vcss.firstOrNull(),
                )
                // VcsShowConfirmationOption.Value: 0 = SHOW_CONFIRMATION (default → modal),
                // 1 = DO_ACTION_SILENTLY, 2 = DO_NOTHING_SILENTLY (silencer flipped to this).
                println("ADD_CONFIRMATION=${'$'}{addConfirm?.value}")
            """.trimIndent(),
            taskId = "vcs-add-dialog",
            reason = "Confirm Git VCS detected and silencer set ADD confirmation to DO_NOTHING_SILENTLY",
            projectName = gitProjectName,
            dialogKiller = false,
        )
        vcsCheck.assertExitCode(0, "VCS state probe should succeed")
            .assertOutputContains("HAS_GIT=true", "ADD_CONFIRMATION=2")

        console.writeStep(5, "Create a new unversioned file via findOrCreateChildData + VfsUtil.saveText")
        val createFile = session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.application.ApplicationManager
                import com.intellij.openapi.command.WriteCommandAction
                import com.intellij.openapi.vfs.LocalFileSystem
                import com.intellij.openapi.vfs.VfsUtil

                val dir = "$gitProjectPath/src/main/java/com/example"
                val name = "NewlyCreated.java"
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(dir)
                    ?: error("dir not found: " + dir)
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) {
                        val f = vf.findOrCreateChildData(null, name)
                        VfsUtil.saveText(f, "package com.example; public class NewlyCreated {}\n")
                        println("CREATED=" + f.path)
                    }
                }
            """.trimIndent(),
            taskId = "vcs-add-dialog",
            reason = "Mirror agent script in ~/Work/mcp-steroid-data/eid_20260507T090*/script.kts that creates a new file in a Git-tracked project",
            projectName = gitProjectName,
            dialogKiller = false,
        )
        createFile.assertExitCode(0, "execute_code that creates a new file should succeed")
            .assertOutputContains("CREATED=")

        console.writeStep(6, "Poll mcpListWindows to confirm no VCS add-file modal dialog appears")
        val modalSeen = waitForModalDialog(session, gitProjectPath, timeoutMillis = 30_000L)
        Assertions.assertFalse(
            modalSeen,
            "Unexpected Add-to-Git? modal dialog after creating an unversioned file in a Git-tracked project. " +
                    "VcsConfirmationSilencer should have flipped ADD confirmation to DO_NOTHING_SILENTLY at project open.",
        )
        console.writeSuccess("VcsConfirmationSilencer suppressed the Git VCS add-file modal dialog")
    }

    /**
     * Wait until the project at [projectPath] is open, indexed, and not blocked
     * by a startup modal. Different from [waitForModalDialog]: here a modal is
     * a transient blocker we want to time out on.
     */
    private fun waitForProjectIndexed(
        session: IntelliJContainer,
        projectPath: String,
        timeoutMillis: Long = 180_000L,
    ) {
        val startedAt = System.currentTimeMillis()
        var lastStatus = "not polled"

        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val windows = session.mcpSteroid.mcpListWindows(timeoutSeconds = 120)
            val projectWindows = windows.filter { it.projectPath == projectPath }
            if (projectWindows.any { it.projectInitialized == true && it.indexingInProgress == false && !it.modalDialogShowing }) {
                return
            }
            lastStatus = describe(projectWindows.ifEmpty { windows })
            Thread.sleep(1_000L)
        }

        Assertions.fail<Unit>(
            "Timed out waiting for $projectPath to finish indexing. Last status: $lastStatus",
        )
    }

    /**
     * Poll [McpSteroidDriver.mcpListWindows] for [timeoutMillis] and return
     * `true` if any window for the project at [projectPath] reports
     * `modalDialogShowing=true` at any point. The Git plugin's confirmation
     * handler runs on the EDT but is fired from VFS notifications scheduled by
     * RefreshQueue, so the dialog can take a few seconds to appear after the
     * script returns — we poll for the full window before concluding nothing
     * surfaced.
     */
    private fun waitForModalDialog(
        session: IntelliJContainer,
        projectPath: String,
        timeoutMillis: Long,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        var lastStatus = "not polled"

        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val windows = session.mcpSteroid.mcpListWindows(timeoutSeconds = 30)
            val projectWindows = windows.filter { it.projectPath == projectPath }
            if (projectWindows.any { it.modalDialogShowing }) {
                println("[VCS-ADD-DIALOG] Modal dialog detected: ${describe(projectWindows)}")
                return true
            }
            lastStatus = describe(projectWindows.ifEmpty { windows })
            Thread.sleep(1_000L)
        }

        println("[VCS-ADD-DIALOG] No modal dialog within ${timeoutMillis}ms. Last status: $lastStatus")
        return false
    }

    private fun describe(windows: List<McpWindowInfo>): String =
        windows.joinToString(prefix = "[", postfix = "]") { window ->
            "name=${window.projectName}, path=${window.projectPath}, modal=${window.modalDialogShowing}, " +
                    "indexing=${window.indexingInProgress}, initialized=${window.projectInitialized}"
        }
}
