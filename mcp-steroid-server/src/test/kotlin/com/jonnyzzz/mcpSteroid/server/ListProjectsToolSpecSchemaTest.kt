/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class ListProjectsToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ListProjectsToolSpec { unreachableHandler() }
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_list_projects")
        assertRequiredExactly(spec.inputSchema)
    }
}
