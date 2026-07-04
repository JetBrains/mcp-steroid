/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

enum class IdeProduct(
    val id: String,
    val dockerImageBase: String,
    val launcherExecutable: String,
    val displayName: String,
    val jetbrainsProductCode: String,
    /**
     * True iff the IDE bundles `com.intellij.java` — which makes
     * `com.intellij.openapi.projectRoots.JavaSdk` resolvable from
     * `steroid_execute_code`. `mcpListJdks` / `mcpAddJdk` / `mcpRegisterJdks`
     * all import `JavaSdk` and will fail to compile in IDEs that don't bundle
     * the Java plugin (PyCharm/GoLand/WebStorm/Rider).
     */
    val hasJavaSdk: Boolean,
) {
    IntelliJIdea(
        id = "idea",
        dockerImageBase = "ide-agent",
        launcherExecutable = "idea",
        displayName = "IntelliJ IDEA",
        jetbrainsProductCode = "IIU",
        hasJavaSdk = true,
    ),
    PyCharm(
        id = "pycharm",
        dockerImageBase = "pycharm-agent",
        launcherExecutable = "pycharm",
        displayName = "PyCharm",
        jetbrainsProductCode = "PCP",
        hasJavaSdk = false,
    ),
    GoLand(
        id = "goland",
        dockerImageBase = "goland-agent",
        launcherExecutable = "goland",
        displayName = "GoLand",
        jetbrainsProductCode = "GO",
        hasJavaSdk = false,
    ),
    WebStorm(
        id = "webstorm",
        dockerImageBase = "webstorm-agent",
        launcherExecutable = "webstorm",
        displayName = "WebStorm",
        jetbrainsProductCode = "WS",
        hasJavaSdk = false,
    ),
    Rider(
        id = "rider",
        dockerImageBase = "rider-agent",
        launcherExecutable = "rider",
        displayName = "Rider",
        jetbrainsProductCode = "RD",
        hasJavaSdk = false,
    ),
    CLion(
        id = "clion",
        dockerImageBase = "clion-agent",
        launcherExecutable = "clion",
        displayName = "CLion",
        jetbrainsProductCode = "CL",
        hasJavaSdk = false,
    ),

    /**
     * Android Studio (Google) — an IntelliJ-platform IDE with a different plugin/SDK surface. Reuses the
     * base `ide-agent` image (no dedicated Android Studio image is built yet), so support is exploratory:
     * the `studio` launcher and the bundled Android/Java plugins differ from IDEA, and the MCP Steroid
     * plugin is not guaranteed compatible. Used by experiments allowed to fail.
     */
    AndroidStudio(
        id = "android-studio",
        dockerImageBase = "ide-agent",
        launcherExecutable = "studio",
        displayName = "Android Studio",
        jetbrainsProductCode = "AI",
        hasJavaSdk = true,
    );

    companion object {
        fun fromSystemProperty(rawValue: String): IdeProduct = when (rawValue.trim().lowercase()) {
            "idea", "iiu", "intellij", "intellijidea", "intellijideaultimate" -> IntelliJIdea
            "pycharm", "pcp", "python" -> PyCharm
            "goland", "go" -> GoLand
            "webstorm", "ws" -> WebStorm
            "rider", "rd", "dotnet" -> Rider
            "clion", "cl", "cpp", "c++" -> CLion
            "androidstudio", "android-studio", "android", "as", "ai" -> AndroidStudio
            else -> error("Unsupported test.integration.ide.product='$rawValue'. Use one of: idea, pycharm, goland, webstorm, rider, clion, android-studio.")
        }
    }
}
