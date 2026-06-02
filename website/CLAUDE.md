# MCP Steroid Website

This directory contains the Hugo source for the MCP Steroid website at https://mcp-steroid.jonnyzzz.com

> **Scope note:** the public website targets *end users* — install instructions,
> demo videos, the strategy page. Agent-facing design tenets live in
> [`docs/PHILOSOPHY.md`](../docs/PHILOSOPHY.md) (canonical, mirrored at
> runtime as `mcp-steroid://skill/design-philosophy`); contributor guidance
> for the plugin / prompts / tests lives in the per-folder `CLAUDE.md`
> and `AGENTS.md` files. Site copy and agent docs evolve on different
> cadences and to different audiences; don't conflate them.

## Architecture Overview

The website is built from this directory using Hugo and deployed via GitHub Pages.

### What is `mcp-steroid-public/`?

The `mcp-steroid-public/` directory is a **separate clone of the same repository** (`jonnyzzz/mcp-steroid`). This setup allows:

1. **Hugo builds to `mcp-steroid-public/docs/`** - Hugo outputs the static site here
2. **GitHub Pages serves from `docs/` folder** - The main repository's `docs/` folder is served at https://mcp-steroid.jonnyzzz.com
3. **Independent commits** - Website changes can be committed and pushed separately from plugin code changes

This is a common pattern for GitHub Pages deployment where the source (Hugo markdown) and output (HTML) live in different places but the same repository.

**Note:** The `mcp-steroid-public/` directory is ignored in `.gitignore` - it's a local clone used only for building and publishing.

## Requirements

- Docker and Docker Compose (for Hugo builds)
- `gh` CLI (authenticated, for querying GitHub release assets)
- `uv` (Python script runner — used instead of `python3` directly)

## Directory Structure

- `hugo.toml` - Hugo configuration with site parameters and featured videos
- `layouts/` - Hugo templates
- `static/` - Static assets (logo, CNAME)
- `content/` - Content files (markdown)
- `scripts/` - Build helpers (`generate-update-plugins-xml.sh`, `generate-update-plugins-xml.py`)
- `mcp-steroid-public/` - Checkout of the public repository (build output destination)

## Adding Documentation Pages

Create new markdown docs under `content/docs/`. Front matter is optional, but recommended:

```markdown
---
title: "My Doc Page"
description: "Short summary shown on the docs landing page"
weight: 3
---

Intro paragraph...

## Overview

Details go here.
```

Notes:
- If `description` is missing, the docs list uses the first paragraph as a summary.
- Nested folders under `content/docs/` are supported; pages are listed by `weight` then title.

## Quick Start

```bash
# Start development server with live reload at http://localhost:1313
make dev

# Build the site to mcp-steroid-public/docs
make build
```

## Manual Setup (if needed)

```bash
# Explicitly clone the public repository
make setup
```

## Development

```bash
# Start development server with live reload at http://localhost:1313
make dev

# Build the site to mcp-steroid-public/docs
make build
```

## Publishing

After running `make build`, commit and push changes in `mcp-steroid-public/`:

```bash
cd mcp-steroid-public
git add docs README.md
git commit -m "Update website"
git push origin main
```

## Important Notes

1. **Version Sync**: The release version is read from `../VERSION` to find the GitHub release and update `hugo.toml`. The actual plugin version used in `updatePlugins.xml` comes from the JAR artifact's `plugin.xml` (e.g. `0.89.0-b8388824`), not from the VERSION file.

2. **Build Output Layout**: The website root lives under `mcp-steroid-public/docs`. Do not create a `public/` folder in this repository.

3. **Public Repository README**: The public repository at https://github.com/jonnyzzz/mcp-steroid contains issues and is user-facing. The README.md in that repository should be kept in sync with the website content and provide similar information about the plugin.

4. **What's New**: Add new entries to `[[params.whatsnew]]` in `hugo.toml` with `date` and `text` fields. Entries are displayed in the order they appear in the file (newest first).

5. **Featured Videos**: Update the `[[params.featuredVideos]]` entries in `hugo.toml` to change which videos appear on the homepage.

6. **CNAME**: The `static/CNAME` file configures the custom domain. Do not remove it.

7. **version.json**: Published at `/version.json` with the current version. Generated automatically during build.

8. **updatePlugins.xml**: Published at `/updatePlugins.xml` — IntelliJ custom plugin repository XML. Generated automatically during build. The Makefile queries `gh` for the release ZIP download URL, then `scripts/generate-update-plugins-xml.py` (run via `uv`) downloads the ZIP, extracts the `ij-plugin-*.jar`, reads `META-INF/plugin.xml` to get the exact plugin version and `since-build`, and generates the XML. This ensures the version and URL always match the actual artifact. The build fails if `gh` is unavailable, the release doesn't exist, or the URL/version validation fails. No fallbacks — errors are fatal.
   - **ZIP selection (since 0.100):** the GitHub release now carries **two** zips — the plugin (`mcp-steroid-*.zip`) **and** the devrig CLI (`devrig-*.zip`). The Makefile's `RELEASE_ZIP_URL` selector matches `startswith("mcp-steroid")` specifically; a bare `endswith(".zip")` would pick `devrig-*.zip` first (alphabetical) and fail with `no ij-plugin-*.jar found in release ZIP`.

9. **Release Pages**: Releases are sorted by **numeric semantic version** (`partials/releases-sorted.html`, used by both `releases/list.html` and `releases/single.html`), newest first. Do **not** revert to `.ByTitle.Reverse` — that string-sorts titles, so `v0.95.0` outranks `v0.100` (`"9" > "1"`), which wrongly promotes an old release as "Latest" and flags the newest as obsolete. The latest release is the featured tile; older releases get an auto-generated obsolete banner (`single.html`). No manual obsolete banners in content files.
   - **Support footer:** the release-page sponsor footer and the homepage `#support` section both render `partials/support-section.html` (single source of truth). Release content `.md` files must **not** carry their own "Support the Project" block.

10. **Plugin Repository Promotion**: The custom plugin repository URL (`https://mcp-steroid.jonnyzzz.com/updatePlugins.xml`) is promoted on the releases list page and in the Download section of release pages (starting from 0.88.0).

## Workflow Summary

```
┌─────────────────────────────────────────────────────────────────┐
│  Main Repository (jonnyzzz/mcp-steroid)                         │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  website/                                                    ││
│  │  ├── content/           ← Hugo source (markdown)             ││
│  │  ├── layouts/           ← Hugo templates                     ││
│  │  ├── hugo.toml          ← Configuration                      ││
│  │  └── mcp-steroid-public/ ← Clone of main repo (ignored)      ││
│  │      └── docs/          ← Hugo build output                  ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │
                    make build │ (Hugo outputs to docs/)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  GitHub Pages serves from jonnyzzz/mcp-steroid:main/docs/       │
│  → https://mcp-steroid.jonnyzzz.com                             │
└─────────────────────────────────────────────────────────────────┘
```
