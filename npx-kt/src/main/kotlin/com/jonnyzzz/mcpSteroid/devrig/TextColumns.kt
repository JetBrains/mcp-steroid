/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

/**
 * Display width: number of Unicode code points, treating surrogate pairs
 * (for example modern emoji) as one visual column.
 *
 * This deliberately does not handle East-Asian wide characters or combining
 * marks; those need a fuller terminal-width implementation.
 */
fun String.codePointWidth(): Int = codePointCount(0, length)

fun String.padEndCodePoints(width: Int): String {
    val current = codePointWidth()
    if (current >= width) return this
    return this + " ".repeat(width - current)
}
