/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class ExecuteFeedbackToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ExecuteFeedbackToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_execute_feedback")
        assertRequiredExactly(schema, "project_name", "task_id", "success_rating", "explanation")
        assertStringProperty(schema, "project_name")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "execution_id")
        assertStringProperty(schema, "explanation")
        assertStringProperty(schema, "code")
        assertNumberProperty(schema, "success_rating", minimum = 0.0, maximum = 1.0)
    }
}
