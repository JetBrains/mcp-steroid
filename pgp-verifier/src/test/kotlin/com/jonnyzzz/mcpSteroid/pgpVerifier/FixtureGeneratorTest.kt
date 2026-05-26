package com.jonnyzzz.mcpSteroid.pgpVerifier

import org.junit.jupiter.api.Test
import java.nio.file.Paths

class FixtureGeneratorTest {
    @Test
    fun regenerateFixtures() {
        if (System.getenv("PGP_VERIFIER_REGENERATE_FIXTURES") != "true") return

        FixtureGenerator.regenerate(Paths.get("src/test/resources/fixtures"))
    }
}
