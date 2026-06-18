# website-gen/CLAUDE.md

Guidance for the `:website-gen` module. **Instructions here override the root guide for this folder.**
Read the [root CLAUDE.md](../CLAUDE.md) too (project-wide rules).

## What this module is

`:website-gen` is **build-tooling**, not plugin runtime — it has **no IntelliJ dependencies** and **no
`project()` dependencies** (so its Gradle tasks compile only this module, never the whole build). Two
jobs, both driven from Gradle `JavaExec` tasks:

1. **Website artifacts** (`WebsiteArtifacts.kt`, task `generateWebsite`) — resolves the published GitHub
   release and writes `version.json` + `updatePlugins.xml` into `website/static`. The task knows all
   paths (VERSION + release notes from the project layout); CI/Makefile invoke it with no args.
2. **JDK data model** (`JdkArtifacts.kt`, task `generateJdkModel`) — `resolveAllJdks(cache)` produces a
   `JdkModel` of every JDK 25 build the installer needs, each field **computed** from the live vendor
   sources (nothing hand-pinned). Feeds #113's installer-script generation.

## JDK data model — the rules

- **Vendors / platforms**: Amazon Corretto 25 for linux glibc + musl(alpine) x64/aarch64, macOS
  x64/aarch64, windows-x64; Azul Zulu 25 for windows/aarch64 (resolved via the Azul Metadata API).
  **Latest 25 is resolved live** for both — no version pin in source.
- **`JdkArtifact` is self-sufficient for a script generator**: `platform`, `vendor`, `version`
  (vendor-native, NOT cross-comparable), `featureVersion` (Int, comparable — use this for "is it 25?"),
  `archive` (+ `ArchiveType.extension`), `url` (version-pinned), `fileName`, `size`, `sha256` (lowercase
  hex), `javaHome`. **`javaHome` is always archive-relative, forward-slash, no leading/trailing slash**
  (ZIP/TAR entries are `/`-separated even on Windows — consumers translate separators). It is *computed*
  by scanning archive entries for the **shallowest** `bin/java[.exe]` (so a nested `jre/bin/java` is not
  mistaken for `JAVA_HOME`), not hardcoded.
- **Vendor-natural validation is mandatory and fail-fast**: every download is verified against a detached
  **OpenPGP signature** (`PgpVerifier`, BouncyCastle) — Corretto's `<file>.sig`, Azul's Metadata-API
  `signature-binary`. The signing-key **fingerprint is pinned in source** (`CORRETTO_KEY_FINGERPRINT`,
  `AZUL_KEY_FINGERPRINT`): the public key is fetched live over HTTPS, so verification asserts the key
  matches the pinned fingerprint before trusting the signature (defeats a compromised key endpoint). PGP
  runs on **every** resolve, including cache hits. Vendor endpoints/keys are catalogued in the
  `jdk-feed-vendor-endpoints` memory.

## Caching (`Cache.kt`)

- `Cache.onDisk(root)` (one file per key, atomic temp+move) / `Cache.inMemory()` (ConcurrentHashMap, for
  tests) behind a static factory. `getOrCompute<T>` uses kotlinx.serialization for `T`.
- **`downloadWithEtag`** — HEAD `ETag` cache; **fails fast if the host exposes no ETag** (no workaround).
  Used for Corretto (its CDN exposes a HEAD ETag).
- **`downloadVerifyingSha256`** — content-addressed by a vendor-published sha256; used for Azul (its CDN
  exposes no HEAD ETag). Re-hashes on **every** return incl. cache hits, since the cache dir is shared.
- **The cache root must live OUTSIDE any `build/` folder** (so the ~230 MB archives survive `clean` and
  are shared across runs/branches) and **its path is passed in from Gradle** (`--cache-dir`), never
  guessed by the generator. `generateJdkModel` roots it at `gradleUserHome/caches/mcp-steroid/…`.

## Running / testing

- Tests are **hermetic** (synthetic tar.gz/zip via `TestArchives`, real BouncyCastle signing via
  `TestPgp`, in-memory cache + a fake `HttpFetcher`) — **no network**. Run: `./gradlew :website-gen:test`.
  Covered by `ciBuildPluginTests` (guarded — see root `build.gradle.kts`).
- `generateJdkModel` / `generateWebsite` hit the network. The first JDK resolve downloads ~1.5 GB
  (cached after; re-runs are ~15 s). Don't run them as part of the unit test loop.
- Deps beyond the repo norm: `org.bouncycastle:bcpg-jdk18on` (PGP), `org.apache.commons:commons-compress`
  (tar.gz/zip entry scanning). HTTP is Ktor CIO (the repo standard). `KtorHttpFetcher` is `AutoCloseable`
  — `use {}` it (the generator does).

## Gotchas

- Hold archives as a single `ByteArray` (~230 MB each) to hash + scan; `generateJdkModel` sets
  `maxHeapSize = "2g"`. Don't introduce a path that holds all 8 in memory at once.
- The `version` field means different things per vendor (`25.0.3.9.1` vs `25.0.3`). Never compare `version`
  across vendors — use `featureVersion`.
