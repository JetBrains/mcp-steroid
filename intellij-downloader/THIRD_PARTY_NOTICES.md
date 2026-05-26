# Third-Party Notices

The `intellij-downloader` module redistributes the following third-party software.

## 7-Zip 23.01 (LGPL v2.1 or later)

Copyright (C) 1999-2023 Igor Pavlov.

Bundled binaries:

| Resource | Architecture | Source |
|---|---|---|
| `7z/linux-x64/7zz` | Linux x86_64 | https://www.7-zip.org/a/7z2301-linux-x64.tar.xz |
| `7z/linux-arm64/7zz` | Linux aarch64 | https://www.7-zip.org/a/7z2301-linux-arm64.tar.xz |
| `7z/mac/7zz` | macOS universal (x86_64 + arm64) | https://www.7-zip.org/a/7z2301-mac.tar.xz |

The `7zz` console executable is the **full** version of 7-Zip (NSIS, DMG, and other
container formats supported). It is invoked as a separate process by
`com.jonnyzzz.mcpSteroid.ideDownloader.SevenZipLocator` to extract Windows IDE
installers (NSIS `.exe`).

The 7-Zip license text is published at <https://www.7-zip.org/license.txt> and
also shipped alongside each binary at `7z/<platform>/License.txt` (and at the top
level `7z/License.txt`) in this JAR.

### LGPL v2.1+ compliance

* 7-Zip is **dynamically used as an external executable** (forked and run by
  `SevenZipLocator.locate()`). No 7-Zip code is linked into our codebase.
* This satisfies LGPL §6: end-users can replace the bundled binary by dropping a
  different `7zz` build into `~/.cache/mcp-steroid/7z/<hash>/7zz` or by placing
  `7z` / `7za` / `7zz` on `PATH` (the locator prefers PATH when present).
* On Windows hosts no 7-Zip binary is bundled — the locator falls back to PATH
  lookup, so users supply their own 7-Zip installation.

### Source code

The 7-Zip source is published by Igor Pavlov at <https://www.7-zip.org/download.html>.
The corresponding source archive for the bundled version is
<https://www.7-zip.org/a/7z2301-src.tar.xz>.
