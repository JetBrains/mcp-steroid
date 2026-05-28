/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CodeButcher.wrapToKotlinClass].
 *
 * These tests verify import extraction behavior, including the fix for the bug
 * where import statements inside triple-quoted string literals were incorrectly
 * extracted as top-level Kotlin imports.
 */
class CodeButcherTest {

    private val butcher = CodeButcher()

    @Test
    fun `top-level import is extracted`() {
        val code = """
            import com.example.Foo
            println(Foo())
        """.trimIndent()
        val result = butcher.wrapToKotlinClass("TestClass", code)
        assertTrue(result.code.contains("import com.example.Foo"),
            "Top-level import should be in generated code")
    }

    @Test
    fun `import inside triple-quoted string is NOT extracted as top-level import`() {
        // Simulates an agent embedding Java source code as a Kotlin raw string.
        // The Java 'import' lines must NOT be extracted as Kotlin top-level imports.
        val code = """
            val javaSource = ${"\"\"\""}
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity
            public class Product { @Id private Long id; }
            ${"\"\"\""}
            println(javaSource)
        """.trimIndent()
        val result = butcher.wrapToKotlinClass("TestClass2", code)
        // The generated code should NOT have these as top-level imports
        val topLevelSection = result.code.substringBefore("class TestClass2")
        assertFalse(topLevelSection.contains("import jakarta.persistence.Entity"),
            "import jakarta.persistence.Entity should NOT appear as top-level import")
        assertFalse(topLevelSection.contains("import jakarta.persistence.Id"),
            "import jakarta.persistence.Id should NOT appear as top-level import")
        // The string content should still be present in the method body
        assertTrue(result.code.contains("import jakarta.persistence.Entity"),
            "The java source content should appear in the method body")
    }

    @Test
    fun `import after closing triple-quote is extracted`() {
        val code = """
            val javaSource = ${"\"\"\""}
            some content
            ${"\"\"\""}
            import com.example.Bar
            println(Bar())
        """.trimIndent()
        val result = butcher.wrapToKotlinClass("TestClass3", code)
        val topLevelSection = result.code.substringBefore("class TestClass3")
        assertTrue(topLevelSection.contains("import com.example.Bar"),
            "Import after closing triple-quote should be extracted")
    }

    @Test
    fun `single-line triple-quoted string with import on same line is handled`() {
        // The import keyword doesn't appear at line start here, so it shouldn't
        // be extracted anyway — but the triple-quote tracking should still work.
        val code = """
            val s = ${"\"\"\""}import foo${"\"\"\""}
            import com.example.Baz
            println(s)
        """.trimIndent()
        val result = butcher.wrapToKotlinClass("TestClass4", code)
        val topLevelSection = result.code.substringBefore("class TestClass4")
        assertTrue(topLevelSection.contains("import com.example.Baz"),
            "Import after single-line triple-quote string should be extracted")
    }
}
