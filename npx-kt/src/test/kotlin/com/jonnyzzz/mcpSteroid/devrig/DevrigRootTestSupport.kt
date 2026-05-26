/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.lang.reflect.Modifier
import java.nio.file.Path

/**
 * Test-only access to [DevrigRoot]'s private cache/source override.
 *
 * The production singleton keeps the seam private so main sources cannot
 * accidentally pin root resolution to synthetic paths; tests go through this
 * helper, making the intent explicit at call sites.
 */
object DevrigRootTestSupport {
    fun overrideCodeSource(path: Path?) {
        setPrivateField("codeSourcePathForTests", path)
        setPrivateField("cachedPath", null)
    }

    fun reset() {
        setPrivateField("codeSourcePathForTests", null)
        setPrivateField("cachedPath", null)
    }

    private fun setPrivateField(name: String, value: Any?) {
        val field = DevrigRoot::class.java.getDeclaredField(name)
        field.isAccessible = true
        val target = if (Modifier.isStatic(field.modifiers)) null else DevrigRoot
        field.set(target, value)
    }
}
