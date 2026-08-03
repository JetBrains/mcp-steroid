/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the on-disk contract of [androidStudioUserStartupConfigFiles] against the REAL parsers that
 * consume the two files inside Android Studio. The field names, value shapes, and paths below were
 * read from the upstream sources — a wrong field name is NOT an error there, it is silently skipped
 * and Google's usage-statistics ConsentDialog re-fires, modally blocking the EDT before project-open
 * (the exact failure of `AndroidStudioRuntimeCompatTest` this stub exists to prevent).
 *
 * Upstream ground truth (verify there before changing anything here):
 * - `com.android.tools.analytics.AnalyticsSettingsData.DataTypeAdapter.read` (analytics-library
 *   `shared`): recognizes `userId`, `hasOptedIn`, `debugDisablePublishing`, `lastOptinPromptVersion`
 *   (Gson JsonReader; unknown names -> `reader.skipValue()`).
 * - `AnalyticsSettings.isValid`: `settings.userId != null && (saltSkew == -1 || saltValue != null)` —
 *   a parsed file WITHOUT `userId` is discarded and replaced by fresh opted-out settings, which
 *   re-fires the dialog. `userId` is therefore mandatory.
 * - `AnalyticsSettings.hasUserBeenPromptedForOptin(major, minor)`: splits
 *   `lastOptinPromptVersion` on '.', requires exactly 2 int-parseable tokens.
 * - `com.intellij.ide.gdpr.ConfirmedConsent.fromString`: `id:version:accepted(1|0):acceptanceTime`,
 *   records separated by ';' (`ConsentOptions.loadConfirmedConsents`), and the LAST token goes
 *   straight into `Long.parseLong` — any trailing newline throws NumberFormatException and the
 *   record is silently dropped.
 */
class AndroidStudioConsentStubTest {

    private val files = androidStudioUserStartupConfigFiles(timestampMillis = 1_234_567_890_123L)

    private fun contentOf(relativePath: String): String {
        val file = files.singleOrNull { it.relativePath == relativePath }
        assertNotNull("expected a stub at $relativePath, got ${files.map { it.relativePath }}", file)
        return file!!.content
    }

    @Test
    fun `exactly the two google consent stubs are produced`() {
        assertEquals(
            listOf(
                ".android/analytics.settings",
                ".local/share/Google/consentOptions/accepted",
            ),
            files.map { it.relativePath },
        )
    }

    @Test
    fun `analytics settings json matches the AnalyticsSettingsData parser field names`() {
        val json = Json.parseToJsonElement(contentOf(".android/analytics.settings")).jsonObject

        // AnalyticsSettings.isValid requires a non-null userId or the whole file is discarded.
        val userId = json["userId"]?.jsonPrimitive?.contentOrNull
        assertNotNull("userId is mandatory (AnalyticsSettings.isValid)", userId)
        assertTrue("userId must be non-blank", userId!!.isNotBlank())

        // Stay opted OUT of Google metrics; publishing hard-disabled as belt-and-suspenders.
        assertEquals(false, json["hasOptedIn"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, json["debugDisablePublishing"]?.jsonPrimitive?.booleanOrNull)

        // hasUserBeenPromptedForOptin requires exactly `major.minor`, both Int-parseable.
        val lastOptinPromptVersion = json["lastOptinPromptVersion"]?.jsonPrimitive?.contentOrNull
        assertNotNull("lastOptinPromptVersion is what suppresses the ConsentDialog", lastOptinPromptVersion)
        val tokens = lastOptinPromptVersion!!.split('.')
        assertEquals("must be exactly major.minor", 2, tokens.size)
        assertNotNull("major must parse as Int", tokens[0].toIntOrNull())
        assertNotNull("minor must parse as Int", tokens[1].toIntOrNull())
    }

    @Test
    fun `confirmed consent line matches the ConfirmedConsent parser`() {
        val content = contentOf(".local/share/Google/consentOptions/accepted")

        // ConfirmedConsent.fromString feeds the last token into Long.parseLong: a trailing
        // newline (or any whitespace) silently drops the record and re-fires the dialog.
        assertEquals("no leading/trailing whitespace allowed", content.trim(), content)
        assertFalse("';' is the record separator, single record expected", content.contains(';'))

        val tokens = content.split(':')
        assertEquals("id:version:accepted:acceptanceTime", 4, tokens.size)
        assertEquals("rsch.send.usage.stat", tokens[0])
        assertTrue("version must be major.minor", tokens[1].matches(Regex("""\d+\.\d+""")))
        assertEquals("0 = declined (opted out)", "0", tokens[2])
        assertEquals(1_234_567_890_123L, tokens[3].toLong())
    }

    @Test
    fun `contents are single-quote free for the in-container shell writer`() {
        // test-integration writes these files inside the IDE container as the `agent` user via a
        // bash single-quoted printf (the docker-cp path would leave them root-owned, and
        // AnalyticsSettings opens its file with RandomAccessFile(file, "rw") — EACCES there falls
        // back to fresh opted-out settings and the dialog re-fires). Keep the contract enforced.
        for (file in files) {
            assertFalse("'${file.relativePath}' content must not contain single quotes", file.content.contains('\''))
            assertFalse("'${file.relativePath}' path must not contain single quotes", file.relativePath.contains('\''))
        }
    }
}
