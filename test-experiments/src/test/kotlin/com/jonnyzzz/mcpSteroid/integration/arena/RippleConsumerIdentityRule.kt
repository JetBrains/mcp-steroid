/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * How a hidden consumer may name the identity its case transforms away.
 *
 * **The trap this closes.** Every case's prompt orders that no use of the old identity survive, so a
 * thorough agent text-searches the tree for it. The hidden consumer is a file the agent is never told
 * about and whose modification VOIDS the arm, so an old name spelled out there turns diligence into a
 * disqualification — build 1031230755, pilot pass 3, claude+mcp. Worse than a plain bug on two counts:
 * it is intermittent, so it voids the careful arms and keeps the careless ones, and it is
 * self-inflicted, since [RippleNameEscapeRule] already refuses a TARGET whose name is addressable as a
 * string while our own overlay introduced exactly such a string.
 *
 * **The rule, in the same words [findings] enforces.** For each of its kind's
 * [RippleTarget.oldIdentitySearchTokens], a consumer must satisfy BOTH:
 *
 * 1. the token appears on no line of the patch contiguously — code, comment and message alike, because
 *    a text search does not care which it lands in; and
 * 2. after adjacent string fragments are joined and each constant helper is inlined at its call sites,
 *    the token appears on at least one line that also performs a [nameResolvingCalls] lookup.
 *
 * **Why (2) is phrased about the LOOKUP and not about the file.** Its first version asked only that the
 * token appear somewhere once the fragments were joined. A review broke that in one line: keep the
 * assembled name in a helper, never call it, assert `true` — both halves pass and the consumer proves
 * nothing, reporting the same verdict whether or not a compatibility alias survived. The fragments
 * existing is not evidence; the fragments reaching the call that resolves the name is. That
 * counterexample is a fixture in `RippleCaseRegistryTest`, so the bypass cannot come back unnoticed.
 *
 * **Why assertion wrappers do not qualify.** `assertThrows(`, `fail(`, `assertTrue(` and friends are
 * deliberately absent from [nameResolvingCalls]. Every consumer also interpolates its assembled name
 * into a FAILURE MESSAGE, and a message proves nothing about what was looked up — admitting those
 * shapes would readmit the dead helper through its own error text.
 */
object RippleConsumerIdentityRule {

    /**
     * Calls that resolve a name at RUNTIME, which is the only way a consumer can name an identity that
     * exists on just one side of the transformation.
     *
     * The family uses two of these today — `Class.forName(` for the type-level kinds and `getMethod(`
     * for the method-level ones. The rest are the same reflective family, listed so a future consumer
     * that reaches for the obvious neighbour is not failed for a distinction without a difference.
     */
    val nameResolvingCalls: List<String> = listOf(
        "Class.forName(",
        "getMethod(",
        "getDeclaredMethod(",
        "getField(",
        "getDeclaredField(",
        "getConstructor(",
        "getDeclaredConstructor(",
    )

    /** What to do about a finding, appended to the failure so the reader does not have to guess. */
    val remedy: String = "Assemble the name from fragments on one line — `\"get\" + \"Name\"` — and pass " +
        "it to the reflective lookup the assertion is built on."

    /** Everything wrong with one consumer's spelling of one token; empty means the consumer is fine. */
    fun findings(patch: String, token: String): List<String> {
        val spelled = patch.lines().withIndex()
            .filter { it.value.contains(token) }
            .map { "spelled contiguously at line ${it.index + 1}: ${it.value.trim()}" }

        val resolved = resolveConstants(joinJavaLiteralFragments(patch))
        val lookedUp = resolved.any { line ->
            line.contains(token) && nameResolvingCalls.any { line.contains(it) }
        }
        val unused = if (lookedUp) emptyList()
        else listOf(
            "names '$token' through no runtime lookup: with its fragments joined and its constant " +
                "helpers inlined, no line both holds the token and calls one of " +
                "${nameResolvingCalls.joinToString(", ")} — so the assertion would report the same " +
                "verdict whether or not the old identity survived"
        )
        return spelled + unused
    }

    /**
     * Every line with `helper()` replaced by the constant that `private static String helper()` returns.
     *
     * Textual on purpose: this runs over a PATCH, which is not a compilable file and has a `+` on every
     * line, so there is no PSI to ask. It recognises exactly the shape the family's consumers use — a
     * no-argument private static String helper whose body is a single string `return` — and nothing
     * cleverer, because a helper this rule cannot follow must fail the guard rather than pass it by
     * accident.
     */
    private fun resolveConstants(joined: String): List<String> {
        val declaration = Regex("""private\s+static\s+String\s+(\w+)\s*\(\s*\)""")
        val returned = Regex("""return\s+"([^"]*)"\s*;""")
        val lines = joined.lines()
        val constants = LinkedHashMap<String, String>()
        lines.forEachIndexed { index, line ->
            val name = declaration.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val body = lines.drop(index).take(HELPER_BODY_LINES)
                .firstNotNullOfOrNull { returned.find(it)?.groupValues?.get(1) }
            if (body != null) constants[name] = body
        }
        return lines.map { line ->
            if (declaration.containsMatchIn(line)) line
            else constants.entries.fold(line) { acc, (name, value) -> acc.replace("$name()", "\"$value\"") }
        }
    }

    /** How far past a helper's signature its `return` may sit; the family writes it on the next line. */
    private const val HELPER_BODY_LINES = 4
}

/**
 * Joins adjacent Java string-literal fragments: `"a" + "b"` becomes `"ab"`.
 *
 * Only a `+` between two literals ON ONE LINE is joined, which is the shape the family's consumers
 * use. A concatenation split across lines is deliberately NOT joined: a patch body carries a leading
 * `+` on every line, so a multi-line concat cannot be recognised without guessing, and a consumer
 * written that way must fail the guard rather than pass it by accident.
 */
fun joinJavaLiteralFragments(text: String): String =
    text.lines().joinToString("\n") { line -> line.replace(Regex("""" *\+ *""""), "") }
