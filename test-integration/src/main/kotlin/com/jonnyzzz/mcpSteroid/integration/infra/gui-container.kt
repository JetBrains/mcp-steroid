package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver

class GuiContainer(
    val console: ConsoleDriver,
    val container: ContainerDriver,
    val windowsDriver: XcvbWindowDriver,
    val videoDriver: XcvbVideoDriver,
    val screenshotDriver: XcvbScreenshotDriver,
    val windowsLayout: WindowLayoutManager,
    val consoleDriver: XcvbConsoleDriver,
    val inputDriver: XcvbInputDriver,
    val xcvb: XcvbDriver
)

fun setupGuiContainerServices(lifetime: CloseableStack,
                              basicContainer: ContainerDriver,
                              layoutManager: LayoutManager,
                              containerMountedPath: String,
                              realConsoleTitle: String): GuiContainer {

    // Register the TC artifact post-process AFTER the container has been
    // started (so its cleanup is also registered — container teardown runs
    // BEFORE this callback in LIFO) but BEFORE the video driver is started
    // (so the video-finalize cleanup is registered LATER and therefore
    // runs AFTER this callback in LIFO). Resulting cleanup order:
    //
    //   1. (latest-registered callbacks — screenshot/rsync drivers, …)
    //   2. video ffmpeg stop + copy-out to /mcp-run-dir/video/recording.mp4
    //   3. <this post-process>  ← final mp4 is on the mount, container still alive
    //   4. container stop + remove
    //   5. publishRunDirArtifact(runDir)  ← sees <runDir>/publish/ tree
    //
    // The post-process is a no-op outside TeamCity; it gates on
    // `TEAMCITY_VERSION` internally so local dev keeps the raw runDir.
    lifetime.registerCleanupAction {
        TeamCityArtifactPostProcess.buildPublishTree(basicContainer, containerMountedPath)
    }

    val xcvb = XcvbDriver(
        lifetime,
        basicContainer,
        layoutManager
    )

    xcvb.startDisplayServer()
    val container = xcvb.withDisplay(basicContainer)

    val windowsDriver = XcvbWindowDriver(lifetime, container, xcvb.wholeScreenAreal())
    windowsDriver.startWindowManager()

    val videoDriver = XcvbVideoDriver(lifetime, container, windowsDriver, xcvb, "$containerMountedPath/video", realConsoleTitle)
    videoDriver.startVideoService()

    val screenshotDriver = XcvbScreenshotDriver(lifetime, container, "$containerMountedPath/screenshot")
    screenshotDriver.startScreenshotCapture()

    val windowsLayout = WindowLayoutManager(windowsDriver, layoutManager)

    val consoleDriver = XcvbConsoleDriver(lifetime, container, windowsDriver)
    val console = consoleDriver.createConsoleDriver(container, realConsoleTitle, windowsLayout.layoutStatusConsoleWindow())

    val inputDriver = XcvbInputDriver(container)
    XcvbSkillDriver(lifetime, container)

    return GuiContainer(
        console = console,
        xcvb = xcvb,
        container = container,
        windowsDriver = windowsDriver,
        videoDriver = videoDriver,
        screenshotDriver = screenshotDriver,
        windowsLayout = windowsLayout,
        consoleDriver = consoleDriver,
        inputDriver = inputDriver,
    )
}
