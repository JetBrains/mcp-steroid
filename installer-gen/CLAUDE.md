# installer-gen/CLAUDE.md

Guidance for the `:installer-gen` module. **Instructions here override the root guide for this folder.**
Read the [root CLAUDE.md](../CLAUDE.md) too (project-wide rules).

## What this module is

`:installer-gen` is **build-tooling** (no IntelliJ deps). It is the **lower** of the two distribution
generators: `:website-gen` depends on it. Two responsibilities:

1. **JDK detection** — `resolveAllJdks(cache)` (in `JdkModel.kt`) produces a `JdkModel` of the JDK 25
   builds the installer ships, each field **computed** from the live vendor sources (nothing hand-pinned).
   The vendor logic is split per file: `CorrettoJdk.kt` (Amazon Corretto) and `AzulJdk.kt` (Azul Zulu).
2. **Installer-script generation** — `InstallerGenerator` + the `install.sh` / `install.ps1` templates
   (consumes the `JdkModel`). *(Ported from PR #113.)* The scripts' scope is LOCKED by
   [docs/install-scripts-contract.md](../docs/install-scripts-contract.md): install devrig + register
   PATH (via the `devrig install devrig` handoff), never auto-register agents or auto-install the IDE
   plugin — those commands are only promoted to the user.

The shared HTTP/cache infra (`Cache.kt`, `HttpFetcher`/`KtorHttpFetcher`) lives here too, so `:website-gen`
reuses it.

## JDK data model — the rules

- **Exactly 5 platforms** the installer supports: Amazon Corretto 25 for `linux-x64`, `linux-arm64`,
  `macos-arm64`, `windows-x64`; Azul Zulu 25 for `windows-arm64` (via the Azul Metadata API). **Latest 25
  is resolved live** — no version pin in source.
- **NO alpine / musl.** The IntelliJ IDEs require glibc, so we do not ship musl builds — `install.sh`
  detects musl and **fails fast**. There is no `ALPINE_LINUX` `JdkOs`. (Also no `macos-x64` — Apple-silicon
  only.)
- **`JdkArtifact` is self-sufficient for the script generator**: `platform`, `vendor`, `version`
  (vendor-native, NOT cross-comparable), `featureVersion` (Int, comparable), `archive` (+
  `ArchiveType.extension`), `url` (version-pinned), `fileName`, `size`, `sha256` (lowercase hex),
  `javaHome`. **`javaHome` is always archive-relative, forward-slash, no leading/trailing slash** —
  computed by scanning entries for the **shallowest** `bin/java[.exe]` (nested `jre/bin/java`-proof).
- **Vendor-natural validation is mandatory and fail-fast** (`PgpVerifier`, BouncyCastle): both vendors
  publish detached OpenPGP signatures (Corretto `<file>.sig`, Azul Metadata-API `signature-binary`). The
  signing-key **fingerprint is pinned in source** (`CORRETTO_KEY_FINGERPRINT` in `CorrettoJdk.kt`,
  `AZUL_KEY_FINGERPRINT` in `AzulJdk.kt`) and asserted before trusting the signature — the key is fetched
  live over HTTPS, so the pin defeats a compromised key endpoint. PGP runs on **every** resolve, incl.
  cache hits. Fingerprints are injectable into `resolveAllJdks` so tests can pin a generated test key.
  Vendor endpoints/keys are catalogued in the `jdk-feed-vendor-endpoints` memory.

## Caching (`Cache.kt`)

- `Cache.onDisk(root)` (one file per key, atomic temp+move) / `Cache.inMemory()` (for tests) behind a
  static factory. `getOrCompute<T>` uses kotlinx.serialization for `T`.
- **`downloadWithEtag`** — HEAD `ETag` cache; **fails fast if the host exposes no ETag** (Corretto).
- **`downloadVerifyingSha256`** — content-addressed by a vendor-published sha256 (Azul, whose CDN exposes
  no HEAD ETag); re-hashes on **every** return incl. cache hits (the cache dir is shared).
- **The cache root must live OUTSIDE any `build/` folder** and **its path is passed in from Gradle**
  (`--cache-dir`), never guessed. `generateJdkModel` roots it at `gradleUserHome/caches/mcp-steroid/…`.

## Running / testing

- Unit tests are **hermetic** (synthetic tar.gz/zip via `TestArchives`, real BouncyCastle signing via
  `TestPgp`, in-memory cache + a fake `HttpFetcher`) — no network. Run: `./gradlew :installer-gen:test`.
  Covered by `ciBuildPluginTests` (auto-swept; guarded in root `build.gradle.kts`).
- The Docker installer integration tests live in a **separate `installerIntegrationTest` source set** —
  NOT in `ciBuildPluginTests`. *(Ported from #113.)*
- `generateJdkModel` hits the network; the first resolve downloads ~1 GB (cached after; re-runs ~15 s).
  Don't run it in the unit-test loop. `KtorHttpFetcher` is `AutoCloseable` — `use {}` it.
- Deps beyond the repo norm: `org.bouncycastle:bcpg-jdk18on` (PGP), `org.apache.commons:commons-compress`
  (archive scanning). `generateJdkModel` sets `maxHeapSize = "2g"` (one ~230 MB `ByteArray` at a time).

## Docker integration lane — NO package installs at test time (#443)

The `installerIntegrationTest` containers must never run `apt-get`/`apk` in their ENTRYPOINT: the
2026-08-04 Ubuntu-mirror stall turned silenced test-time installs into 11 consecutive CI failures —
healthy-looking containers whose tool polls burned 4-minute deadlines with zero diagnostics.
The standing shape (suite runtime dropped ~10 min → ~44 s):

- **pwsh lane needs no curl at all**: `verifyMockServes` probes with built-in
  `pwsh Invoke-WebRequest` (the same download path `install.ps1` itself uses); containers boot with
  a bare `mkdir + sleep` entrypoint.
- **ubuntu sh lane** (curl+unzip ARE the `curl | sh` product contract) uses the pre-baked,
  digest-pinned image from `src/installerIntegrationTest/resources/ubuntu-installer/Dockerfile`
  (bounded 3-attempt apt retry, VISIBLE output, hard `command -v` gate), built once per test JVM via
  test-helper `buildDockerImage()` and BuildKit-layer-cached on the agent. A real mirror outage
  fails fast in `docker build` with full apt output. Update the digest with
  `docker buildx imagetools inspect ubuntu:24.04` (pin the multi-arch index digest, not a per-arch one).
- **Readiness = exec transport only** (`awaitContainerReady`, 60 s, surfaces the actual docker
  error). The alpine musl test keeps its tiny `apk add bash` (harness exec shell only) —
  **un-silenced** so the streamed `[prefix ERR]` log documents any future incident.
- The GH workflow step is bounded at `timeout-minutes: 20` (worst pathological hang was 4 × 15 m
  serial JUnit timeouts under the 45 m job cap).

## Gotchas

- `version` means different things per vendor (`25.0.3.9.1` vs `25.0.3`) — never compare across vendors;
  use `featureVersion`.
