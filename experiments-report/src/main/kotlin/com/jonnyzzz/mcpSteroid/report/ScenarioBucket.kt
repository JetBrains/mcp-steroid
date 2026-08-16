package com.jonnyzzz.mcpSteroid.report

/**
 * Dashboard grouping for scenarios, in DISPLAY ORDER: the semantic-ripple series leads (it is the
 * only family run repeatedly on one revision, so its table is the one the whitepaper quotes), the
 * IDE-power experiments follow (semantic tasks where the IDE's PSI answer is exact while grep's is
 * incomplete), then the debugger demos, and the DPAIA fix-the-build arena sits under its own
 * dedicated heading instead of interleaving with everything else.
 */
enum class ScenarioBucket(val title: String) {
    RIPPLE("Semantic ripple — repeated-run series"),
    IDE_SEMANTIC("IDE semantic power — PSI vs grep"),
    DEBUGGER("Debugger"),
    DPAIA("DPAIA arena — fix the build"),
    OTHER("Other experiments"),
}

/**
 * Bucket for one scenario id, by its stable prefix (`ripple__…`, `keycloak__…`, `dpaia__…`, `debugger__…`).
 *
 * `ripple__` is matched FIRST and deliberately: the family's ids read `ripple__keycloak__…`, so with no
 * route of its own the series matches no branch at all — the `keycloak__` prefix is not at position 0 —
 * and every ripple run lands silently in [ScenarioBucket.OTHER].
 */
fun scenarioBucket(scenario: String): ScenarioBucket = when {
    scenario.startsWith("ripple__") -> ScenarioBucket.RIPPLE
    scenario.startsWith("keycloak__") || scenario.startsWith("youtrackdb__") -> ScenarioBucket.IDE_SEMANTIC
    scenario.startsWith("debugger__") -> ScenarioBucket.DEBUGGER
    scenario.startsWith("dpaia__") -> ScenarioBucket.DPAIA
    else -> ScenarioBucket.OTHER
}
