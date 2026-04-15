/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.git.BareRepoCache
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File


sealed class IntelliJProject{
    abstract fun IntelliJProjectDriver.deploy()

    /**
     * Returns the HTTPS clone URL for the repository that this project deploys,
     * or null if this project is not backed by a remote git repository.
     *
     * Used by [IntelliJContainer.create] to warm the bare repo cache on the host
     * before the container starts, so [GitDriver.cloneFromCachedBare] can use the
     * fast local clone path instead of hitting the remote.
     */
    open fun getRepoUrlForCache(): String? = null

    /**
     * Warm host-side cache artifacts before container startup.
     *
     * Default behavior:
     * - if [getRepoUrlForCache] is non-null: warm bare git cache
     * - otherwise: no-op
     */
    open fun warmRepoCache(cacheDir: File) {
        val repoUrl = getRepoUrlForCache() ?: return
        BareRepoCache.ensureRepo(repoUrl, cacheDir)
    }

    /**
     * Relative path (from project root) of the file to open when the IDE starts.
     * When null, the default README.md / first source file fallback is used.
     */
    open val openFileOnStart: String? = null

    object TestProject : ProjectFromRepository(
        "test-project",
        openFile = "src/test/kotlin/com/jonnyzzz/mcpSteroid/demo/DemoByJonnyzzzTest.kt",
    )
    object PyCharmTestProject : ProjectFromRepository("test-project-pycharm", openFile = "main.py")
    object GoLandTestProject : ProjectFromRepository("test-project-goland", openFile = "main.go")
    object WebStormTestProject : ProjectFromRepository("test-project-webstorm", openFile = "index.js")
    object RiderTestProject : ProjectFromRepository(
        "test-project-rider",
        openFile = "DemoRider.Tests/LeaderboardTests.cs",
    )

    object MavenTestProject : ProjectFromRepository(
        "test-project-maven",
        openFile = "src/test/java/com/example/demo/CalculatorTest.java",
    )

    object ThisLoggerProject : ProjectFromRepository(
        "thislogger-project",
        openFile = "src/main/kotlin/com/example/util/Logging.kt",
    )

    object KeycloakProject : ProjectFromRemoteGit("https://github.com/keycloak/keycloak.git")
    object IntelliJMasterProject : ProjectFromIntelliJMasterZip(
        openFile = "platform/platform-tests/testSrc/com/intellij/openapi/vfs/newvfs/persistent/PersistentFsTest.java",
    )

    open class ProjectFromRepository protected constructor(
        val projectName: String,
        private val openFile: String? = null,
    ) : IntelliJProject() {
        override val openFileOnStart: String? get() = openFile
        override fun IntelliJProjectDriver.deploy() {
            console.writeInfo("Copying project $projectName files into container-local project-home...")
            val guestProjectDir = ijDriver.getGuestProjectDir()
            val hostProjectSourceDir = IdeTestFolders.dockerDir.resolve(projectName)
            require(hostProjectSourceDir.isDirectory) {
                "Project source directory does not exist: ${hostProjectSourceDir.absolutePath}"
            }

            container.startProcessInContainer {
                this
                    .args("rm", "-rf", guestProjectDir)
                    .timeoutSeconds(30)
                    .description("Remove stale project directory $guestProjectDir")
                    .quietly()
            }.awaitForProcessFinish().assertExitCode(0, "Failed to clean project directory $guestProjectDir")

            container.copyToContainer(hostProjectSourceDir, guestProjectDir)

            // docker cp on macOS Docker Desktop creates directories owned by root inside the container.
            // Fix ownership so the agent user can write to the project directory (e.g. create .idea/).
            // Must run as root (user 0:0) since the files are root-owned and only root can chown them.
            container.startProcessInContainer {
                this
                    .user("0:0")
                    .args("chown", "-R", "agent:agent", guestProjectDir)
                    .timeoutSeconds(30)
                    .description("Fix project directory ownership for agent user")
                    .quietly()
            }.awaitForProcessFinish().assertExitCode(0, "Failed to chown project directory $guestProjectDir")
        }
    }

    open class ProjectFromRemoteGit protected constructor(val repoUrl: String) : IntelliJProject() {
        override fun getRepoUrlForCache(): String = repoUrl

        override fun IntelliJProjectDriver.deploy() {
            val git = GitDriver(container)
            val guestProjectDir = ijDriver.getGuestProjectDir()

            // Derive owner/repo from URL (e.g. "keycloak/keycloak") for the cache path.
            val ownerAndRepo = repoUrl
                .removePrefix("https://github.com/")
                .trimEnd('/')
                .removeSuffix(".git")

            // Use the bare repo cache when it is mounted at /repo-cache inside the container.
            val clonedFromCache = git.cloneFromCachedBare(ownerAndRepo, guestProjectDir)
            if (!clonedFromCache) {
                console.writeInfo("Cache miss for $ownerAndRepo — cloning from $repoUrl ...")
                git.clone(repoUrl, guestProjectDir)
            }
        }
    }

    open class ProjectFromIntelliJMasterZip protected constructor(
        private val openFile: String? = null,
        private val zipUrl: String = INTELLIJ_MASTER_GIT_CLONE_LINUX_ZIP_URL,
        private val repoUrlOverride: String? = null,
        private val branch: String = INTELLIJ_MASTER_BRANCH,
    ) : IntelliJProject() {
        override val openFileOnStart: String? get() = openFile

        override fun warmRepoCache(cacheDir: File) {
            ensureIntelliJGitCloneZipInCache(cacheDir, zipUrl)
        }

        override fun IntelliJProjectDriver.deploy() {
            val guestProjectDir = ijDriver.getGuestProjectDir()
            val guestZipInCache = "/repo-cache/intellij-master-git-clone/ultimate-git-clone-linux.zip"
            container.startProcessInContainer {
                this
                    .args("test", "-f", guestZipInCache)
                    .timeoutSeconds(10)
                    .description("Verify IntelliJ ZIP exists at $guestZipInCache")
                    .quietly()
            }.assertExitCode(0) {
                "Missing IntelliJ git clone ZIP in repo cache at $guestZipInCache. " +
                        "Warm cache first via ensureIntelliJGitCloneZipInCache()."
            }

            console.writeInfo("Unpacking IntelliJ repository and syncing $branch...")
            val setupScript = """
                set -euo pipefail
                zipPath="$guestZipInCache"
                targetDir="$guestProjectDir"
                repoUrlOverride="${repoUrlOverride ?: ""}"
                branch="$branch"
                unpackDir="/tmp/intellij-master-unpack"

                rm -rf "${'$'}unpackDir" "${'$'}targetDir"
                mkdir -p "${'$'}unpackDir"
                unzip -q "${'$'}zipPath" -d "${'$'}unpackDir"

                if [ -d "${'$'}unpackDir/.git" ]; then
                  repoDir="${'$'}unpackDir"
                else
                  gitDir="$(find "${'$'}unpackDir" -mindepth 1 -maxdepth 4 -type d -name .git | head -n 1)"
                  if [ -z "${'$'}gitDir" ]; then
                    echo "No .git directory found in unpacked ZIP: ${'$'}zipPath" >&2
                    exit 1
                  fi
                  repoDir="$(dirname "${'$'}gitDir")"
                fi

                mkdir -p "$(dirname "${'$'}targetDir")"
                mv "${'$'}repoDir" "${'$'}targetDir"
                rm -rf "${'$'}unpackDir"

                if ! git -C "${'$'}targetDir" remote | grep -qx origin; then
                  echo "Expected origin remote in IntelliJ ZIP checkout at ${'$'}targetDir" >&2
                  exit 1
                fi

                if [ -n "${'$'}repoUrlOverride" ]; then
                  git -C "${'$'}targetDir" remote set-url origin "${'$'}repoUrlOverride"
                fi

                if git -C "${'$'}targetDir" config --get-all remote.origin.fetch >/dev/null 2>&1; then
                  git -C "${'$'}targetDir" config --unset-all remote.origin.fetch
                fi
                git -C "${'$'}targetDir" config --add remote.origin.fetch "+refs/heads/${'$'}branch:refs/remotes/origin/${'$'}branch"
                if [ ! -S /tmp/ssh-agent.sock ]; then
                  echo "SSH agent socket missing at /tmp/ssh-agent.sock" >&2
                  exit 1
                fi
                GIT_SSH_COMMAND='ssh -o StrictHostKeyChecking=accept-new -o IdentityAgent=/tmp/ssh-agent.sock' \
                  git -C "${'$'}targetDir" fetch --prune --depth 1 origin "${'$'}branch"
                git -C "${'$'}targetDir" reset --hard
                git -C "${'$'}targetDir" clean -fdx
                git -C "${'$'}targetDir" checkout -f -B "${'$'}branch" --track "origin/${'$'}branch"
                chown -R agent:agent "${'$'}targetDir"

                read_netrc_field_for_machine() {
                  machineName="${'$'}1"
                  field="${'$'}2"
                  netrcPath="${'$'}3"
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
                  ' "${'$'}netrcPath"
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
                    echo "[INTELLIJ-ZIP] Credential check HTTP ${'$'}status for ${'$'}healthcheckUrl (attempt ${'$'}attempt/${'$'}attempts)" >&2
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
                  for healthcheckUrl in \
                    "https://packages.jetbrains.team/maven/p/ij/intellij-private-dependencies/" \
                    "https://packages.jetbrains.team/maven/p/ij/code-with-me-lobby-server/"; do
                    if ! test_packages_creds_for_url "${'$'}username" "${'$'}password" "${'$'}healthcheckUrl"; then
                      echo "[INTELLIJ-ZIP] Credential check failed for ${'$'}healthcheckUrl" >&2
                      return 1
                    fi
                  done
                  return 0
                }

                upsert_netrc_machine() {
                  machineName="${'$'}1"
                  machineLogin="${'$'}2"
                  machinePassword="${'$'}3"
                  netrcFile="/home/agent/.netrc"
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
                  chown agent:agent "${'$'}netrcFile"
                }

                issue_packages_credentials_from_jb_oauth_cache() {
                  issuedCredsFile="/tmp/intellij-packages-creds.json"
                  jbTokenFile="/home/agent/.jb/tokens/jetbrains.team.json"

                  command -v node >/dev/null 2>&1 || {
                    echo "[INTELLIJ-ZIP] node is required to use ${'$'}jbTokenFile" >&2
                    return 1
                  }
                  command -v jq >/dev/null 2>&1 || {
                    echo "[INTELLIJ-ZIP] jq is required to parse rotated credential payload" >&2
                    return 1
                  }
                  [ -f "${'$'}jbTokenFile" ] || {
                    echo "[INTELLIJ-ZIP] Missing jb OAuth token cache: ${'$'}jbTokenFile" >&2
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
                  if (!TOKEN_FILE) throw new Error('JB_TEAM_TOKEN_FILE is not set');

                  const token = decryptTokenFile(TOKEN_FILE);
                  let accessToken = token.accessToken;
                  const expiresAt = typeof token.expiresAt === 'number' ? token.expiresAt : 0;
                  if (!accessToken || Date.now() + 60_000 >= expiresAt) {
                    if (!token.refreshToken) throw new Error('Stored jb token has no refreshToken');
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
                  if (!profile.username) throw new Error('Team profile response does not contain username');

                  const expirationIso = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString();
                  const tokenName = 'mcp-steroid-intellij-zip-' + Date.now().toString(16);
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
                  if (!issued.second) throw new Error('Issued token payload does not contain token secret');

                  process.stdout.write(JSON.stringify({
                    username: profile.username,
                    password: issued.second,
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
                    echo "[INTELLIJ-ZIP] Rotated credential payload is incomplete" >&2
                    return 1
                  fi

                  export JB_SPACE_CLIENT_ID="${'$'}rotatedUser"
                  export JB_SPACE_CLIENT_SECRET="${'$'}rotatedPassword"
                  return 0
                }

                credentialSource=""
                packagesUser=""
                packagesPassword=""
                if [ -n "${'$'}{JB_SPACE_CLIENT_ID:-}" ] && [ -n "${'$'}{JB_SPACE_CLIENT_SECRET:-}" ] && test_packages_creds "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"; then
                  echo "[INTELLIJ-ZIP] Reusing JB_SPACE_CLIENT_* credentials from container environment"
                  credentialSource="container-env"
                else
                  if [ -n "${'$'}{JB_SPACE_CLIENT_ID:-}" ] || [ -n "${'$'}{JB_SPACE_CLIENT_SECRET:-}" ]; then
                    echo "[INTELLIJ-ZIP] JB_SPACE_CLIENT_* credentials are present but failed health checks, trying ~/.netrc" >&2
                  fi
                  packagesUser="$(read_netrc_field_for_machine "packages.jetbrains.team" "login" "/home/agent/.netrc")"
                  packagesPassword="$(read_netrc_field_for_machine "packages.jetbrains.team" "password" "/home/agent/.netrc")"
                fi
                if [ -z "${'$'}credentialSource" ]; then
                  if [ -n "${'$'}packagesUser" ] && [ -n "${'$'}packagesPassword" ] && test_packages_creds "${'$'}packagesUser" "${'$'}packagesPassword"; then
                    export JB_SPACE_CLIENT_ID="${'$'}packagesUser"
                    export JB_SPACE_CLIENT_SECRET="${'$'}packagesPassword"
                    credentialSource="netrc"
                    echo "[INTELLIJ-ZIP] Reusing valid JetBrains packages credentials from ~/.netrc"
                  else
                    if [ -n "${'$'}packagesUser" ] || [ -n "${'$'}packagesPassword" ]; then
                      echo "[INTELLIJ-ZIP] ~/.netrc has packages credentials but they failed health checks, rotating token from ~/.jb cache" >&2
                    else
                      echo "[INTELLIJ-ZIP] ~/.netrc has no packages.jetbrains.team credentials, rotating token from ~/.jb cache"
                    fi
                    issue_packages_credentials_from_jb_oauth_cache
                    credentialSource="oauth-cache"
                  fi
                fi

                if [ -z "${'$'}{JB_SPACE_CLIENT_ID:-}" ] || [ -z "${'$'}{JB_SPACE_CLIENT_SECRET:-}" ]; then
                  echo "[INTELLIJ-ZIP] Failed to resolve JetBrains packages credentials" >&2
                  exit 1
                fi

                if ! test_packages_creds "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"; then
                  echo "[INTELLIJ-ZIP] Resolved packages credentials are not accepted by required repositories" >&2
                  exit 1
                fi
                echo "[INTELLIJ-ZIP] Using JetBrains packages credentials source: ${'$'}credentialSource"

                upsert_netrc_machine "packages.jetbrains.team" "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"
                upsert_netrc_machine "cache-redirector.jetbrains.com" "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"
                upsert_netrc_machine "ultimate-bazel-cache-http.labs.jb.gg" "${'$'}JB_SPACE_CLIENT_ID" "${'$'}JB_SPACE_CLIENT_SECRET"

                cat > /home/agent/.m2/settings.xml <<EOF
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
                  <servers>
                    <server>
                      <id>code-with-me-lobby-server-private</id>
                      <username>${'$'}JB_SPACE_CLIENT_ID</username>
                      <password>${'$'}JB_SPACE_CLIENT_SECRET</password>
                    </server>
                    <server>
                      <id>intellij-private-dependencies</id>
                      <username>${'$'}JB_SPACE_CLIENT_ID</username>
                      <password>${'$'}JB_SPACE_CLIENT_SECRET</password>
                    </server>
                    <server>
                      <id>grazie-platform-private</id>
                      <username>${'$'}JB_SPACE_CLIENT_ID</username>
                      <password>${'$'}JB_SPACE_CLIENT_SECRET</password>
                    </server>
                    <server>
                      <id>jcp-github-mirror-private</id>
                      <username>${'$'}JB_SPACE_CLIENT_ID</username>
                      <password>${'$'}JB_SPACE_CLIENT_SECRET</password>
                    </server>
                  </servers>
                </settings>
                EOF
                chown agent:agent /home/agent/.m2/settings.xml
                chmod 600 /home/agent/.m2/settings.xml

                chown -R agent:agent "${'$'}targetDir"
            """.trimIndent()

            container.startProcessInContainer {
                this
                    .user("0:0")
                    .args("bash", "-lc", setupScript)
                    .timeoutSeconds(900)
                    .description("Prepare IntelliJ repository from ZIP and checkout $branch")
            }.assertExitCode(0) { "Failed to prepare IntelliJ repository from ZIP" }
        }
    }

    /**
     * Deploy a project by cloning a git repository at a specific commit and optionally
     * applying a patch. The project is deployed at the IDE's project-home path so
     * IntelliJ opens it directly on startup — no [steroid_open_project] call needed.
     *
     * Used by arena test runners (e.g. DpaiaArenaTest) to pre-deploy the test scenario
     * before IntelliJ starts, so that [waitForProjectReady] handles indexing as usual.
     *
     * @param cloneUrl         Full HTTPS clone URL (e.g. "https://github.com/dpaia/empty-maven-springboot3")
     * @param repoOwnerAndName Owner/repo without .git suffix (e.g. "dpaia/empty-maven-springboot3")
     * @param baseCommit       Git commit SHA to check out
     * @param testPatch        Unified diff to apply after checkout; empty string means no patch
     * @param displayName      Human-readable name used in console messages
     */
    class ProjectFromGitCommitAndPatch(
        val cloneUrl: String,
        val repoOwnerAndName: String,
        val baseCommit: String,
        val testPatch: String,
        val displayName: String,
        /**
         * Build system hint: "maven" or "gradle". When set to "maven", a minimal
         * `.idea/misc.xml` is pre-created so IntelliJ auto-imports as Maven instead
         * of showing the "Open or Import Project" dialog (which appears when the repo
         * contains both pom.xml and build.gradle).
         */
        val buildSystem: String = "",
    ) : IntelliJProject() {
        override fun getRepoUrlForCache(): String = cloneUrl

        override fun IntelliJProjectDriver.deploy() {
            console.writeInfo("Cloning $displayName ...")
            val git = GitDriver(container)
            val guestProjectDir = ijDriver.getGuestProjectDir()

            val clonedFromCache = git.cloneFromCachedBare(repoOwnerAndName, guestProjectDir)
            if (!clonedFromCache) {
                console.writeInfo("Cache miss — cloning from $cloneUrl ...")
                git.clone(cloneUrl, guestProjectDir, shallow = false, timeoutSeconds = 120)
            }

            git.checkout(guestProjectDir, baseCommit)

            if (testPatch.isNotBlank()) {
                console.writeInfo("Applying test patch for $displayName ...")
                git.applyPatch(guestProjectDir, testPatch)
            }

            if (buildSystem.equals("maven", ignoreCase = true)) {
                console.writeInfo("Pre-creating .idea/ Maven config for $displayName ...")
                val ideaDir = "$guestProjectDir/.idea"
                val miscXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project version="4">
                      <component name="MavenProjectsManager">
                        <option name="originalFiles">
                          <list>
                            <option value="${'$'}PROJECT_DIR${'$'}/pom.xml" />
                          </list>
                        </option>
                      </component>
                    </project>
                """.trimIndent()
                // modules.xml signals to IntelliJ that this is an existing IntelliJ project,
                // preventing the blocking "Open or Import Project" dialog that appears when
                // a directory has both pom.xml and build.gradle (ambiguous build system).
                // Without modules.xml IntelliJ shows the wizard before any project frame is
                // created; misc.xml alone is not sufficient in IntelliJ 2025.3.
                val modulesXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project version="4">
                      <component name="ProjectModuleManager">
                        <modules />
                      </component>
                    </project>
                """.trimIndent()
                val script = """
                    set -euo pipefail
                    mkdir -p "$ideaDir"
                    cat > "$ideaDir/misc.xml" << 'XMLEOF'
$miscXml
XMLEOF
                    cat > "$ideaDir/modules.xml" << 'XMLEOF'
$modulesXml
XMLEOF
                """.trimIndent()
                container.startProcessInContainer {
                    this
                        .args("bash", "-c", script)
                        .timeoutSeconds(15)
                        .description("Pre-create .idea/ Maven config for $displayName")
                        .quietly()
                }.awaitForProcessFinish().assertExitCode(0, "Failed to create .idea/ for $displayName")
            }

            console.writeSuccess("$displayName ready")
        }
    }
}

class IntelliJProjectDriver(
    val lifetime: CloseableStack,
    val container: ContainerDriver,
    val ijDriver: IntelliJDriver,
    val console: ConsoleDriver,
) {
    fun deployProject(project: IntelliJProject) {
        project.apply { deploy() }
    }
}
