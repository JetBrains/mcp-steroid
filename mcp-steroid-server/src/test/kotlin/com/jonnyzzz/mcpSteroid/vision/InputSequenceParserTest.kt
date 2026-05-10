/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent

class InputSequenceParserTest {
    private val parser = InputSequenceParser()

    @Test
    fun parseBasicSequence() {
        val steps = parser.parse("stick:ALT, delay:400, press:F4, type:hurra")

        assertEquals(4, steps.size)
        assertTrue(steps[0] is InputStep.StickKey)
        assertTrue(steps[1] is InputStep.Delay)
        assertTrue(steps[2] is InputStep.PressKey)
        assertTrue(steps[3] is InputStep.TypeText)

        val stick = steps[0] as InputStep.StickKey
        assertEquals(KeyEvent.VK_ALT, stick.keyCode)

        val delay = steps[1] as InputStep.Delay
        assertEquals(400, delay.ms)

        val press = steps[2] as InputStep.PressKey
        assertEquals(KeyEvent.VK_F4, press.keyCode)

        val type = steps[3] as InputStep.TypeText
        assertEquals("hurra", type.text)
    }

    @Test
    fun parsePressWithModifiers() {
        val steps = parser.parse("press:CTRL+SHIFT+P")
        assertEquals(1, steps.size)

        val press = steps[0] as InputStep.PressKey
        assertEquals(KeyEvent.VK_P, press.keyCode)
        assertTrue(press.modifiers.contains(InputModifier.CTRL))
        assertTrue(press.modifiers.contains(InputModifier.SHIFT))
    }

    @Test
    fun parseClickWithModifiersAndTarget() {
        val steps = parser.parse("click:CTRL+Left@120,200")
        assertEquals(1, steps.size)

        val click = steps[0] as InputStep.Click
        assertEquals(MouseButton.LEFT, click.button)
        assertTrue(click.modifiers.contains(InputModifier.CTRL))
        assertEquals(InputTarget.ScreenshotPixel(120, 200), click.target)
    }

    @Test
    fun parseClickWithScreenTarget() {
        val steps = parser.parse("click:Right@screen:10,20")
        assertEquals(1, steps.size)

        val click = steps[0] as InputStep.Click
        assertEquals(MouseButton.RIGHT, click.button)
        assertEquals(InputTarget.ScreenPixel(10, 20), click.target)
    }

    @Test
    fun parseTypeWithComma() {
        val steps = parser.parse("type:hello, world")
        assertEquals(1, steps.size)

        val type = steps[0] as InputStep.TypeText
        assertEquals("hello, world", type.text)
    }

    @Test
    fun parseCommaSeparatorByStepPrefix() {
        val steps = parser.parse("type:hello, delay:10")
        assertEquals(2, steps.size)

        val type = steps[0] as InputStep.TypeText
        assertEquals("hello", type.text)

        val delay = steps[1] as InputStep.Delay
        assertEquals(10, delay.ms)
    }

    @Test
    fun parseNewLines() {
        val steps = parser.parse(
            """
            stick:ALT
            press:F4
            """.trimIndent()
        )

        assertEquals(2, steps.size)
        assertTrue(steps[0] is InputStep.StickKey)
        assertTrue(steps[1] is InputStep.PressKey)
    }

    @Test
    fun parseComments() {
        val steps = parser.parse(
            """
            stick:ALT # hold modifier
            # comment line
            press:F4 # close window
            """.trimIndent()
        )

        assertEquals(2, steps.size)
        assertTrue(steps[0] is InputStep.StickKey)
        assertTrue(steps[1] is InputStep.PressKey)
    }

    @Test
    fun parseTrailingCommaBeforeNewline() {
        val steps = parser.parse(
            """
            stick:ALT,
            press:F4
            """.trimIndent()
        )

        assertEquals(2, steps.size)
        assertTrue(steps[0] is InputStep.StickKey)
        assertTrue(steps[1] is InputStep.PressKey)
    }

    @Test
    fun rejectsUnknownOperation() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse("noop:1")
        }
    }
}
