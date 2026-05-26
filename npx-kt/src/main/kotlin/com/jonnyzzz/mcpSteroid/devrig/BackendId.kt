/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct

data class BackendId(
    val product: IdeProduct,
    val version: String?,
) {
    val productKey: String get() = product.id

    fun withVersionOverride(versionOverride: String?): BackendId {
        return if (versionOverride.isNullOrBlank()) this else copy(version = validateBackendVersion(versionOverride))
    }
}

data class ResolvedBackendId(
    val product: IdeProduct,
    val version: String,
) {
    val id: String get() = "${product.id}-$version"
}

fun parseBackendId(raw: String): BackendId {
    require(raw.isNotBlank()) { "Backend id must not be blank" }

    val colonParts = raw.split(':')
    if (colonParts.size > 2) {
        throw IllegalArgumentException("Backend id '$raw' must contain at most one ':'")
    }
    if (colonParts.size == 2) {
        val product = parseManagedProductKey(colonParts[0])
        return BackendId(product, validateBackendVersion(colonParts[1]))
    }

    parseManagedProductKeyOrNull(raw)?.let { return BackendId(it, version = null) }

    val product = IdeProduct.knownProducts
        .sortedByDescending { it.id.length }
        .firstOrNull { raw.startsWith("${it.id}-") }
        ?: throw IllegalArgumentException(
            "Unsupported backend product in '$raw'. Known product keys: ${knownManagedProductKeys()}"
        )
    val version = raw.removePrefix("${product.id}-")
    return BackendId(product, validateBackendVersion(version))
}

fun validateBackendVersion(raw: String): String {
    val version = raw.trim()
    require(version.isNotBlank()) { "Backend version must not be blank" }
    require(isSupportedBackendVersion(version)) {
        "Backend version '$raw' contains unsupported characters. " +
            "Use letters, digits, '.', '_' or '-'."
    }
    return version
}

fun isSupportedBackendVersion(raw: String): Boolean {
    val version = raw.trim()
    return version.isNotBlank() && version.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
}

private val backendVersionTokenRegex = Regex("""\d+|\D+""")

fun compareBackendVersions(left: String, right: String): Int {
    if (left == right) return 0
    val leftTokens = backendVersionTokenRegex.findAll(left).map { it.value }.toList()
    val rightTokens = backendVersionTokenRegex.findAll(right).map { it.value }.toList()
    val maxSize = maxOf(leftTokens.size, rightTokens.size)
    for (index in 0 until maxSize) {
        val leftToken = leftTokens.getOrNull(index) ?: return -1
        val rightToken = rightTokens.getOrNull(index) ?: return 1
        val tokenCompare = compareBackendVersionTokens(leftToken, rightToken)
        if (tokenCompare != 0) return tokenCompare
    }
    return 0
}

private fun compareBackendVersionTokens(left: String, right: String): Int {
    val leftIsNumber = left.all { it.isDigit() }
    val rightIsNumber = right.all { it.isDigit() }
    return when {
        leftIsNumber && rightIsNumber -> {
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            compareValuesBy(normalizedLeft, normalizedRight, { it.length }, { it })
        }
        leftIsNumber != rightIsNumber -> if (leftIsNumber) 1 else -1
        else -> left.compareTo(right)
    }
}

private fun parseManagedProductKey(raw: String): IdeProduct {
    return parseManagedProductKeyOrNull(raw)
        ?: throw IllegalArgumentException(
            "Unsupported backend product '$raw'. Known product keys: ${knownManagedProductKeys()}"
        )
}

private fun parseManagedProductKeyOrNull(raw: String): IdeProduct? {
    return IdeProduct.knownProducts.firstOrNull { it.id == raw }
}

private fun knownManagedProductKeys(): String = IdeProduct.knownProducts.joinToString { it.id }
