/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationManager
import com.jonnyzzz.mcpSteroid.IntelliJMcpServerInfo

/**
 * Indirection over the bundled IntelliJ MCP Server plugin
 * (`com.intellij.mcpServer`). The interface is defined here; the real
 * implementation ([IntelliJMcpServerProbeImpl]) is registered as the
 * application service for this interface **only** inside the optional
 * config file `META-INF/mcpServer-integration.xml`, which IntelliJ
 * processes only when the optional dependency on
 * `com.intellij.mcpServer` is satisfied.
 *
 * If the optional dependency is missing or the user has disabled the
 * MCP server plugin, `getInstanceOrNull()` returns `null` and callers
 * (e.g. [ServerUrlWriter]) treat the IDE-bundled MCP server as
 * unavailable. No reflection is involved — and the real impl class is
 * never loaded by the JVM unless the optional config file activates.
 *
 * See `docs/intellij-builtin-servers.md` for the API surface this
 * indirection wraps.
 */
interface IntelliJMcpServerProbe {
    fun probe(): IntelliJMcpServerInfo?

    companion object {
        fun getInstanceOrNull(): IntelliJMcpServerProbe? =
            ApplicationManager.getApplication().getService(IntelliJMcpServerProbe::class.java)
    }
}
