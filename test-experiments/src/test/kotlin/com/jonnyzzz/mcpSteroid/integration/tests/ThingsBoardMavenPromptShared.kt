/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

private val thingsBoardPrompt = RealWorldMavenProjectPrompt(
    projectName = "ThingsBoard",
    projectUrl = "https://github.com/thingsboard/thingsboard",
    description = "It is a large multi-module Apache Maven IoT platform. The root POM currently targets Java 25.",
    testSelection = "use the deterministic fast backend target `org.thingsboard.common.util.NumberUtilsTest#isNaN` in module `common/util`. Keep Maven goals scoped to `common/util`; do not run root package/test goals. If Maven cannot resolve sibling reactor artifacts, first install `common/data`, then install the `common` parent POM non-recursively, and retry the scoped `common/util` test with Maven dependency updates enabled.",
    moduleExample = "common/util",
    successMarker = "THINGSBOARD_MAVEN_TEST_RAN",
)

internal fun buildThingsBoardMavenPrompt(): String = buildRealWorldMavenProjectPrompt(thingsBoardPrompt)

internal fun assertThingsBoardMavenAgentSucceeded(combined: String) =
    assertRealWorldMavenAgentSucceeded(combined, thingsBoardPrompt.successMarker)
