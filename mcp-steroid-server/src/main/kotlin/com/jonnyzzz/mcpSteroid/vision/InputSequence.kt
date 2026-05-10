package com.jonnyzzz.mcpSteroid.vision

import java.awt.event.KeyEvent
import kotlinx.serialization.Serializable

@Serializable
enum class InputModifier {
    ALT,
    CTRL,
    SHIFT,
    META,
}

@Serializable
enum class MouseButton {
    LEFT,
    RIGHT,
    MIDDLE,
}

@Serializable
sealed class InputTarget {
    @Serializable data class ScreenshotPixel(val x: Int, val y: Int) : InputTarget()
    @Serializable data class ScreenPixel(val x: Int, val y: Int) : InputTarget()
    @Serializable data class Unsupported(val raw: String) : InputTarget()
}

@Serializable
sealed class InputStep {
    @Serializable data class Delay(val ms: Long) : InputStep()
    @Serializable data class StickKey(val keyCode: Int, val keyName: String) : InputStep()
    @Serializable
    data class PressKey(
        val keyCode: Int,
        val keyName: String,
        val modifiers: Set<InputModifier>,
    ) : InputStep()

    @Serializable
    data class TypeText(val text: String) : InputStep()

    @Serializable
    data class Click(
        val button: MouseButton,
        val modifiers: Set<InputModifier>,
        val target: InputTarget,
    ) : InputStep()
}

class InputSequenceParser {
    fun parse(sequence: String): List<InputStep> {
        val sanitized = stripComments(sequence)
        val trimmed = sanitized.trim()
        require(trimmed.isNotEmpty()) { "sequence is empty" }

        val tokens = tokenize(trimmed)

        require(tokens.isNotEmpty()) { "sequence is empty" }

        return tokens.map { parseToken(it) }
    }

    private fun parseToken(token: String): InputStep {
        val parts = token.split(":", limit = 2)
        require(parts.size == 2) { "Invalid token '$token'. Expected format kind:value" }
        val kind = parts[0].trim().lowercase()
        val value = parts[1].trim()
        require(value.isNotEmpty()) { "Invalid token '$token'. Missing value" }

        return when (kind) {
            "delay" -> InputStep.Delay(parseDelay(value, token))
            "stick" -> InputStep.StickKey(parseSingleKey(value, token), value.uppercase())
            "press" -> parsePress(value, token)
            "type" -> InputStep.TypeText(value)
            "click" -> parseClick(value, token)
            else -> throw IllegalArgumentException("Unknown sequence operation '$kind' in token '$token'")
        }
    }

    private fun parseDelay(value: String, token: String): Long {
        val ms = value.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid delay in token '$token'. Expected integer milliseconds")
        require(ms >= 0) { "Invalid delay in token '$token'. Must be non-negative" }
        return ms
    }

    private fun parseSingleKey(value: String, token: String): Int {
        require(!value.contains("+")) { "Invalid stick in token '$token'. Use a single key" }
        return resolveKeyCode(value)
            ?: throw IllegalArgumentException("Unknown key '$value' in token '$token'")
    }

    private fun parsePress(value: String, token: String): InputStep.PressKey {
        val (mods, keyName) = parseModifiersAndKey(value, token)
        val keyCode = resolveKeyCode(keyName)
            ?: throw IllegalArgumentException("Unknown key '$keyName' in token '$token'")
        return InputStep.PressKey(keyCode, keyName, mods)
    }

    private fun parseClick(value: String, token: String): InputStep.Click {
        val specParts = value.split("@", limit = 2)
        require(specParts.size == 2) { "Invalid click in token '$token'. Expected click:<button>@<target>" }

        val (mods, buttonName) = parseModifiersAndButton(specParts[0], token)
        val target = parseTarget(specParts[1].trim(), token)

        return InputStep.Click(button = buttonName, modifiers = mods, target = target)
    }

    private fun parseTarget(value: String, token: String): InputTarget {
        val trimmed = value.trim()
        val prefixSplit = trimmed.split(":", limit = 2)
        return if (prefixSplit.size == 2 && prefixSplit[0].lowercase() in setOf("screen", "screenshot", "component", "tag")) {
            val prefix = prefixSplit[0].lowercase()
            val payload = prefixSplit[1].trim()
            when (prefix) {
                "screen" -> parseCoordinates(payload, token)?.let { InputTarget.ScreenPixel(it.first, it.second) }
                "screenshot" -> parseCoordinates(payload, token)?.let { InputTarget.ScreenshotPixel(it.first, it.second) }
                else -> InputTarget.Unsupported("$prefix:$payload")
            }
                ?: throw IllegalArgumentException("Invalid target coordinates in token '$token'")
        } else {
            val coords = parseCoordinates(trimmed, token)
                ?: throw IllegalArgumentException("Invalid target coordinates in token '$token'")
            InputTarget.ScreenshotPixel(coords.first, coords.second)
        }
    }

    private fun parseCoordinates(value: String, token: String): Pair<Int, Int>? {
        val parts = value.split(",", limit = 2)
        if (parts.size != 2) return null
        val x = parts[0].trim().toIntOrNull()
        val y = parts[1].trim().toIntOrNull()
        if (x == null || y == null) return null
        require(x >= 0 && y >= 0) { "Invalid coordinates in token '$token'. Must be non-negative" }
        return x to y
    }

    private fun parseModifiersAndKey(value: String, token: String): Pair<Set<InputModifier>, String> {
        val parts = value.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Invalid key in token '$token'" }
        val modifiers = parts.dropLast(1).map { parseModifier(it, token) }.toSet()
        val keyName = parts.last()
        return modifiers to keyName
    }

    private fun parseModifiersAndButton(value: String, token: String): Pair<Set<InputModifier>, MouseButton> {
        val parts = value.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Invalid click in token '$token'" }
        val modifiers = parts.dropLast(1).map { parseModifier(it, token) }.toSet()
        val button = parseMouseButton(parts.last(), token)
        return modifiers to button
    }

    private fun parseModifier(value: String, token: String): InputModifier {
        return when (value.uppercase()) {
            "ALT" -> InputModifier.ALT
            "CTRL", "CONTROL" -> InputModifier.CTRL
            "SHIFT" -> InputModifier.SHIFT
            "META", "CMD", "COMMAND" -> InputModifier.META
            else -> throw IllegalArgumentException("Unknown modifier '$value' in token '$token'")
        }
    }

    private fun parseMouseButton(value: String, token: String): MouseButton {
        return when (value.uppercase()) {
            "LEFT", "LMB" -> MouseButton.LEFT
            "RIGHT", "RMB" -> MouseButton.RIGHT
            "MIDDLE", "MMB" -> MouseButton.MIDDLE
            else -> throw IllegalArgumentException("Unknown mouse button '$value' in token '$token'")
        }
    }

    private fun resolveKeyCode(name: String): Int? {
        val normalized = normalizeKeyName(name)
        keyCodeByName[normalized]?.let { return it }
        if (normalized.length == 1) {
            val code = KeyEvent.getExtendedKeyCodeForChar(normalized[0].code)
            if (code != KeyEvent.VK_UNDEFINED) return code
        }
        return null
    }

    private fun normalizeKeyName(name: String): String {
        val trimmed = name.trim().uppercase()
        return when (trimmed) {
            "ESC" -> "ESCAPE"
            "DEL" -> "DELETE"
            "BACK" -> "BACK_SPACE"
            "PGUP" -> "PAGE_UP"
            "PGDN" -> "PAGE_DOWN"
            "RETURN" -> "ENTER"
            else -> trimmed
        }
    }

    private fun tokenize(sequence: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        var index = 0
        while (index < sequence.length) {
            val ch = sequence[index]
            if (ch == '\n' || ch == '\r') {
                val token = normalizeToken(current.toString())
                if (token.isNotEmpty()) {
                    tokens.add(token)
                }
                current.setLength(0)
                index++
                continue
            }
            if (ch == ',' && isSeparatorAt(sequence, index)) {
                val token = current.toString().trim()
                if (token.isNotEmpty()) {
                    tokens.add(token)
                }
                current.setLength(0)
                index++
                continue
            }
            current.append(ch)
            index++
        }

        val tail = normalizeToken(current.toString())
        if (tail.isNotEmpty()) {
            tokens.add(tail)
        }

        return tokens
    }

    private fun isSeparatorAt(sequence: String, commaIndex: Int): Boolean {
        var index = commaIndex + 1
        while (index < sequence.length && sequence[index].isWhitespace()) {
            index++
        }
        if (index >= sequence.length) return true

        val start = index
        while (index < sequence.length && sequence[index].isLetter()) {
            index++
        }
        if (index == start) return false
        while (index < sequence.length && sequence[index].isWhitespace()) {
            index++
        }
        return index < sequence.length && sequence[index] == ':'
    }

    private fun normalizeToken(raw: String): String {
        val trimmed = raw.trim()
        val withoutTrailingComma = trimmed.removeSuffix(",").trim()
        return withoutTrailingComma
    }

    private fun stripComments(sequence: String): String {
        val builder = StringBuilder()
        for (line in sequence.lineSequence()) {
            val sanitized = line.substringBefore("#")
            builder.append(sanitized)
            builder.append('\n')
        }
        return builder.toString()
    }

    companion object {
        private val keyCodeByName: Map<String, Int> = KeyEvent::class.java.fields
            .filter { it.name.startsWith("VK_") && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                val key = field.name.removePrefix("VK_")
                key to field.getInt(null)
            }
    }
}
