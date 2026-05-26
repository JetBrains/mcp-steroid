/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

private val broadleafCommercePrompt = RealWorldMavenProjectPrompt(
    projectName = "BroadleafCommerce",
    projectUrl = "https://github.com/BroadleafCommerce/BroadleafCommerce",
    description = "It is a multi-module Apache Maven commerce platform on the `develop-7.0.x` default branch. The root POM compiles for Java 17.",
    testSelection = "use the deterministic fast target `org.broadleafcommerce.common.extensibility.context.merge.handlers.SchemaLocationMergeTest#testReplacementRegex` in module `common`. Avoid browser, web, integration specs, and app startup tests.",
    moduleExample = "common",
    successMarker = "BROADLEAF_MAVEN_TEST_RAN",
)

internal fun buildBroadleafCommerceMavenPrompt(): String = buildRealWorldMavenProjectPrompt(broadleafCommercePrompt)

internal fun assertBroadleafCommerceMavenAgentSucceeded(combined: String) =
    assertRealWorldMavenAgentSucceeded(combined, broadleafCommercePrompt.successMarker)
