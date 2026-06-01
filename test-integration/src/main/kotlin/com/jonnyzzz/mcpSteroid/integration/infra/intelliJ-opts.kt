package com.jonnyzzz.mcpSteroid.integration.infra

data class IntelliJContainerOpts(
    val dockerFileBase: String = "ide-agent",

    val consoleTitle: String,

    val project : IntelliJProject = IntelliJProject.TestProject,

    val layoutManager : LayoutManager = HorizontalLayoutManager(),

    val distribution: IdeDistribution = IdeDistribution.fromSystemProperties(),

    val aiMode: AiMode = AiMode.AI_MCP,
    /**
     * Explicit MCP connection mode for AI agents.
     *
     * When provided, takes precedence over [aiMode] for MCP connectivity.
     * Use [McpConnectionMode.None] to create a container where agents have NO MCP registered
     * (baseline / control group) while still producing real-time console output and log files.
     *
     * When null (default), the mode is derived from [aiMode].
     */
    val mcpConnectionMode: McpConnectionMode? = null,

    /**
     * When true, mounts the host Docker socket (`/var/run/docker.sock`) into the container
     * at the same path so Testcontainers-based tests can start sibling Docker containers.
     *
     * Requirements:
     * - The host Docker socket must exist at `/var/run/docker.sock`
     * - The `ide-base` Docker image must have `docker-ce-cli` installed (already done) and
     *   the `agent` user must be in the `docker` group (already done in the Dockerfile)
     *
     * Default: `false` (Docker socket not mounted — arena tests that use Testcontainers
     * will fail with "Could not find a valid Docker environment" unless this is enabled).
     */
    val mountDockerSocket: Boolean = false,

    /**
     * Default true keeps ordinary Docker tests immune to trust prompts. Tests that validate
     * project-trust behavior can set this false and rely on explicit trusted paths.
     */
    val disableProjectTrustChecks: Boolean = true,

    /**
     * Default true mirrors the historical test image setup that trusts every path. Tests that
     * need an actually untrusted secondary project can set this false.
     */
    val trustAllProjectPaths: Boolean = true,

    /**
     * When true (default), pre-write `options/jdk.table.xml` before the IDE starts so Gradle
     * auto-import resolves the project JDK at project-open and does not stall on
     * `Observation.awaitConfiguration`. Tests that need IntelliJ to build the JDK table from
     * scratch (e.g. the generator-fidelity test) set this false.
     */
    val preloadJdkTable: Boolean = true,

    /**
     * Hooks invoked just BEFORE the IDE process is launched (after all built-in startup
     * config files are written, including the pre-generated `jdk.table.xml`, and the project
     * files are deployed). Use these to adjust the IDE config dir / project files while no IDE is
     * running yet — e.g. inject a file under the `options` config dir, or `git init` the project.
     * Each hook receives a [BeforeIdeStartContext].
     */
    val beforeIdeStart: List<BeforeIdeStartContext.() -> Unit> = emptyList(),

    /**
     * Hooks invoked AFTER the container session is fully built (IDE up, MCP ready, window
     * positioned). Each hook receives the live [IntelliJContainer].
     */
    val afterIdeStart: List<IntelliJContainer.() -> Unit> = emptyList(),
)
