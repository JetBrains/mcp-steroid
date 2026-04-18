# MCP Steroid Release Instructions

## Repository Layout

There is **one repository**: `jonnyzzz/mcp-steroid` (at `/Users/jonnyzzz/Work/mcp-steroid`).
It contains both the plugin source and the website source (under `website/`).
The website is built and deployed automatically by the **GitHub Actions** workflow
(`.github/workflows/github-pages.yml`) on every push to `main` that touches `website/**`
or `VERSION`. There is no separate website repo and no need to commit generated files.

## Commit-Then-Build Principle

**Always commit and push all release material before building the plugin.**
The plugin ZIP name embeds the HEAD git hash at build time (e.g. `mcp-steroid-0.92.0-59b78976.zip`).
The GitHub release `--target` must point to the same commit. If you add commits after the build,
the release will point to a newer commit than the plugin ZIP — avoid this.

Correct order:
1. Commit all release material (notes, website page, `hugo.toml`, `VERSION`)
2. Push to `origin/main` and sync to `jb/main`
3. Build the plugin
4. Create tags on both remotes
5. Create the GitHub release (attaches to existing tag)
6. Upload to JetBrains Marketplace
7. **Explicitly trigger the website build** and verify it's live

**Critical:** Steps 1-2 must complete before step 3. The website content (release page,
`hugo.toml`) must be on `origin/main` before the GitHub release is created, so the
Pages workflow can find the content when it runs. The push in step 2 will trigger a
Pages build that fails (release doesn't exist yet) — this is expected. The explicit
trigger in step 7 is what actually deploys the website.

## Release Stages

### Stage 0: Preflight

1. Working tree must be clean (`git status` shows nothing).
2. `gh auth status` is valid.
3. `VERSION` is in `X.Y.Z` format.
4. `~/.marketplace` token file exists (one-line JetBrains permanent token).

### Stage 1: Review Changes and Collect Contributors

Before writing release notes, review all changes since the last release and identify
external contributors who should be acknowledged.

**1a. List commits since the last release tag:**
```bash
git log v<prev-version>..HEAD --oneline
```

**1b. Find external contributors (non-maintainer commits and co-authors):**
```bash
# Direct commit authors
git log v<prev-version>..HEAD --format='%an <%ae>' | sort -u

# Co-authors from commit bodies
git log v<prev-version>..HEAD --format='%b' | grep -i 'co-authored-by' | sort -u

# PRs merged in this range
gh pr list --repo jonnyzzz/mcp-steroid --state merged --search "merged:>$(git log -1 --format=%ci v<prev-version> | cut -d' ' -f1)" --json author,title,number
```

**1c. Update `CONTRIBUTORS.md`** with any new contributors — add their name,
GitHub handle, and a short description of their contribution(s). Keep the list
alphabetically sorted within the Contributors section.

**1d. Mention contributors in both release notes and website release page** — list
contributor names with GitHub handles. No need to describe what they did — just thank
them by name.

**1e. Review and collect fixed issues:**

```bash
# List all issues (open and closed)
gh issue list --repo jonnyzzz/mcp-steroid --state all --limit 50 --json number,title,state,closedAt

# Find the v<prev-version> tag date
git log -1 --format=%ci v<prev-version>
```

For each closed issue with `closedAt` after the previous release tag date:
1. Verify the fix is actually in this release (check commits/PRs)
2. Add to the website release page under a **Fixed Issues** section:
   `- [#N](https://github.com/jonnyzzz/mcp-steroid/issues/N) — short description`
3. Comment on the issue: "Fixed in v<version>" — but only if no developer has
   already mentioned the fix version explicitly

For open issues: check if they were actually fixed but not closed. Close them
with a comment if the fix is confirmed in the release range.

### Stage 2: Release Notes

Create `release/notes/<version>.md` with user-facing prose (no raw commit hashes).
Follow the style of previous notes files. Include segues to any relevant blog posts.

If external contributors participated in this release, add a **Contributors** section:
```markdown
### Contributors

Thank you to the contributors in this release:
- **Name** ([@handle](https://github.com/handle))
```

Commit it:
```bash
git add release/notes/<version>.md CONTRIBUTORS.md
git commit -m "release: add <version> release notes and update contributors"
```

### Stage 3: Version Bump

```bash
release/scripts/bump-version.sh
```

This bumps `VERSION` (`X.Y.Z` → `X.(Y+1).0`), commits it, and writes a guard file
to `release/state/version-bump.env` (prevents double-bump on rerun).

Or manually:
```bash
echo "0.92.0" > VERSION
git add VERSION && git commit -m "release: bump version to 0.92.0"
```

### Stage 4: Website Release Page + Homepage Update

**4a. Release page** — create `website/content/releases/<version>.md` following the
pattern of `0.93.0.md`. Page structure (in this order):
1. Success banner + release date
2. **Highlights** (first — this is what users came to see)
3. Download (custom repo, marketplace, manual ZIP link)
4. Connecting to agents + supported agents
5. **Fixed Issues** (list of `#N — description` with links to GitHub issues)
6. **Contributors** (list names with GitHub handles — no descriptions needed)
7. Reporting issues + Discord
8. Feedback + support

EULA link must point to the GitHub release asset:
`https://github.com/jonnyzzz/mcp-steroid/releases/download/v<version>/EULA`

**3b. Homepage** — in `website/hugo.toml`:
- Update `params.version` to the new version
- Add a `[[params.whatsnew]]` entry at the top with date and summary

**Note**: `static/updatePlugins.xml` and `static/version.json` are generated by the
GitHub Actions CI (`make build`). Do **not** commit them — they are in `.gitignore`.
The CI rebuilds them automatically on every push.

Commit:
```bash
git add -f website/content/releases/<version>.md website/hugo.toml
git commit -m "release: add <version> website release page and update homepage version"
```

(The `-f` flag is needed because `website/` is in the root `.gitignore`, but existing
tracked files under `website/` still work normally. New files need `-f` once.)

### Stage 5: Push to Origin and Sync to JB Remote

```bash
git push origin main
```

All release commits (notes, version bump, website page, hugo.toml) must be pushed
**before** building the plugin and creating the GitHub release.

Then sync to the `jb` remote (TeamCity pulls from `jb/main`):
```bash
git fetch jb
git checkout -b jb-merge jb/main
git merge main --no-ff -m "Merge remote-tracking branch 'origin/main' into jb-merge"
git push jb jb-merge:main
git checkout main
git branch -D jb-merge
```

If there are conflicts (e.g. `github-pages.yml` deleted on jb), resolve them
appropriately — jb doesn't use GitHub Pages, so accept jb's deletion.

### Stage 6: Build Plugin

```bash
./gradlew :ij-plugin:buildPlugin -Pmcp.release.build=true
```

Or via IntelliJ MCP (`steroid_execute_code`) with a Gradle run configuration.

The resulting ZIP is in `ij-plugin/build/distributions/mcp-steroid-<version>-<gitHash>.zip`.
The `<gitHash>` must match the current HEAD (the version bump commit).

### Stage 7: Create Tags and GitHub Release

**7a. Create tags on both remotes:**

The release tag on `jb` must point to the **jb merge commit** (not the origin commit),
because TeamCity builds from `jb/main`. The tag on `origin` points to the build commit.

```bash
# Tag on origin (the build commit)
git tag "v<version>" "<gitHash>" -m "Release <version>"
git push origin "v<version>"

# Tag on jb (the merge commit that contains the build commit)
# Find the jb merge commit — it's the latest commit on jb/main after the sync in Stage 5
JB_MERGE=$(git ls-remote jb refs/heads/main | cut -f1)
git tag "v<version>" "$JB_MERGE" -f -m "Release <version>"
git push jb "v<version>"
```

**Note**: If `git push origin "v<version>"` says "already exists", the tag was created
correctly. If `gh release create` fails with "target_commitish is invalid", it means
the tag already exists on the remote — drop `--target` from the `gh` command.

**7b. Create the GitHub release:**

```bash
gh release create "v<version>" \
  "ij-plugin/build/distributions/mcp-steroid-<version>-<gitHash>.zip" \
  EULA \
  --repo jonnyzzz/mcp-steroid \
  --notes-file "release/notes/<version>.md" \
  --title "v<version>"
```

**Do NOT use `--target`** when the tag already exists on the remote — `gh` will reject
it with HTTP 422. The release attaches to the existing tag automatically.

**EULA**: The root `EULA` file is uploaded directly. The `gh` CLI uses the source
filename as the asset name — it appears as `EULA` on the release page.

**Immutable**: Once created, releases cannot have assets added. If a fix is needed,
delete and recreate the release.

### Stage 8: Upload to JetBrains Marketplace

```bash
release/scripts/publish-marketplace.sh "ij-plugin/build/distributions/mcp-steroid-<version>-<gitHash>.zip"
```

Requires `~/.marketplace` (one-line JetBrains permanent token).

**Channel**: Uploads to the `Stable` channel (case-sensitive — `Stable`, not `stable` or `default`).

Plugin page: https://plugins.jetbrains.com/plugin/30019-mcp-steroid

The plugin enters the JetBrains review queue and will be listed once approved.

### Stage 9: Trigger Website Build and Verify

The website build (`make build`) queries the GitHub release for the plugin ZIP download
URL to generate `updatePlugins.xml`. The website content (release page, `hugo.toml`)
was committed and pushed in Stages 4-5, **before** the release was created. The
push-triggered GitHub Actions run from Stage 5 will fail because the release didn't
exist yet — this is expected and harmless.

Now that the GitHub release exists (Stage 7b), explicitly trigger the website build:

```bash
gh workflow run "Deploy to GitHub Pages" --repo jonnyzzz/mcp-steroid --ref main
```

The `release: published` event from Stage 7b may also trigger a run automatically.
Either way, the explicit trigger ensures the build happens.

**Monitor the deployment:**

```bash
# Wait a few seconds for the run to appear, then watch it
gh run list --repo jonnyzzz/mcp-steroid --workflow "Deploy to GitHub Pages" --limit 3
gh run watch <RUN_ID> --repo jonnyzzz/mcp-steroid
```

**Verify the website is live** (with cache-busting to avoid Cloudflare stale responses):

```bash
# version.json should show the new version
curl -sH "Cache-Control: no-cache" \
  "https://mcp-steroid.jonnyzzz.com/version.json?_=$(date +%s)"

# updatePlugins.xml should reference the new ZIP
curl -sH "Cache-Control: no-cache" \
  "https://mcp-steroid.jonnyzzz.com/updatePlugins.xml?_=$(date +%s)" | head -5

# Release page should return HTTP 200
curl -sI "https://mcp-steroid.jonnyzzz.com/releases/<version>/?_=$(date +%s)" | head -3
```

**Cloudflare caching:** The website is behind Cloudflare. Query-string cache-busting
(`?_=<timestamp>`) bypasses the edge cache. If you see stale content without the
query string, it will expire within Cloudflare's TTL (typically 2-4 hours for HTML,
shorter for JSON). Do not purge Cloudflare manually — wait or use cache-busting URLs
for verification.

**If verification fails:** Check the workflow run logs for errors. The most common
failure is the release ZIP not being found — ensure the GitHub release (Stage 7b)
completed successfully before the website build ran.

### Stage 10: Mark Older Releases Obsolete (Optional)

The website template automatically renders an obsolete banner on older release pages
(handled by `layouts/releases/single.html` — no manual content changes needed).

## Key Paths

| File | Purpose |
|------|---------|
| `CONTRIBUTORS.md` | Contributor acknowledgements (update each release) |
| `VERSION` | Current plugin version (`X.Y.Z`) |
| `EULA` | End User License Agreement (uploaded to GitHub releases) |
| `release/notes/<version>.md` | Release notes (used as GitHub release body) |
| `release/scripts/bump-version.sh` | Version bump with rerun guard |
| `release/scripts/publish-marketplace.sh` | JetBrains Marketplace upload (Stable channel) |
| `website/hugo.toml` | Homepage version + whatsnew entries |
| `website/content/releases/<version>.md` | Website release page |
| `website/static/updatePlugins.xml` | **Generated by CI** — do not commit |
| `website/static/version.json` | **Generated by CI** — do not commit |
| `.github/workflows/github-pages.yml` | Automated website build + deploy |

## Notes

- Release notes format: user-friendly prose with section headers, no raw commit hashes.
- All links in release pages must use full URLs (`https://...`) — release content is also
  shown on GitHub where relative links break.
- Custom plugin repository: `https://mcp-steroid.jonnyzzz.com/updatePlugins.xml`
- The `updatePlugins.xml` is generated by `website/scripts/generate-update-plugins-xml.py`
  (run via `uv`). It downloads the release ZIP, extracts `plugin.xml` from the JAR to get
  the exact version and `since-build`. No fallbacks — build fails on any error.
