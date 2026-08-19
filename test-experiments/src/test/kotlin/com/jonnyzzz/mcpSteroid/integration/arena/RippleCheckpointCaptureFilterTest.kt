package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The capture build's selector, checked against the code it selects.
 *
 * A capture run is started by ONE TeamCity parameter — `ripple.checkpoint.capture.method` — which
 * `build.gradle.kts` maps to a test filter. Gradle silently ignores an unknown `-P`, and a filter that
 * matches nothing makes the build run whatever the configuration's own filter selects, so a rename on
 * either side of that mapping produces a green build that measured the wrong thing. That is not a
 * hypothetical: the pilot already lost $4.43 to parameters that looked applied and were not.
 *
 * Reflection, not string comparison: the point is that the class and the method the build script names
 * REALLY exist in the compiled test code.
 */
class RippleCheckpointCaptureFilterTest {
    @Test
    fun `every capture class the build script can select exists`() {
        val declared = declaredCaptureClasses()
        assertTrue(declared.isNotEmpty()) { "no capture classes are declared in $buildFile" }
        declared.forEach { (case, simpleName) ->
            val loaded = runCatching { Class.forName("$ARENA_PACKAGE.$simpleName") }.getOrNull()
            assertTrue(loaded != null) {
                "the build script maps case '$case' to $ARENA_PACKAGE.$simpleName, which does not exist"
            }
        }
    }

    @Test
    fun `every capture method the build script can select exists on every capture class`() {
        val methods = declaredCaptureMethods()
        assertTrue(methods.isNotEmpty()) { "no capture methods are declared in $buildFile" }
        declaredCaptureClasses().forEach { (_, simpleName) ->
            val loaded = Class.forName("$ARENA_PACKAGE.$simpleName")
            val present = loaded.methods.map { it.name }.toSet()
            methods.forEach { method ->
                assertTrue(method in present) {
                    "$simpleName has no $method(), so a capture build selecting it would match no test"
                }
            }
        }
    }

    /**
     * The pilot's case must be selectable, and it must be the DEFAULT.
     *
     * A capture started without an explicit case has to record the case the pilot measures. Defaulting
     * to the keycloak case — kept only as the already-measured second one — would produce a perfectly
     * green capture of a trajectory whose readiness curve is known to be a step function.
     */
    @Test
    fun `the pilot's case is the default of the capture selector`() {
        val default = buildFile.readText()
            .substringAfter("checkpointCaptureCaseProperty")
            .let { Regex("""\?:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        assertTrue(default == RippleCheckpointCase.RESOURCE_DIR) {
            "the capture selector defaults to '$default', not to the pilot's case " +
                "'${RippleCheckpointCase.RESOURCE_DIR}'"
        }
    }

    private fun declaredCaptureClasses(): Map<String, String> =
        Regex("""^\s*"([\w-]+)"\s+to\s+"(\w*CheckpointCaptureTest)"""", RegexOption.MULTILINE)
            .findAll(buildFile.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    // The empty missing-delimiter value matters: substringAfter returns the WHOLE text when the marker
    // is absent, and the regex below would then harvest unrelated string literals into a list that
    // looks like a valid declaration.
    private fun declaredCaptureMethods(): List<String> = buildFile.readText()
        .substringAfter("val checkpointCaptureMethods", "")
        .substringBefore(")")
        .let { Regex(""""(\w+)"""").findAll(it).map { match -> match.groupValues[1] }.toList() }

    private companion object {
        const val ARENA_PACKAGE = "com.jonnyzzz.mcpSteroid.integration.arena"

        /**
         * Located from the compiled test class, never from the working directory: this suite is run
         * from the repository root, from the module, and from TeamCity's checkout dir alike.
         */
        val buildFile: File = File(
            RippleCheckpointCaptureFilterTest::class.java.protectionDomain.codeSource.location.toURI()
        ).let { classesDir ->
            generateSequence(classesDir) { it.parentFile }
                .map { it.resolve("build.gradle.kts") }
                .first { it.isFile && it.parentFile.name == "test-experiments" }
        }
    }
}
