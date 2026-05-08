/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo.ssr;

/**
 * Fixture for StructuralSearchPromptTest — `System.out.println` audit.
 *
 * Modelled after the "Method calls" predefined Java SSR template
 * (`$Instance$.$MethodCall$($Parameter$)` from JavaPredefinedConfigurations):
 * https://github.com/JetBrains/intellij-community/blob/master/java/structuralsearch-java/src/com/intellij/structuralsearch/JavaPredefinedConfigurations.java
 *
 * Expected SSR pattern (apostrophe form):
 *
 *     System.out.'_m('_args*);
 *
 * Five matches below across `println` overloads + `print`/`printf`. A textual grep
 * for `System.out.println` would miss `System.out.print(…)` and `System.out.printf(…)`;
 * SSR's `'_m` macro placeholder picks them all up.
 */
public final class SsrSysoutDemo {

    public void demoMethods() {
        System.out.println();                          // MATCH: println()
        System.out.println("hello");                   // MATCH: println(String)
        System.out.println(42);                        // MATCH: println(int)
        System.out.print("no newline");                // MATCH: print(String)
        System.out.printf("formatted %s%n", "x");      // MATCH: printf(String, Object...)
    }

    /** False-positive bait: System.err calls are NOT System.out. */
    public void noiseSystemErr() {
        System.err.println("not a match");
        System.err.print("also not");
    }

    /** False-positive bait: instance variable named `out` shadowing System.out. */
    public void noiseShadowedOut(java.io.PrintStream out) {
        out.println("not System.out — local 'out'");
    }
}
