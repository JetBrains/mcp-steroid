/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo
import com.jonnyzzz.mcpSteroid.IdeInfo

fun IdeInfo.Companion.ofApplication(): IdeInfo {
    val appInfo = ApplicationInfo.getInstance()
    return IdeInfo(
        name = appInfo.fullApplicationName,
        version = appInfo.fullVersion,
        build = appInfo.build.asString()
    )
}
