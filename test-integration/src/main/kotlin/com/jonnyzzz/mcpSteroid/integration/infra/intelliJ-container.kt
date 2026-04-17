/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.commitContainerToImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File

/**
 * Controls how AI agents connect to MCP Steroid inside the test container.
 *
 * [IntelliJContainer.aiAgents] ([AiAgentDriver]) is always created regardless of mode.
 * This enum only determines which MCP transport (if any) is registered with each agent.
 *
 * Pass as [IntelliJContainer.create]'s `aiMode` parameter.
 */
enum class AiMode {
    /**
     * Agents are available but MCP Steroid is NOT registered with them.
     * Use for pure IDE/infrastructure tests that don't need MCP Steroid tools.
     */
    NONE,

    /**
     * Agents connect to MCP Steroid via HTTP (default).
     * Each agent has [AiAgentSession.registerHttpMcp] called with the guest-side URL.
     */
    AI_MCP,

    /**
     * Agents connect to MCP Steroid via an NPX stdio proxy.
     * [NpxSteroidDriver] is deployed before agents are initialized; each agent
     * has [AiAgentSession.registerNpxMcp] called with the resulting command.
     */
    AI_NPX,
}

/**
 * Manages a Docker container running IntelliJ IDEA with MCP Steroid plugin.
 * Assembles the Docker build context from separate artifacts and starts a named container.
 *
 * The container is NOT removed after the test — it stays around for debugging.
 * It IS removed before the next test run (by name).
 *
 * All IDE directories, video, and screenshots are mounted to a timestamped
 * run directory under testOutputDir for easy inspection and debugging.
 */
class IntelliJContainer(
    val lifetime: CloseableStack,
    val runDirInContainer: File,
    val scope: ContainerDriver,

    val intellijDriver: IntelliJDriver,

    val input: XcvbInputDriver,
    private val intellij: RunningContainerProcess,

    val console: ConsoleDriver,
    val mcpSteroid: McpSteroidDriver,

    /**
     * AI agent driver — always present.
     * Whether agents have MCP Steroid registered depends on the [AiMode] used at creation.
     */
    val aiAgents: AiAgentDriver,

    val windows: XcvbWindowDriver,
    private val windowLayout: WindowLayoutManager,

    /**
     * Relative path (from project root) of the file to open when the IDE starts.
     * When null, the default README.md / first source file fallback is used.
     */
    private val openFileOnStart: String? = null,
) {
    val pid by intellij::pid

    private fun latestScreenshotPath(): String? =
        File(runDirInContainer, "screenshot")
            .listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath

    private fun problemDetailsWithScreenshot(baseDetails: String): String {
        val screenshot = latestScreenshotPath() ?: "<none>"
        return "$baseDetails; latestScreenshot=$screenshot"
    }

    /**
     * Wait for the IDE project to finish import and indexing.
     * Polls via MCP execute_code until DumbService reports smart mode.
     * Writes progress to the console.
     *
     * When a modal dialog is detected (e.g. NewUI Onboarding in IntelliJ 2025.3.3+),
     * actively kills it via steroid_execute_code so Gradle import can proceed.
     */
    /**
     * Wait for the project to be fully ready for agent work.
     *
     * Ordered steps:
     * 1. Wait for IDE window (projectInitialized=true, indexingInProgress=false)
     * 2. Reposition IDE window
     * 3. Register JDKs via IntelliJ API (earliest possible — before any import)
     * 4. Set project SDK (parameter-driven, skip for Rider/.NET)
     * 5. Trigger build tool import (Maven/Gradle/NONE)
     * 6. Wait for import + indexing to complete
     * 7. Install IDE plugins (after import so dependency detection works)
     * 8. Compile project (testClasses/test-compile) — optional
     * 9. Open file + show tool windows
     *
     * @param timeoutMillis Max time for initial IDE window wait
     * @param projectJdkVersion JDK version to set as project SDK ("21", "17", etc.), null = skip
     * @param buildSystem Build system for import/compile. NONE = IntelliJ auto-detect only
     * @param compileProject Whether to run compilation (testClasses/test-compile) before returning
     */
    fun waitForProjectReady(
        timeoutMillis: Long = System.getProperty("test.integration.project.ready.timeout.ms")?.toLongOrNull() ?: 600_000L,
        pollIntervalMillis: Long = 1_000L,
        requireIndexingComplete: Boolean = true,
        performPostSetup: Boolean = true,
        projectJdkVersion: String? = "21",
        buildSystem: BuildSystem = BuildSystem.NONE,
        compileProject: Boolean = false,
    ) : IntelliJContainer {
        // Step 1: Wait for IDE window
        val waitLabel = if (requireIndexingComplete) "project import and indexing" else "project initialization"
        console.writeStep(1, "Waiting for $waitLabel...")
        val guestProjectDir = intellijDriver.getGuestProjectDir()
        waitForIdeWindow(guestProjectDir, timeoutMillis, pollIntervalMillis, requireIndexingComplete, waitLabel)

        if (!performPostSetup) return this

        // Step 2: Reposition IDE window
        console.writeStep(2, "Applying IDE window layout...")
        repositionIdeWindow()
        console.writeSuccess("Window layout applied")

        // Step 3: Register JDKs (earliest — before any import)
        if (projectJdkVersion != null) {
            console.writeStep(3, "Registering JDKs via IntelliJ API...")
            mcpSteroid.mcpRegisterJdks(guestProjectDir)
            console.writeSuccess("JDK registration complete")

            // Step 4: Set project SDK
            console.writeStep(4, "Setting project SDK to JDK $projectJdkVersion...")
            mcpSteroid.mcpSetProjectSdk(guestProjectDir, projectJdkVersion)
            console.writeSuccess("Project SDK set to $projectJdkVersion")
        } else {
            console.writeStep(3, "Skipping JDK setup (projectJdkVersion=null)")
        }

        // Step 5+6: Trigger import and wait for completion
        console.writeStep(5, "Triggering $buildSystem import and waiting...")
        mcpSteroid.mcpTriggerImportAndWait(guestProjectDir, buildSystem)
        console.writeSuccess("Import + indexing complete")

        // Step 6b: Resolve unknown SDKs (prevents "Resolving SDKs..." false positive during build)
        console.writeStep(6, "Resolving unknown SDKs...")
        mcpSteroid.mcpResolveUnknownSdks(guestProjectDir)
        console.writeSuccess("SDK resolution complete")

        // Step 7: Install IDE plugins
        console.writeStep(7, "Installing required IDE plugins...")
        mcpSteroid.mcpInstallRequiredPlugins(guestProjectDir)
        console.writeSuccess("Plugin installation complete")

        // Step 8: Compile project (optional)
        if (compileProject) {
            console.writeStep(8, "Compiling project ($buildSystem)...")
            mcpSteroid.mcpCompileProject(guestProjectDir, buildSystem)
            console.writeSuccess("Compilation complete")
        }

        // Step 9: Open file + show tool windows
        console.writeStep(9, "Opening project file and build tool window...")
        mcpSteroid.mcpOpenFileAndBuildToolWindow(guestProjectDir, openFileOnStart)
        console.writeSuccess("Project UX ready")

        return this
    }

    /**
     * Poll for IDE window readiness (extracted from the old waitForProjectReady).
     */
    private fun waitForIdeWindow(
        guestProjectDir: String,
        timeoutMillis: Long,
        pollIntervalMillis: Long,
        requireIndexingComplete: Boolean,
        waitLabel: String,
    ) {
        var lastDialogKillMs = 0L
        val startedAt = System.currentTimeMillis()
        var lastStatus = "no project windows found"
        var projectReady = false

        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val windows = try {
                mcpSteroid.mcpListWindows(timeoutSeconds = 120)
            } catch (e: Exception) {
                lastStatus = "mcpListWindows failed: ${e.message}"
                Thread.sleep(pollIntervalMillis)
                continue
            }
            val projectWindows = windows.filter { it.projectPath == guestProjectDir || it.projectName != null }

            if (projectWindows.isEmpty()) {
                lastStatus = "no project windows found"
                Thread.sleep(pollIntervalMillis)
                continue
            }

            val modalDialogPresent = projectWindows.any { it.modalDialogShowing }
            if (modalDialogPresent) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastDialogKillMs > 5_000) {
                    lastDialogKillMs = nowMs
                    console.writeError(
                        "Blocking modal dialog detected while waiting for $waitLabel. " +
                                problemDetailsWithScreenshot("projectWindows=${projectWindows.size}")
                    )
                    mcpSteroid.killStartupDialogs(guestProjectDir)
                }
                lastStatus = "modal dialog present"
                Thread.sleep(pollIntervalMillis)
                continue
            }

            val readyWindow = projectWindows.any { window ->
                val initialized = window.projectInitialized == true
                val indexingDone = window.indexingInProgress == false
                initialized && (!requireIndexingComplete || indexingDone)
            }
            if (readyWindow) {
                console.writeSuccess(
                    if (requireIndexingComplete) "Project import and indexing complete"
                    else "Project initialized"
                )
                projectReady = true
                break
            }

            val initialized = projectWindows.any { it.projectInitialized == true }
            val indexing = projectWindows.any { it.indexingInProgress == true }
            lastStatus = "projectInitialized=$initialized, indexingInProgress=$indexing, windows=${projectWindows.size}"
            Thread.sleep(pollIntervalMillis)
        }

        val elapsed = System.currentTimeMillis() - startedAt
        require(projectReady) {
            "Failed waiting for $waitLabel after ${elapsed}ms. " +
                    problemDetailsWithScreenshot("Last status: $lastStatus")
        }
    }

    /**
     * Wait for a project restored from a warm snapshot.
     *
     * Unlike [waitForProjectReady], this method intentionally does not run plugin installation,
     * JDK setup, or additional import/indexing flows. It fails if indexing restarts.
     */
    fun waitForSnapshotReadyWithoutIndexing(
        timeoutMillis: Long = 180_000L,
        pollIntervalMillis: Long = 1_000L,
    ): IntelliJContainer {
        console.writeStep(0, "Waiting for warm snapshot startup (indexing must stay off)...")
        val guestProjectDir = intellijDriver.getGuestProjectDir()
        val startedAt = System.currentTimeMillis()
        var lastDialogKillMs = 0L
        var lastPollError: String? = null

        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val windows = try {
                mcpSteroid.mcpListWindows(timeoutSeconds = 120)
            } catch (e: Exception) {
                lastPollError = "mcpListWindows failed: ${e.message}"
                Thread.sleep(pollIntervalMillis)
                continue
            }
            val projectWindows = windows.filter { it.projectPath == guestProjectDir || it.projectName != null }

            if (projectWindows.isNotEmpty()) {
                if (projectWindows.any { it.modalDialogShowing }) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastDialogKillMs > 5_000) {
                        lastDialogKillMs = nowMs
                        console.writeError(
                            "Blocking modal dialog detected during warm snapshot startup. " +
                                    problemDetailsWithScreenshot("projectWindows=${projectWindows.size}")
                        )
                        mcpSteroid.killStartupDialogs(guestProjectDir)
                    }
                    Thread.sleep(pollIntervalMillis)
                    continue
                }

                if (projectWindows.any { it.indexingInProgress == true }) {
                    error(
                        "Warm snapshot startup triggered indexing for project at $guestProjectDir. " +
                                problemDetailsWithScreenshot("indexingInProgress=true")
                    )
                }

                if (projectWindows.any { it.projectInitialized == true && it.indexingInProgress == false }) {
                    console.writeSuccess("Warm snapshot startup complete (no indexing detected)")
                    return this
                }
            }

            Thread.sleep(pollIntervalMillis)
        }

        val details = lastPollError?.let { " Last poll error: $it" } ?: ""
        error(
            "Timed out waiting for warm snapshot startup without indexing at $guestProjectDir.$details " +
                    problemDetailsWithScreenshot("snapshotWaitTimeout=true")
        )
    }

    /**
     * Wait until indexing/import completes, pre-build the project (Bazel + IntelliJ),
     * and commit a Docker snapshot image.
     *
     * The committed image is a full Docker container filesystem snapshot.
     * (Docker mounted volumes are preserved externally and are not embedded in committed layers.)
     */
    fun createIndexedSnapshot(imageTag: String): ImageDriver {
        val waitTimeoutMillis = System.getProperty("test.integration.snapshot.project.ready.timeout.ms")
            ?.toLongOrNull()
            ?: 5_400_000L
        val waitPollIntervalMillis = System.getProperty("test.integration.snapshot.project.ready.poll.ms")
            ?.toLongOrNull()
            ?: 5_000L
        waitForProjectReady(
            timeoutMillis = waitTimeoutMillis,
            pollIntervalMillis = waitPollIntervalMillis,
            requireIndexingComplete = false,
            performPostSetup = false,
        )
        val projectDir = intellijDriver.getGuestProjectDir()
        val systemDir = intellijDriver.getGuestSystemDir()
        val configDir = intellijDriver.getGuestConfigDir()
        val pluginsDir = intellijDriver.getGuestPluginsDir()

        runSnapshotPrebuild(projectDir)
        waitForProjectReady(
            timeoutMillis = waitTimeoutMillis,
            pollIntervalMillis = waitPollIntervalMillis,
            requireIndexingComplete = true,
            performPostSetup = false,
        )

        scope.startProcessInContainer {
            this
                .args("test", "-d", "$projectDir/.git")
                .timeoutSeconds(10)
                .description("Verify IntelliJ checkout exists at $projectDir")
                .quietly()
        }.assertExitCode(0) {
            "IntelliJ checkout is missing before snapshot: $projectDir"
        }

        scope.startProcessInContainer {
            this
                .args("test", "-d", systemDir)
                .timeoutSeconds(10)
                .description("Verify IntelliJ system directory exists at $systemDir")
                .quietly()
        }.assertExitCode(0) {
            "IntelliJ system directory not found before snapshot: $systemDir"
        }

        scope.startProcessInContainer {
            this
                .args("test", "-d", configDir)
                .timeoutSeconds(10)
                .description("Verify IntelliJ config directory exists at $configDir")
                .quietly()
        }.assertExitCode(0) {
            "IntelliJ config directory not found before snapshot: $configDir"
        }

        scope.startProcessInContainer {
            this
                .args("test", "-d", pluginsDir)
                .timeoutSeconds(10)
                .description("Verify IntelliJ plugins directory exists at $pluginsDir")
                .quietly()
        }.assertExitCode(0) {
            "IntelliJ plugins directory not found before snapshot: $pluginsDir"
        }

        // Keep snapshots lean and deterministic even if older setup paths were used.
        scope.startProcessInContainer {
            this
                .args("rm", "-rf", "/tmp/intellij-master-unpack", "/tmp/ultimate-git-clone-linux.zip")
                .timeoutSeconds(20)
                .description("Cleanup temporary IntelliJ ZIP/unpack artifacts before snapshot")
                .quietly()
        }.assertExitCode(0) {
            "Failed to cleanup temporary IntelliJ ZIP/unpack artifacts before snapshot"
        }

        console.writeStep(0, "Creating Docker snapshot: $imageTag ...")
        val snapshot = scope.commitContainerToImage(imageTag)
        console.writeSuccess(
            "Docker snapshot created: ${snapshot.imageIdToLog} " +
                    "(full container filesystem committed; mounted ide-config/ide-log remain on host volume)"
        )
        return snapshot
    }

    private fun runSnapshotPrebuild(projectDir: String) {
        runBazelBuildForSnapshot(projectDir)
        runIntelliJBuildForSnapshot(projectDir)
    }

    private fun runBazelBuildForSnapshot(projectDir: String) {
        console.writeStep(0, "Running IntelliJ Bazel build before snapshot...")
        val bazelBuildScript = """
            set -euo pipefail
            projectDir="$projectDir"
            bazelWrapper="${'$'}projectDir/bazel.cmd"
            bazelHostJvmXmx="6g"
            bazelPrimaryTarget="//:idea-ultimate-main"
            authorizerLastRunFile="${'$'}projectDir/out/.private.packages.auth.last.run.txt"
            authorizerLastMtimeFile="${'$'}projectDir/out/.private.packages.auth.source.mtime.txt"
            packagesHealthcheckUrls="
            https://packages.jetbrains.team/maven/p/ij/intellij-private-dependencies/
            https://packages.jetbrains.team/maven/p/ij/code-with-me-lobby-server/
            "
            issuedCredsFile="/tmp/intellij-packages-creds.json"
            jbTokenFile="${'$'}HOME/.jb/tokens/jetbrains.team.json"
            netrcFile="${'$'}HOME/.netrc"

            read_netrc_field_for_machine() {
              machineName="${'$'}1"
              field="${'$'}2"
              awk -v machineName="${'$'}machineName" -v field="${'$'}field" '
                BEGIN { in_host = 0 }
                ${'$'}1 == "machine" {
                  if (${ '$'}2 == machineName) {
                    in_host = 1
                  } else if (in_host) {
                    exit
                  } else {
                    in_host = 0
                  }
                }
                in_host {
                  for (i = 1; i <= NF; i++) {
                    if (${ '$'}i == field && i + 1 <= NF) {
                      print ${ '$'}(i + 1)
                      exit
                    }
                  }
                }
              ' "${'$'}HOME/.netrc"
            }

            test_packages_creds_for_url() {
              username="${'$'}1"
              password="${'$'}2"
              healthcheckUrl="${'$'}3"
              attempts=3
              attempt=1
              while [ "${'$'}attempt" -le "${'$'}attempts" ]; do
                status="$(curl -sS -o /dev/null -w "%{http_code}" -I -u "${'$'}username:${'$'}password" "${'$'}healthcheckUrl" || true)"
                if [ "${'$'}status" = "200" ]; then
                  return 0
                fi
                echo "[SNAPSHOT-PREBUILD] Credential check HTTP ${'$'}status for ${'$'}healthcheckUrl (attempt ${'$'}attempt/${'$'}attempts)" >&2
                if [ "${'$'}attempt" -lt "${'$'}attempts" ]; then
                  sleep "${'$'}attempt"
                fi
                attempt=$((attempt + 1))
              done
              return 1
            }

            test_packages_creds() {
              username="${'$'}1"
              password="${'$'}2"
              for healthcheckUrl in ${'$'}packagesHealthcheckUrls; do
                if ! test_packages_creds_for_url "${'$'}username" "${'$'}password" "${'$'}healthcheckUrl"; then
                  echo "[SNAPSHOT-PREBUILD] Credential check failed for ${'$'}healthcheckUrl" >&2
                  return 1
                fi
              done
              return 0
            }

            upsert_netrc_machine() {
              machineName="${'$'}1"
              machineLogin="${'$'}2"
              machinePassword="${'$'}3"
              tmpNetrc="$(mktemp)"
              {
                printf "machine %s login %s password %s\n" "${'$'}machineName" "${'$'}machineLogin" "${'$'}machinePassword"
                if [ -f "${'$'}netrcFile" ]; then
                  awk -v machineName="${'$'}machineName" '
                    BEGIN { skip = 0 }
                    ${'$'}1 == "machine" {
                      if (${ '$'}2 == machineName) {
                        skip = 1
                        next
                      }
                      skip = 0
                    }
                    !skip { print }
                  ' "${'$'}netrcFile"
                fi
              } > "${'$'}tmpNetrc"
              chmod 600 "${'$'}tmpNetrc"
              mv "${'$'}tmpNetrc" "${'$'}netrcFile"
            }

            issue_packages_credentials_from_jb_oauth_cache() {
              command -v node >/dev/null 2>&1 || {
                echo "[SNAPSHOT-PREBUILD] node is required to use ~/.jb/tokens/jetbrains.team.json" >&2
                return 1
              }
              command -v jq >/dev/null 2>&1 || {
                echo "[SNAPSHOT-PREBUILD] jq is required to parse rotated credential payload" >&2
                return 1
              }
              [ -f "${'$'}jbTokenFile" ] || {
                echo "[SNAPSHOT-PREBUILD] Missing jb OAuth token cache: ${'$'}jbTokenFile" >&2
                return 1
              }

              export JB_TEAM_TOKEN_FILE="${'$'}jbTokenFile"
              node <<'NODE' > "${'$'}issuedCredsFile"
const fs = require('fs');
const crypto = require('crypto');
const https = require('https');
const querystring = require('querystring');

const CLIENT_ID = '40b9a25a-06e8-4d92-a3dd-f87b0bd05fb6';
const TEAM_HOST = 'code.jetbrains.team';
const TOKEN_FILE = process.env.JB_TEAM_TOKEN_FILE;
const PERMANENT_SCOPE = [
  'project:3fodM13c2SEy:PackageRepository.Read',
  'project:1xLusQ2GsCxo:PackageRepository.Read',
  'project:1Tg5UJ1kq836:PackageRepository.Read',
  'project:4LuZvO4ENXaS:PackageRepository.Read',
].join(' ');

function request(method, host, path, headers = {}, body = null) {
  return new Promise((resolve, reject) => {
    const req = https.request({ method, hostname: host, path, headers }, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => resolve({ status: res.statusCode || 0, body: data }));
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

function decryptTokenFile(filePath) {
  const base64 = fs.readFileSync(filePath, 'utf8').trim();
  const combined = Buffer.from(base64, 'base64');
  const iv = combined.subarray(0, 12);
  const encrypted = combined.subarray(12);
  const authTag = encrypted.subarray(encrypted.length - 16);
  const cipherText = encrypted.subarray(0, encrypted.length - 16);
  const key = crypto.pbkdf2Sync('IntelliJIDEARulezzz!', 'jb-cli-salt-2026', 65536, 32, 'sha256');
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(authTag);
  const plaintext = Buffer.concat([decipher.update(cipherText), decipher.final()]).toString('utf8');
  return JSON.parse(plaintext);
}

async function refreshAccessToken(refreshToken) {
  const body = querystring.stringify({
    grant_type: 'refresh_token',
    refresh_token: refreshToken,
    client_id: CLIENT_ID,
  });
  const response = await request('POST', TEAM_HOST, '/oauth/token', {
    'Content-Type': 'application/x-www-form-urlencoded',
    'Content-Length': Buffer.byteLength(body),
  }, body);
  if (response.status !== 200) {
    throw new Error('OAuth refresh failed: HTTP ' + response.status);
  }
  const json = JSON.parse(response.body);
  if (!json.access_token) {
    throw new Error('OAuth refresh response does not contain access_token');
  }
  return json.access_token;
}

async function run() {
  if (!TOKEN_FILE) {
    throw new Error('JB_TEAM_TOKEN_FILE is not set');
  }

  const token = decryptTokenFile(TOKEN_FILE);
  let accessToken = token.accessToken;
  const expiresAt = typeof token.expiresAt === 'number' ? token.expiresAt : 0;
  if (!accessToken || Date.now() + 60_000 >= expiresAt) {
    if (!token.refreshToken) {
      throw new Error('Stored jb token has no refreshToken');
    }
    accessToken = await refreshAccessToken(token.refreshToken);
  }

  const meResponse = await request('GET', TEAM_HOST, '/api/http/team-directory/profiles/me', {
    'Accept': 'application/json',
    'Authorization': 'Bearer ' + accessToken,
  });
  if (meResponse.status !== 200) {
    throw new Error('Failed to resolve team profile: HTTP ' + meResponse.status);
  }
  const profile = JSON.parse(meResponse.body);
  if (!profile.username) {
    throw new Error('Team profile response does not contain username');
  }

  const expirationIso = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString();
  const tokenName = 'mcp-steroid-snapshot-' + Date.now().toString(16);
  const issuePayload = JSON.stringify({
    name: tokenName,
    scope: PERMANENT_SCOPE,
    expires: expirationIso,
  });
  const issueResponse = await request('POST', TEAM_HOST, '/api/http/team-directory/profiles/me/permanent-tokens', {
    'Accept': 'application/json',
    'Authorization': 'Bearer ' + accessToken,
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(issuePayload),
  }, issuePayload);
  if (issueResponse.status !== 200) {
    throw new Error('Failed to issue packages token: HTTP ' + issueResponse.status);
  }

  const issued = JSON.parse(issueResponse.body);
  if (!issued.second) {
    throw new Error('Issued token payload does not contain token secret');
  }

  process.stdout.write(JSON.stringify({
    username: profile.username,
    password: issued.second,
    tokenName,
    expires: expirationIso,
  }));
}

run().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
NODE

              rotatedUser="$(jq -r '.username // empty' "${'$'}issuedCredsFile")"
              rotatedPassword="$(jq -r '.password // empty' "${'$'}issuedCredsFile")"
              if [ -z "${'$'}rotatedUser" ] || [ -z "${'$'}rotatedPassword" ]; then
                echo "[SNAPSHOT-PREBUILD] Rotated credential payload is incomplete" >&2
                return 1
              fi

              export JB_SPACE_CLIENT_ID="${'$'}rotatedUser"
              export JB_SPACE_CLIENT_SECRET="${'$'}rotatedPassword"
              echo "[SNAPSHOT-PREBUILD] Rotated JetBrains packages credentials from ~/.jb/tokens/jetbrains.team.json"
            }

            credentialSource=""
            if [ -n "${'$'}{JB_SPACE_CLIENT_ID:-}" ] && [ -n "${'$'}{JB_SPACE_CLIENT_SECRET:-}" ] && test_packages_creds "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"; then
              echo "[SNAPSHOT-PREBUILD] Reusing JB_SPACE_CLIENT_* credentials from container environment"
              credentialSource="container-env"
            elif [ -f "${'$'}HOME/.netrc" ]; then
              existingUser="$(read_netrc_field_for_machine "packages.jetbrains.team" "login")"
              existingPassword="$(read_netrc_field_for_machine "packages.jetbrains.team" "password")"
              if [ -n "${'$'}existingUser" ] && [ -n "${'$'}existingPassword" ] && test_packages_creds "${'$'}existingUser" "${'$'}existingPassword"; then
                export JB_SPACE_CLIENT_ID="${'$'}existingUser"
                export JB_SPACE_CLIENT_SECRET="${'$'}existingPassword"
                echo "[SNAPSHOT-PREBUILD] Reusing valid JetBrains packages credentials from ~/.netrc"
                credentialSource="netrc"
              else
                echo "[SNAPSHOT-PREBUILD] Existing ~/.netrc credentials are missing or invalid, rotating token"
                issue_packages_credentials_from_jb_oauth_cache
                credentialSource="oauth-cache"
              fi
            else
              echo "[SNAPSHOT-PREBUILD] ~/.netrc is missing, rotating token from ~/.jb cache"
              issue_packages_credentials_from_jb_oauth_cache
              credentialSource="oauth-cache"
            fi

            if [ -z "${'$'}{JB_SPACE_CLIENT_ID:-}" ] || [ -z "${'$'}{JB_SPACE_CLIENT_SECRET:-}" ]; then
              echo "[SNAPSHOT-PREBUILD] Failed to resolve JetBrains packages credentials for Bazel authorizer" >&2
              exit 1
            fi

            if ! test_packages_creds "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"; then
              echo "[SNAPSHOT-PREBUILD] Resolved JetBrains packages credentials are not accepted by required packages.jetbrains.team repositories" >&2
              exit 1
            fi
            echo "[SNAPSHOT-PREBUILD] Using JetBrains packages credentials source: ${'$'}credentialSource"

            # Bazel uses ~/.netrc for authenticated package fetches and remote cache access.
            # Keep direct packages, cache-redirector, and Bazel HTTP remote cache hosts in sync.
            touch "${'$'}netrcFile"
            chmod 600 "${'$'}netrcFile"
            upsert_netrc_machine "packages.jetbrains.team" "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"
            upsert_netrc_machine "cache-redirector.jetbrains.com" "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"
            upsert_netrc_machine "ultimate-bazel-cache-http.labs.jb.gg" "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"

            # Force build/private-packages-auth/authorizer.sh to execute in this container run.
            rm -f "${'$'}authorizerLastRunFile" "${'$'}authorizerLastMtimeFile"

            cd "${'$'}projectDir"

            if [ ! -f "${'$'}bazelWrapper" ]; then
              echo "[SNAPSHOT-PREBUILD] Missing Bazel script: ${'$'}bazelWrapper" >&2
              exit 1
            fi
            chmod +x "${'$'}bazelWrapper"

            cpuCount="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN || echo 4)"
            case "${'$'}cpuCount" in
              ''|*[!0-9]*)
                cpuCount=4
                ;;
            esac
            if [ "${'$'}cpuCount" -lt 2 ]; then
              bazelJobs=2
            elif [ "${'$'}cpuCount" -gt 8 ]; then
              bazelJobs=8
            else
              bazelJobs="${'$'}cpuCount"
            fi

            run_bazel_build_once() {
              attempt="${'$'}1"
              shift || true
              echo "[SNAPSHOT-PREBUILD] Bazel attempt ${'$'}attempt: ${'$'}bazelPrimaryTarget (jobs=${'$'}bazelJobs, extraFlags='${'$'}*')" >&2
              "${'$'}bazelWrapper" --host_jvm_args=-Xmx"${'$'}bazelHostJvmXmx" build \
                --config=ci \
                --jobs="${'$'}bazelJobs" \
                --worker_max_multiplex_instances=JvmCompile=1 \
                "${'$'}@" \
                "${'$'}bazelPrimaryTarget"
            }

            # Retry once after Bazel server restart. Keep worker mode enabled because
            # JvmCompile in this setup requires persistent workers.
            run_bazel_build_once 1 || {
              bazelExitCode="${'$'}?"
              echo "[SNAPSHOT-PREBUILD] Bazel build attempt 1 failed with exit code ${'$'}bazelExitCode" >&2
              "${'$'}bazelWrapper" shutdown || true
              pkill -9 -f 'bazel\(project-home\)' || true
              sleep 2
              run_bazel_build_once 2 || {
                bazelExitCode="${'$'}?"
                echo "[SNAPSHOT-PREBUILD] Bazel build attempt 2 failed with exit code ${'$'}bazelExitCode" >&2
                exit "${'$'}bazelExitCode"
              }
            }
        """.trimIndent()

        scope.startProcessInContainer {
            this
                .args("bash", "-lc", bazelBuildScript)
                .timeoutSeconds(14_400)
                .description("Run IntelliJ Bazel build for snapshot prebuild")
        }.assertExitCode(0) {
            "IntelliJ Bazel build failed before snapshot"
        }
        console.writeSuccess("IntelliJ Bazel build complete")
    }

    private fun runIntelliJBuildForSnapshot(projectDir: String) {
        console.writeStep(0, "Running IntelliJ build before snapshot...")
        val projectName = mcpSteroid.mcpListProjects().singleOrNull { it.path == projectDir }?.name
            ?: error("No IntelliJ project found at $projectDir for pre-snapshot IntelliJ build")
        val ideBuild = mcpSteroid.mcpExecuteCode(
            projectName = projectName,
            reason = "Pre-snapshot IntelliJ build for warmed compile/index caches",
            timeout = 14_400,
            code = """
import com.intellij.task.ProjectTaskManager
import java.util.concurrent.TimeUnit

// buildAllModules may open modal progress dialogs (e.g. "Resolving SDKs").
// These are expected and should not cancel the current execution.
doNotCancelOnModalityStateChange()

val changedFilesScanProperty = "idea.indexes.pretendNoFiles"
val oldChangedFilesScanProperty = System.getProperty(changedFilesScanProperty)
System.setProperty(changedFilesScanProperty, "true")
println("[SNAPSHOT-PREBUILD] Temporarily set ${'$'}changedFilesScanProperty=true to skip changed-files scan")

try {
    println("[SNAPSHOT-PREBUILD] Starting IntelliJ buildAllModules() ...")
    val result = ProjectTaskManager.getInstance(project).buildAllModules().blockingGet(4, TimeUnit.HOURS)
        ?: error("IntelliJ build returned null result")
    println("[SNAPSHOT-PREBUILD] IntelliJ build finished: errors=${'$'}{result.hasErrors()}, aborted=${'$'}{result.isAborted()}")
    check(!result.isAborted()) { "IntelliJ build was aborted" }
    check(!result.hasErrors()) { "IntelliJ build reported compile errors" }
} finally {
    if (oldChangedFilesScanProperty == null) {
        System.clearProperty(changedFilesScanProperty)
    } else {
        System.setProperty(changedFilesScanProperty, oldChangedFilesScanProperty)
    }
    println("[SNAPSHOT-PREBUILD] Restored ${'$'}changedFilesScanProperty to ${'$'}{oldChangedFilesScanProperty ?: "<unset>"}")
}
""".trimIndent(),
        )
        ideBuild.assertExitCode(0) {
            "IntelliJ build failed before snapshot"
        }
        console.writeSuccess("IntelliJ build complete")
    }

    /**
     * Re-apply the IDE window layout by finding the IntelliJ window by PID and calling
     * [XcvbWindowDriver.updateLayout] with the target rect from [windowLayout].
     *
     * IntelliJ restores its own saved window bounds after project load, overriding the
     * initial xdotool positioning. This must be called after project initialization completes.
     */
    private fun repositionIdeWindow() {
        val ideWindow = windows.listWindows().firstOrNull { it.pid == pid }
        if (ideWindow == null) {
            println("[IDE-AGENT] repositionIdeWindow: no window found for PID=$pid, skipping")
            return
        }
        val targetRect = windowLayout.layoutIntelliJWindow()
        windows.updateLayout(ideWindow, targetRect)
        // Nudge the window size by 1px then restore: IntelliJ AWT may not notice the external
        // move and keeps rendering at the old position (50px gap at top, status bar clipped).
        // A second ConfigureNotify with a different size forces AWT to re-layout correctly.
        windows.forceRelayout(ideWindow, targetRect)
    }

    companion object
}
