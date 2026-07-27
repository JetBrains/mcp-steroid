/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExecCodeDescriptionVariantTest {

    @Test
    fun `absent or blank selects the full description`() {
        for (raw in listOf(null, "", "   ", "\n")) {
            assertEquals(
                ExecCodeDescriptionVariant.FULL,
                ExecCodeDescriptionVariant.parse(raw),
                "an unset ${ExecCodeDescriptionVariant.ENV_VAR} must keep serving the repo default",
            )
        }
    }

    @Test
    fun `wire values map to their variant regardless of case and padding`() {
        for (variant in ExecCodeDescriptionVariant.entries) {
            for (raw in listOf(variant.wire, variant.wire.uppercase(), " ${variant.wire} ")) {
                assertEquals(variant, ExecCodeDescriptionVariant.parse(raw), "raw='$raw'")
            }
        }
    }

    @Test
    fun `an unknown value fails fast instead of falling back to the default`() {
        val failure = assertThrows<IllegalStateException> { ExecCodeDescriptionVariant.parse("sslim") }
        assertTrue(
            failure.message!!.contains(ExecCodeDescriptionVariant.ENV_VAR) &&
                ExecCodeDescriptionVariant.entries.all { failure.message!!.contains(it.wire) },
            "the failure must name the variable and the supported values, got: ${failure.message}",
        )
    }

    @Test
    fun `each variant serves its own marked text and slim is the shorter one`() {
        val texts = ExecCodeDescriptionVariant.entries.associateWith {
            it.readDescription(PromptsContext.Generic)
        }

        for ((variant, text) in texts) {
            assertTrue(
                text.contains(variant.marker),
                "${variant.wire} must contain its own marker '${variant.marker}' — the marker is how a " +
                    "caller that only sees the served description identifies the variant",
            )
            for (other in ExecCodeDescriptionVariant.entries - variant) {
                assertTrue(
                    !text.contains(other.marker),
                    "${variant.wire} must not contain ${other.wire}'s marker '${other.marker}' — the " +
                        "markers stop distinguishing the variants",
                )
            }
        }

        val full = texts.getValue(ExecCodeDescriptionVariant.FULL)
        val slim = texts.getValue(ExecCodeDescriptionVariant.SLIM)
        assertTrue(
            slim.length < full.length,
            "the slim variant exists to buy back tool-definition context (slim=${slim.length}, " +
                "full=${full.length}); the size ratio itself is pinned by :prompts PromptRoutingContractTest",
        )
    }

    @Test
    fun `the tool spec serves the variant selected for this process`() {
        val variant = ExecCodeDescriptionVariant.fromEnvironment()
        val description = ExecuteCodeToolSpec { unreachableHandler() }.description

        assertTrue(
            description.contains(variant.marker),
            "steroid_execute_code must serve the ${variant.wire} description selected by " +
                "${ExecCodeDescriptionVariant.ENV_VAR}",
        )
    }
}
