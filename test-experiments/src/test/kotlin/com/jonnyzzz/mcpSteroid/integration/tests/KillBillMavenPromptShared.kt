/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

private val killBillPrompt = RealWorldMavenProjectPrompt(
    projectName = "Kill Bill",
    projectUrl = "https://github.com/killbill/killbill",
    description = "It is a multi-module Apache Maven billing platform. Upstream CI uses Temurin 11.",
    testSelection = "use the deterministic fast TestNG target `org.killbill.billing.util.TestPluginProperties#testToMap` in module `util`. If Maven reports the sibling `api` artifact is missing, launch the same IntelliJ Maven run with reactor goals that include `-pl api,util` and `-Dsurefire.failIfNoSpecifiedTests=false`.",
    moduleExample = "util",
    successMarker = "KILLBILL_MAVEN_TEST_RAN",
)

internal fun buildKillBillMavenPrompt(): String = buildRealWorldMavenProjectPrompt(killBillPrompt)

internal fun assertKillBillMavenAgentSucceeded(combined: String) =
    assertRealWorldMavenAgentSucceeded(combined, killBillPrompt.successMarker)
