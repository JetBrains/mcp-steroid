/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class VisionInputToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = VisionInputToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_input")
        assertRequiredExactly(schema, "project_name", "task_id", "reason", "window_id", "sequence")
        assertStringProperty(schema, "project_name")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "reason")
        assertStringProperty(schema, "window_id")
        assertStringProperty(schema, "sequence")
    }
}
