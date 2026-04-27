/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Fire-and-forget VFS refresh after every [steroid_execute_code] MCP call.
 *
 * After a script finishes it may have written to disk (`VfsUtil.saveText`,
 * `vf.setBinaryContent`, external processes running in parallel, etc.).
 * If another tool — native `Edit`, a peer process, a recently-run build —
 * also touched files, IntelliJ's VFS can drift out of sync with the real
 * filesystem. A subsequent semantic query (find-references, rename, hierarchy)
 * then sees stale PSI. This service re-anchors VFS to disk **asynchronously**
 * so the current MCP response is not delayed.
 *
 * ### Why this call
 * [VfsUtil.markDirtyAndRefresh] with `async = true`:
 * - schedules the refresh through [com.intellij.openapi.vfs.newvfs.RefreshQueue],
 *   which coalesces concurrent requests on a single-threaded dispatcher — so
 *   back-to-back `steroid_execute_code` calls do not pile up refresh work;
 * - only processes files whose timestamps actually changed, so the steady-state
 *   cost when nothing was written is effectively free;
 * - does not require a read/write action and is safe from a background coroutine.
 *
 * API reference: `platform/analysis-api/src/com/intellij/openapi/vfs/VfsUtil.java`
 * (markDirtyAndRefresh) and `platform/platform-impl/src/com/intellij/openapi/vfs/newvfs/RefreshQueueImpl.kt`.
 *
 * ### Scoping
 * The service owns its own [CoroutineScope] injected by the platform at project
 * level, so the fire-and-forget launch is automatically cancelled on project
 * dispose. Exceptions are logged at DEBUG and swallowed — a failed refresh must
 * never fail the MCP response.
 */
@Service(Service.Level.PROJECT)
class VfsRefreshService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val log = thisLogger()

    /**
     * Schedules an async VFS refresh on the project's content roots. Returns
     * immediately; the refresh runs on the RefreshQueue thread.
     *
     * Safe to call from any coroutine context. Must not be called while holding
     * a read or write action (the platform forbids nested refresh from inside
     * an action).
     *
     * This is the [tail-of-every-exec_code] path: even if the script threw, we
     * still want the next semantic query to see an up-to-date VFS, which is why
     * [com.jonnyzzz.mcpSteroid.execution.ExecutionManager] calls this from a
     * `finally` block.
     */
    fun scheduleAsyncRefresh() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val base = projectBaseVf() ?: return@launch
                VfsUtil.markDirtyAndRefresh(
                    /* async = */ true,
                    /* recursive = */ true,
                    /* reloadChildren = */ false,
                    base,
                )
            } catch (e: Exception) {
                log.debug("Async VFS refresh failed (non-fatal)", e)
            }
        }
    }

    /**
     * Triggers a VFS refresh and suspends until it finishes. Used before the
     * Kotlin compiler reads the script's source / classpath so kotlinc sees
     * filesystem changes made since the previous `steroid_execute_code`:
     * otherwise a user who edited a file between calls would get stale
     * compilation input.
     *
     * The refresh itself runs on [com.intellij.openapi.vfs.newvfs.RefreshQueue]
     * (single-threaded, coalescing). This method bridges the Runnable
     * completion callback to a suspending continuation via
     * [CompletableDeferred]. Hard-capped at 30 s so a pathological
     * refresh cannot hang compilation forever.
     */
    suspend fun awaitRefresh() {
        val base = projectBaseVf() ?: return
        val done = CompletableDeferred<Unit>()
        try {
            // RefreshQueue.refresh with async=true returns immediately — no IO dispatch
            // needed. The actual refresh happens on RefreshQueueImpl's own thread; the
            // finishRunnable fires on EDT when done, which completes our deferred.
            RefreshQueue.getInstance().refresh(
                /* async = */ true,
                /* recursive = */ true,
                /* finishRunnable = */ Runnable { done.complete(Unit) },
                /* files = */ base,
            )
        } catch (e: Exception) {
            log.debug("awaitRefresh schedule failed (non-fatal)", e)
            done.complete(Unit)
        }
        val result = withTimeoutOrNull(30.seconds) { done.await() }
        if (result == null) {
            log.warn("awaitRefresh timed out after 30 s; compilation will proceed against possibly stale VFS")
        }
    }

    private fun projectBaseVf(): VirtualFile? {
        val basePath = project.basePath
        if (basePath == null) {
            log.debug("No project basePath; skipping VFS refresh")
            return null
        }
        val base = LocalFileSystem.getInstance().findFileByPath(basePath)
        if (base == null) {
            log.debug("Project basePath '$basePath' has no VirtualFile; skipping")
        }
        return base
    }
}

inline val Project.vfsRefreshService: VfsRefreshService get() = service()
