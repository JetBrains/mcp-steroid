Managing IDE backends for open_project

Download, start, and stop devrig-managed IDEs to open a project in a fresh or specific IDE, and how open_project routes to that backend.

# Managing IDE backends

Use this recipe when the IDE you need is **not already running** — you
want a clean IDE for the project, or a **specific product/version** the
project requires (Rider for .NET, GoLand for Go, a particular platform
build, etc.).

Backend management is a rare, heavyweight operation (it can download an
IDE), so it is **not** an MCP tool. You drive it through the `devrig`
CLI directly; `steroid_open_project` then routes into the backend you
started. Every command supports `--json` (stable, pretty-printed) and
returns exit code `0` on success or `64` on a user/validation/lock
error — always pass `--json` and check the exit code.

## Step 1 — See what IDEs are available

Two read-only commands give you the full decision context. Run them
first and decide what to open *before* mutating anything.

- Installed + running backends, and the projects open in each:
  `devrig backend --json`
- Stable IDEs you can download (latest stable per product, each with its
  `id`, `displayName`, `licenseTier`, `version`, and `releaseDate`), so
  you can pick the right one for the project:
  `devrig backend download --json`

A **backend id is the `id` field** from that list — `idea-ultimate`,
`idea-community`, `pycharm-pro`, `pycharm-community`, `goland`,
`webstorm`, `rider`, `clion`, or `android-studio`. Use the id bare for
the latest stable, or pin a version with `--version` (e.g.
`--version 2026.1`). Copy the `id` verbatim rather than guessing a
short code.

## Step 2 — Download the IDE (if it is not installed yet)

`devrig backend --json` shows installed backends. If the one you need is
absent, download it — bare for latest, or pinned:
`devrig backend download idea-ultimate --version 2026.1 --json`. This
can take minutes; downloads are cached and resumable.

## Step 3 — Start the backend

Start it with `devrig backend start idea-ultimate --json` (add the same
`--version` you downloaded to pin it). This spawns a
**detached** IDE process and returns its `pid` *before* the IDE has
finished starting. Only **one** managed backend runs at a time (a global
lock); if another is running, `start` fails with exit `64` — stop it
first with `devrig backend stop <id> --json`.

## Step 4 — Wait until the backend is discoverable, then open

The IDE is not routable until its plugin writes a marker (≈5–15 s after
start). Poll `steroid_list_projects` until the new IDE appears, then call
`steroid_open_project` with the absolute project path.

`steroid_open_project` prefers a **running devrig-managed backend** over
any other (even newer) IDE — so once your backend is discovered, the
project opens there deterministically. To open **several** projects in
that one IDE, call `steroid_open_project` once per path; the global lock
guarantees they all land in the same managed backend.

After opening, poll `steroid_list_windows` (watch `modalDialogShowing`,
`indexingInProgress`, `projectInitialized`) and use
`steroid_take_screenshot` + `steroid_input` for any dialogs, exactly as
in the normal open-project flow.

## Two common modes

- **Open everything in a fresh IDE:** Step 3 (start a clean backend) →
  Step 4, calling `steroid_open_project` for each project path.
- **Download the IDE a project needs:** inspect the project to pick the
  product/version, Step 2 (download it) → Step 3 → Step 4.

## Step 5 — Stop the backend when done

Stop it with `devrig backend stop idea-ultimate --json`. The reported
`outcome` is one of `stopped` (graceful), `killed` (forced),
`already stopped`, `not running`, or `stale`.

# See also

- [Open Project Workflow Overview](mcp-steroid://open-project/overview)
- [Open Project (Trusted)](mcp-steroid://open-project/open-trusted)
- [Open Project (With Dialog Handling)](mcp-steroid://open-project/open-with-dialogs)
- [Open Project via IntelliJ APIs](mcp-steroid://open-project/open-via-code)
