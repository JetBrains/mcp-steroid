/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager

/**
 * Starts the MCP HTTP server as soon as the IDE is ready, before any project opens.
 * This ensures connection info is available in the Settings page immediately.
 *
 * Uses [AppLifecycleListener.appFrameCreated] (public API) instead of `appStarted()`
 * which is `@ApiStatus.Internal`. `appFrameCreated` fires slightly earlier (before project
 * opening), but `startServerIfNeeded()` is idempotent and doesn't require a project.
 */
class SteroidsMcpServerAppLifecycleListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        // startServerIfNeeded() is blocking, so run off the EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            SteroidsMcpServer.getInstance().startServerIfNeeded()
        }
    }
}
