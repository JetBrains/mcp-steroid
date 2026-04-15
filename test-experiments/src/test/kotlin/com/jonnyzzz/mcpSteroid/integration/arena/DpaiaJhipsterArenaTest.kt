/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * Dedicated arena test for the JHipster scenario — **Claude Code only**.
 *
 * Each test method launches a **fresh Docker container** with IntelliJ IDEA.
 * Before the agent timer starts, the test runs a full prewarm:
 * 1. Maven import + JDK setup (via [waitForProjectReady])
 * 2. `./mvnw compile -DskipTests` (compiles Java + installs npm packages via frontend-maven-plugin)
 *
 * Only after the project is fully built does the agent timer start.
 *
 * **Usage:**
 * ```
 * ./gradlew :test-experiments:test --tests '*DpaiaJhipsterArenaTest*'
 * ```
 *
 * **Run a single mode:**
 * ```
 * --tests '*DpaiaJhipsterArenaTest.claude with mcp'
 * ```
 */
class DpaiaJhipsterArenaTest : DpaiaScenarioBaseTest() {
    override val instanceId = "dpaia__jhipster__sample__app-3"
}
