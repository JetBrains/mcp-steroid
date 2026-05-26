/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

data class IdeStartupConfigFile(
    val relativePath: String,
    val content: String,
)

private val eulaPreferenceKeys = listOf(
    "accepted_version",
    "privacy_policy_accepted_version",
    "eua_accepted_version",
    "euacommunity_accepted_version",
    "ij_euaeap_accepted_version",
)

/**
 * Startup-state files that must exist before a fresh IntelliJ config dir is
 * launched. They suppress onboarding and AI-promo first-run flows that would
 * otherwise block headless/managed runs behind modal dialogs or slow network
 * verdict calculations.
 */
fun ideStartupConfigFiles(): List<IdeStartupConfigFile> = listOf(
    IdeStartupConfigFile(
        relativePath = "options/other.xml",
        content = """<application>
  <component name="PropertyService"><![CDATA[{"keyToString":{"experimental.ui.on.first.startup":"true","experimental.ui.onboarding.proposed.version":"suppressed","RunOnceActivity.llm.onboarding.window.launcher.v7":"true"}}]]></component>
</application>
""",
    ),
    IdeStartupConfigFile(
        relativePath = "early-access-registry.txt",
        content = "switched.from.classic.to.islands\nfalse\n",
    ),
    IdeStartupConfigFile(
        relativePath = "options/AIOnboardingPromoWindowAdvisor.xml",
        content = """<application>
  <component name="AIOnboardingPromoWindowAdvisor">
    <option name="shouldShowNextTime" value="NO" />
    <option name="wasShown" value="true" />
    <option name="attempts" value="1" />
  </component>
</application>
""",
    ),
)

fun ideUserStartupConfigFiles(
    timestampMillis: Long = System.currentTimeMillis() - 1_000L,
): List<IdeStartupConfigFile> = listOf(
    IdeStartupConfigFile(
        relativePath = ".java/.userPrefs/jetbrains/prefs.xml",
        content = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">
<map MAP_XML_VERSION="1.0"/>
""",
    ),
    IdeStartupConfigFile(
        relativePath = ".java/.userPrefs/jetbrains/privacy_policy/prefs.xml",
        content = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">
<map MAP_XML_VERSION="1.0">
  <entry key="accepted_version" value="999.999"/>
  <entry key="privacy_policy_accepted_version" value="999.999"/>
  <entry key="eua_accepted_version" value="999.999"/>
  <entry key="euacommunity_accepted_version" value="999.999"/>
  <entry key="ij_euaeap_accepted_version" value="999.999"/>
</map>
""",
    ),
    IdeStartupConfigFile(
        // java.util.prefs.FileSystemPreferences encodes node names containing
        // underscores on Linux; this is the actual backing path for
        // Preferences.userRoot().node("jetbrains/privacy_policy").
        relativePath = """.java/.userPrefs/jetbrains/_!(!!cg"p!(}!}@"j!(k!|w"w!'8!b!"p!':!e@==/prefs.xml""",
        content = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">
<map MAP_XML_VERSION="1.0">
  <entry key="accepted_version" value="999.999"/>
  <entry key="privacy_policy_accepted_version" value="999.999"/>
  <entry key="eua_accepted_version" value="999.999"/>
  <entry key="euacommunity_accepted_version" value="999.999"/>
  <entry key="ij_euaeap_accepted_version" value="999.999"/>
</map>
""",
    ),
    IdeStartupConfigFile(
        relativePath = ".config/JetBrains/consentOptions/accepted",
        content = "rsch.send.usage.stat:1.1:0:$timestampMillis",
    ),
)

fun writeIdeStartupConfigFiles(configDir: Path) {
    for (file in ideStartupConfigFiles()) {
        val target = configDir.resolve(file.relativePath)
        Files.createDirectories(target.parent)
        Files.writeString(target, file.content)
    }
}

fun writeIdeUserStartupConfigFiles(userHome: Path) {
    // The .java/.userPrefs/... paths use java.util.prefs FileSystemPreferences
    // encoding (with " characters) that's illegal in Windows paths. Windows uses
    // WindowsPreferences (HKCU\Software\JavaSoft\Prefs) instead, so file-based
    // EULA stubs are unnecessary — the Preferences.userRoot() flush below covers it.
    if (resolveHostOs() != HostOs.WINDOWS) {
        for (file in ideUserStartupConfigFiles()) {
            val target = userHome.resolve(file.relativePath)
            Files.createDirectories(target.parent)
            Files.writeString(target, file.content)
        }
    }
    if (userHome.toAbsolutePath().normalize() == Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()) {
        val prefs = Preferences.userRoot().node("jetbrains/privacy_policy")
        for (key in eulaPreferenceKeys) {
            prefs.put(key, "999.999")
        }
        prefs.flush()
    }
}
