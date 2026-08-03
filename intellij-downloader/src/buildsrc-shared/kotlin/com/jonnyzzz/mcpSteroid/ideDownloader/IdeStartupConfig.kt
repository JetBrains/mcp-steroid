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

/**
 * Google's Android Studio consent state, pre-seeded (relative to the IDE user's home) so the
 * usage-statistics dialog (`com.android.tools.idea.stats.ConsentDialog`) never opens. That dialog is
 * MODAL and fires on the EDT before the project frame appears — in a fresh container it permanently
 * blocks project-open, and `waitForMcpReady` then polls `list_projects` to its full deadline
 * (`AndroidStudioRuntimeCompatTest`, issue #412).
 *
 * `ConsentDialog.showConsentDialogIfNeeded` early-returns without showing anything when the user has
 * already answered the JetBrains-platform consent (`consentsToShow.second == false` — our
 * `-Djb.consents.confirmation.enabled=false` vmoption already forces that) AND
 * `AnalyticsSettings.hasUserBeenPromptedForOptin(major, minor)` is true. The latter reads
 * `~/.android/analytics.settings` (analytics-library `shared`, `AnalyticsPaths`: `ANDROID_PREFS_ROOT`
 * -> `ANDROID_SDK_HOME` -> `${user.home}/.android`); absent file means "never prompted" and the
 * dialog fires. So:
 *
 * 1. `.android/analytics.settings` — Gson-parsed by `AnalyticsSettingsData.DataTypeAdapter.read`;
 *    recognized field names are exactly `userId`, `hasOptedIn`, `debugDisablePublishing`,
 *    `lastOptinPromptVersion` (misspelled names are silently `skipValue()`d). `userId` is MANDATORY:
 *    `AnalyticsSettings.isValid` discards a parsed file whose `userId` is null and replaces it with
 *    fresh never-prompted settings. The value is a fixed synthetic UUID — nothing is ever published
 *    (`hasOptedIn=false` + `debugDisablePublishing=true`). `lastOptinPromptVersion` is compared by
 *    `hasUserBeenPromptedForOptin` as `currentMajor == lastMajor ? currentMinor <= lastMinor
 *    : currentMajor <= lastMajor` — the Android Studio lane resolves the UNPINNED current stable
 *    (`IdeDistribution.Latest`), so a real version like "2026.1" would silently re-arm the dialog on
 *    the next Studio release; the far-future `9999.9999` keeps the check true for any real version.
 *
 * 2. `.local/share/Google/consentOptions/accepted` — the JetBrains-platform confirmed-consents file
 *    under Android Studio's vendor dir. Path per `ConsentOptions.getConfirmedConsentsFile()` =
 *    `PathManager.getCommonDataPath()/consentOptions/accepted`, where the Linux common data path is
 *    `${XDG_DATA_HOME:-~/.local/share}/<vendor>` and Android Studio's vendor is `Google` (NOT
 *    `.config/...` — that is the config-dir root, not the common-data one). Record format per
 *    `ConfirmedConsent.fromString`: `id:version:accepted(1|0):acceptanceTime`, `;`-separated. The
 *    acceptanceTime token goes straight into `Long.parseLong`, so the content MUST NOT end with a
 *    newline — a trailing `\n` silently drops the record. `0` = declined: consent recorded as
 *    answered while staying opted out.
 *
 * Deliberately NOT part of [ideUserStartupConfigFiles]: devrig's ManagedBackend writes that list into
 * the REAL user home on developer machines, and overwriting a real `~/.android/analytics.settings`
 * would clobber the developer's actual Android Studio analytics identity/opt-in choice. Only the
 * throwaway test containers (test-integration `intelliJ.kt`) consume this list.
 */
fun androidStudioUserStartupConfigFiles(
    timestampMillis: Long = System.currentTimeMillis() - 1_000L,
): List<IdeStartupConfigFile> = listOf(
    IdeStartupConfigFile(
        relativePath = ".android/analytics.settings",
        content = """{"userId":"00000000-0000-0000-0000-000000000000","hasOptedIn":false,"debugDisablePublishing":true,"lastOptinPromptVersion":"9999.9999"}""",
    ),
    IdeStartupConfigFile(
        relativePath = ".local/share/Google/consentOptions/accepted",
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
