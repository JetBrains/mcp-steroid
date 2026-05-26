/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class OpenProjectToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = OpenProjectToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_open_project")
        assertRequiredExactly(schema, "project_path", "task_id", "reason")
        assertStringProperty(schema, "project_path")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "reason")
        assertBooleanProperty(schema, "trust_project")
    }
}
