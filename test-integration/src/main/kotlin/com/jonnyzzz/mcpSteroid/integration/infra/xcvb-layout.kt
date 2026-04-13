/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

/**
 * Defines the display resolution and calculates target window rects
 * for the IDE and console layout.
 *
 * The layout manager is the single source of truth for display dimensions
 * and window zone positioning.
 */
interface LayoutManager {
    val displaySize : WindowRect

    /** Target rect for IDE project window(s) — left-aligned, occupies 2/3 of the screen. */
    fun layoutIntelliJWindow(workArea: WindowRect): WindowRect

    /** Target rect for the status/console window — right-aligned, occupies 1/3 of the screen. */
    fun layoutStatusConsoleWindow(workArea: WindowRect): WindowRect
}

/**
 * Default layout: IDE takes left 2/3 minus 2px gap, console takes right 1/3 minus 2px gap.
 * This leaves a 4px visual separator between the two zones.
 *
 * ```
 * ┌──────────────────────────────┬──┬───────────────┐
 * │  IDE (2/3 width - 2px)       │4 │ Console       │
 * │  aligned left                │px│ (1/3 w - 2px) │
 * │                              │  │ aligned right │
 * └──────────────────────────────┴──┴───────────────┘
 * ```
 */
class HorizontalLayoutManager(
    val displayWidth: Int = 3840,
    val displayHeight: Int = 2160,
) : LayoutManager {

    override val displaySize: WindowRect
        get() = WindowRect(0, 0, displayWidth, displayHeight)

    override fun layoutIntelliJWindow(workArea: WindowRect): WindowRect {
        return WindowRect(
            x = workArea.x,
            y = workArea.y,
            width = workArea.width * 2 / 3 - 2,
            height = windowHeight(workArea),
        )
    }

    override fun layoutStatusConsoleWindow(workArea: WindowRect): WindowRect {
        val consoleWidth = workArea.width / 3 - 2
        return WindowRect(
            x = workArea.x + workArea.width - consoleWidth,
            y = workArea.y,
            width = consoleWidth - 2,
            height = windowHeight(workArea),
        )
    }

    /**
     * Compute safe window height that fits within the physical display.
     *
     * With fluxbox configured to use no WM decorations ({NONE} in apps file),
     * xdotool windowsize sets the CLIENT height directly (no title bar overhead).
     * The work area height is already safe to use; we cap at displayHeight - 4
     * as a small safety margin for bottom pixel borders.
     */
    private fun windowHeight(workArea: WindowRect): Int =
        workArea.height.coerceAtMost(displayHeight - 4)
}

class WindowLayoutManager(
    private val driver: XcvbWindowDriver,
    private val layoutManager: LayoutManager,
) {

    /** Target rect for IDE project window(s) — left-aligned, occupies 2/3 of the screen. */
    fun layoutIntelliJWindow(): WindowRect = layoutManager.layoutIntelliJWindow(driver.getWorkArea())

    /** Target rect for the status/console window — right-aligned, occupies 1/3 of the screen. */
    fun layoutStatusConsoleWindow(): WindowRect = layoutManager.layoutStatusConsoleWindow(driver.getWorkArea())
}
