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
 *    the token appears on a line that performs a [nameResolvingCalls] lookup AND that line sits inside
 *    a `@Test` method, or inside a method reachable from one.
 *
 * **This guard has been defeated twice, and both bypasses are fixtures in `RippleCaseRegistryTest`.**
 * That is the only reason this third version can be called stronger rather than merely newer, and it is
 * the reason to distrust a fourth relaxation that arrives without a fixture of its own.
 *
 * - *Version 1* asked only that the token appear once the fragments were joined. The fragments alone
 *   satisfy that: keep the assembled name in a helper, never call it, assert `true`, and the consumer
 *   passes while reporting the same verdict whether or not a compatibility alias survived.
 * - *Version 2* additionally required a [nameResolvingCalls] lookup on the token's line — anywhere in
 *   the file. A never-called `private static void decoyLookupNeverCalled()` holding
 *   `Class.forName(oldFqn())` satisfies that literally, and the consumer still proves nothing.
 *
 * Both bypasses are the same defect: asking whether the right TEXT exists rather than whether the
 * assertion actually depends on it. Hence version 3's reachability requirement — evidence is a lookup
 * the `@Test` method really executes. Reachability through a private helper stays legal, because that
 * is the shape all seven consumers use for the NAME; a method nothing calls is not evidence.
 *
 * **Why assertion wrappers do not qualify.** `assertThrows(`, `fail(`, `assertTrue(` and friends are
 * deliberately absent from [nameResolvingCalls]. Every consumer also interpolates its assembled name
 * into a FAILURE MESSAGE, and a message proves nothing about what was looked up — admitting those
 * shapes would readmit both bypasses through their own error text.
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
        "it to a reflective lookup that the @Test method itself runs, directly or through a helper it calls."

    /** Everything wrong with one consumer's spelling of one token; empty means the consumer is fine. */
    fun findings(patch: String, token: String): List<String> {
        val spelled = patch.lines().withIndex()
            .filter { it.value.contains(token) }
            .map { "spelled contiguously at line ${it.index + 1}: ${it.value.trim()}" }

        val resolved = resolveConstants(joinJavaLiteralFragments(patch))
        val code = withoutComments(resolved)
        val methods = declaredMethods(code, resolved)
        val reachable = reachableFromTests(code, methods)

        val lookups = code.indices.filter { line ->
            code[line].contains(token) && nameResolvingCalls.any { code[line].contains(it) }
        }
        val executed = lookups.filter { line -> methods.any { it.holds(line) && it.name in reachable } }

        val unused = when {
            executed.isNotEmpty() -> emptyList()
            lookups.isEmpty() -> listOf(
                "names '$token' through no runtime lookup: with its fragments joined and its constant " +
                    "helpers inlined, no line both holds the token and calls one of " +
                    "${nameResolvingCalls.joinToString(", ")} — so the assertion would report the same " +
                    "verdict whether or not the old identity survived"
            )
            else -> listOf(
                "looks up '$token' only from ${lookups.map { line -> methods.firstOrNull { it.holds(line) }?.name ?: "<no method>" }}" +
                    ", which no @Test method reaches: the lookup is dead code, so the assertion would " +
                    "report the same verdict whether or not the old identity survived"
            )
        }
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

    /**
     * The same lines with comment text blanked out.
     *
     * Braces and calls inside a comment are not code, and the family's own consumers carry `{@link X}`
     * in their KDoc — counting that brace would misplace every method boundary after it.
     */
    private fun withoutComments(lines: List<String>): List<String> {
        var inBlock = false
        return lines.map { line ->
            var text = line
            if (inBlock) {
                val close = text.indexOf("*/")
                if (close < 0) return@map "" else {
                    inBlock = false
                    text = text.substring(close + 2)
                }
            }
            val open = text.indexOf("/*")
            if (open >= 0) {
                val close = text.indexOf("*/", open)
                text = if (close >= 0) text.removeRange(open, close + 2) else {
                    inBlock = true
                    text.substring(0, open)
                }
            }
            text.substringBefore("//")
        }
    }

    /**
     * The methods a consumer declares, by name and line range, and whether `@Test` sits above each.
     *
     * A method is recognised only at class level and only when its signature ends the line with `{`,
     * which is the shape every consumer uses; a one-line helper body is left as an ordinary line, since
     * [resolveConstants] is what reads those and a lookup has never been written that way.
     */
    private fun declaredMethods(code: List<String>, original: List<String>): List<JavaMethod> {
        val signature = Regex("""\b(\w+)\s*\([^;]*\)\s*(?:throws\s+[\w.,\s]+)?\{\s*$""")
        val methods = ArrayList<JavaMethod>()
        var depth = 0
        var pendingTest = false
        var index = 0
        while (index < code.size) {
            val line = code[index]
            if (original[index].contains("@Test")) pendingTest = true
            val name = if (depth == 1) signature.find(line)?.groupValues?.get(1) else null
            if (name != null) {
                var balance = 0
                var end = index
                while (end < code.size) {
                    balance += code[end].count { it == '{' } - code[end].count { it == '}' }
                    if (balance == 0 && end > index) break
                    end++
                }
                methods.add(JavaMethod(name, index..minOf(end, code.size - 1), pendingTest))
                pendingTest = false
                index = end + 1
                continue
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            index++
        }
        return methods
    }

    /**
     * Every method a `@Test` method can reach, by name, closed transitively over textual calls.
     *
     * Transitive rather than one level deep: one level is what the consumers need today, and a rule
     * that stopped there would reject an honest two-step helper chain for no reason the reader could
     * defend. What it must never admit is a method NOTHING calls, and the closure does not.
     */
    private fun reachableFromTests(code: List<String>, methods: List<JavaMethod>): Set<String> {
        val reachable = methods.filter { it.isTest }.map { it.name }.toMutableSet()
        var growing = true
        while (growing) {
            growing = false
            for (caller in methods.filter { it.name in reachable }) {
                for (candidate in methods.filter { it.name !in reachable }) {
                    val called = Regex("""\b${Regex.escape(candidate.name)}\s*\(""")
                    if (caller.range.any { called.containsMatchIn(code[it]) }) {
                        reachable.add(candidate.name)
                        growing = true
                    }
                }
            }
        }
        return reachable
    }

    /** One method of a consumer: its name, the lines it spans, and whether it is a `@Test`. */
    private data class JavaMethod(val name: String, val range: IntRange, val isTest: Boolean) {
        fun holds(line: Int): Boolean = line in range
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
