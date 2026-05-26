/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

sealed class IdeDistribution {
    abstract val product: IdeProduct

    /** Resolves the latest release of [product] on [channel] from the public products API. */
    data class Latest(
        override val product: IdeProduct = IdeProduct.IntelliJIdea,
        val channel: IdeChannel = IdeChannel.STABLE,
    ) : IdeDistribution()

    data class FromUrl(
        override val product: IdeProduct,
        val url: String,
        val fileName: String? = null,
        val checksumUrl: String? = null,
        val expectedSha256: String? = null,
    ) : IdeDistribution()

}
