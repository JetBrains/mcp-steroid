/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class ListWindowsToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ListWindowsToolSpec { unreachableHandler() }
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_list_windows")
        assertRequiredExactly(spec.inputSchema)
    }
}
