/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DevrigBeaconTest {
    @Test
    fun `constructor registers coroutine cleanup with lifetime`(@TempDir tempDir: Path) = runWithCloseableStack { lifetime ->
        val scope = DevrigBeacon(HomePaths(tempDir.resolve("home")), lifetime)
        scope.capture("test_event")
        //TODO: assert there is no errors logged
    }


}
