# Empty test project

Minimal IntelliJ IDEA project fixture — just this README, no build system and no sources.

Use it for tests where the project content does not matter (dialog killer, infrastructure
smoke tests, etc.). Because it declares no build system and no JDK, `waitForProjectReady`
skips JDK setup and build-system import, so startup is fast and there is no Gradle/Maven
configuration to wait on.
