/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class BackendDescriptorJsonTest {

    @Test
    fun `descriptor round trips through backend json`(
        @TempDir tempDir: Path,
    ) {
        val descriptor = BackendDescriptor(
            id = "idea-community-2025.3.3",
            productKey = "idea-community",
            productCode = "IC",
            version = "2025.3.3",
            buildNumber = "IC-253.1",
            bundleDirName = "idea-IC-253.1",
            launcherPath = "bin/idea.sh",
            downloadedAt = "2026-05-14T21:00:00Z",
            sourceArchiveSha256 = "abc123",
        )
        val path = tempDir.resolve("backend.json")

        writeDescriptor(path, descriptor)
        val readBack = readDescriptorOrNull(path)

        assertEquals(descriptor, readBack)
    }

    @Test
    fun `unknown fields are tolerated for forward compatibility`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("backend.json")
        Files.writeString(
            path,
            """
            {
              "schemaVersion": 1,
              "id": "idea-community-2025.3.3",
              "productKey": "idea-community",
              "productCode": "IC",
              "version": "2025.3.3",
              "buildNumber": "IC-253.1",
              "bundleDirName": "idea-IC-253.1",
              "launcherPath": "bin/idea.sh",
              "downloadedAt": "2026-05-14T21:00:00Z",
              "sourceArchiveSha256": null,
              "futureField": { "hello": "world" }
            }
            """.trimIndent(),
        )

        val descriptor = readDescriptorOrNull(path)

        assertEquals("idea-community-2025.3.3", descriptor!!.id)
        assertEquals("bin/idea.sh", descriptor.launcherPath)
    }
}
