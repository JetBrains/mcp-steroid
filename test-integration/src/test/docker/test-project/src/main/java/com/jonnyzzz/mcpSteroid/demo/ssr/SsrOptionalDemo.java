/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo.ssr;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Fixture for StructuralSearchPromptTest — Java `Optional.get()` audit with `exprtype` filter.
 *
 * Modelled after JavaStructuralSearchTest.testExprTypeWithObject:
 * https://github.com/JetBrains/intellij-community/blob/master/java/structuralsearch-java/testSrc/com/intellij/java/structuralsearch/JavaStructuralSearchTest.java
 *
 * The skill recipe in `mcp-steroid://skill/structural-search-use-cases` (case D3) and
 * the `:[exprtype(...)]` macro in `mcp-steroid://skill/structural-search-syntax` are
 * the load-bearing references. The expected SSR pattern is:
 *
 *     '_o:[exprtype( java\.util\.Optional<.*> )].get()
 *
 * Critical: a textual grep for `.get()` would match `OptionalInt.getAsInt()` chains
 * and `Map.get(key)` and many others. The point of SSR is the type filter — only
 * `java.util.Optional<T>.get()` callsites should match. There are exactly 4 below.
 */
public final class SsrOptionalDemo {

    public String optionalGet1(Optional<String> o) {
        return o.get(); // MATCH: Optional<String>.get()
    }

    public Integer optionalGet2(Optional<Integer> o) {
        if (o.isPresent()) {
            return o.get(); // MATCH: Optional<Integer>.get()
        }
        return null;
    }

    public String optionalGet3() {
        Optional<String> opt = Optional.of("hello");
        String value = opt.get(); // MATCH: Optional<String>.get()
        return value;
    }

    public String optionalGet4(Optional<? extends Number> o) {
        return String.valueOf(o.get()); // MATCH: Optional<? extends Number>.get()
    }

    /** False-positive bait: `OptionalInt.getAsInt()` is NOT `Optional.get()`. */
    public int optionalIntGetAsInt(OptionalInt o) {
        return o.getAsInt();
    }

    /** False-positive bait: `Map.get` looks textually similar; not Optional. */
    public String mapGet(java.util.Map<String, String> m, String key) {
        return m.get(key);
    }

    /** Non-callsite: a literal `.get` in a string. */
    public String getAsLiteral() {
        return "Some(\"hello\").get";
    }
}
