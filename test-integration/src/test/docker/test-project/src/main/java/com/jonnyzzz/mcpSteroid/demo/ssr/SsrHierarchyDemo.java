/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo.ssr;

/**
 * Fixture for StructuralSearchPromptTest — class-hierarchy search.
 *
 * Modelled after JavaStructuralSearchTest.testFindClassesWithinHierarchy and
 * testInterfaceImplementationsSearch:
 * https://github.com/JetBrains/intellij-community/blob/master/java/structuralsearch-java/testSrc/com/intellij/java/structuralsearch/JavaStructuralSearchTest.java
 *
 * Expected SSR pattern (apostrophe form, "within hierarchy" text-modifier `*`):
 *
 *     class '_C implements '_I:*Greeting {}
 *
 * The `*Greeting` arg modifier walks the type hierarchy, so direct implementors
 * (HelloGreeter, FormalGreeter) AND transitive implementors (LoudGreeter, which
 * extends FormalGreeter) BOTH match. Without the leading `*`, only direct
 * implementors would match.
 */
public final class SsrHierarchyDemo {

    public interface Greeting {
        String greet(String who);
    }

    /** Direct implementor — matches with or without `*`. */
    public static class HelloGreeter implements Greeting {
        @Override
        public String greet(String who) {
            return "Hello, " + who;
        }
    }

    /** Direct implementor — matches with or without `*`. */
    public static class FormalGreeter implements Greeting {
        @Override
        public String greet(String who) {
            return "Good day, " + who;
        }
    }

    /** Transitive implementor — matches ONLY with the `*Greeting` hierarchy modifier. */
    public static class LoudGreeter extends FormalGreeter {
        @Override
        public String greet(String who) {
            return "GOOD DAY, " + who.toUpperCase() + "!";
        }
    }

    /** Unrelated class — must NOT match. */
    public static class NotAGreeter {
        public String wave(String who) {
            return "👋 " + who;
        }
    }
}
