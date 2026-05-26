/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

sealed class IdeDistribution {
    abstract val product: IdeProduct

    /**
     * Resolves a release of [product] on [channel] from the public products API.
     *
     * When [version] is null the resolver picks the latest available entry on
     * the channel. When [version] is set it filters by either the public
     * version string (e.g. `"2026.1"`, picks the latest 2026.1.x patch) or
     * a per-major snapshot tag (e.g. `"262-EAP-SNAPSHOT"`, picks the latest
     * 262 EAP build) or an exact build number (e.g. `"262.6228.19"`).
     * Rolling cross-major tags like `LATEST-EAP-SNAPSHOT` are forbidden by
     * the matrix (see [McpSteroidIdeTargets]).
     */
    data class Latest(
        override val product: IdeProduct = IdeProduct.IntelliJIdea,
        val channel: IdeChannel = IdeChannel.STABLE,
        val version: String? = null,
    ) : IdeDistribution()

    data class FromUrl(
        override val product: IdeProduct,
        val url: String,
        val fileName: String? = null,
        val checksumUrl: String? = null,
        val expectedSha256: String? = null,
    ) : IdeDistribution()

}
