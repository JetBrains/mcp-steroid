/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.io.File

internal object SevenZipLocatorTestSupport {
    fun overrideCacheRoot(dir: File?) {
        SevenZipLocator.cacheRootOverride = dir
    }

    fun reset() = overrideCacheRoot(null)
}
