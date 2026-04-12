[![official JetBrains project](http://jb.gg/badges/incubator-flat-square.svg)](https://github.com/JetBrains#jetbrains-on-github)

# MCP Steroid

<p align="center">
  <img src="website/static/pluginIcon.svg" alt="MCP Steroid Logo" width="120" height="120">
</p>

<p align="center">
  <strong>Give AI the whole IntelliJ, not just the files</strong><br>
  <em>AI agents can finally SEE your IDE — not just read code</em>
</p>

<p align="center">
  <a href="https://github.com/jonnyzzz/mcp-steroid/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <a href="https://plugins.jetbrains.com/plugin/30019-mcp-steroid"><img src="https://img.shields.io/badge/JetBrains-Marketplace-orange.svg" alt="JetBrains Marketplace"></a>
  <a href="https://discord.gg/e9qgQ7NeTC"><img src="https://img.shields.io/badge/Discord-Community-5865F2.svg" alt="Discord"></a>
</p>

<p align="center">
  <a href="https://mcp-steroid.jonnyzzz.com">Website</a> &bull;
  <a href="https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f">Demo Videos</a> &bull;
  <a href="https://jonnyzzz.com/blog/2026/04/07/mcp-steroid-open-source/">Blog Post</a> &bull;
  <a href="https://discord.gg/e9qgQ7NeTC">Discord</a>
</p>

---

## About MCP Steroid

**MCP Steroid** is an open-source [Model Context Protocol](https://modelcontextprotocol.io/) server that runs inside JetBrains IDEs and exposes the full power of the IntelliJ Platform to AI agents.

Unlike file-only assistants, MCP Steroid gives AI agents the same capabilities developers use: semantic code understanding, advanced refactorings, debugging, test running, visual awareness, and the entire IntelliJ API surface — all accessible via standard MCP over HTTP.

### What Makes It Unique

MCP Steroid is the **only MCP server** offering ALL of:

- **Visual IDE understanding** — Screenshots + OCR + component tree
- **UI automation** — Control the IDE like a human developer
- **Native IntelliJ APIs** — PSI, inspections, refactorings, and more
- **Kotlin scripting** — Full platform access at runtime
- **Human-in-the-loop safety** — Review modes (ALWAYS / TRUSTED / NEVER)
- **Standard MCP protocol** — Works with ANY MCP-compatible AI agent

### Benchmarks

On [DPAIA](https://dpaia.org) Spring Boot tasks, agents with MCP Steroid are **20–54% faster** than file-only workflows on complex multi-file operations:

| Case | Task | MCP Time | No-MCP Time | &Delta; |
|------|------|----------|-------------|---------|
| dpaia\_jhipster\_sample\_app-3 | Rename ROLE_ADMIN across app (9 files) | 202s | 440s | **-54%** |
| dpaia\_empty\_maven\_springboot3-1 | JWT auth from scratch (5+ new files) | 288s | 396s | **-27%** |
| dpaia\_feature\_service-25 | Parent-child JPA + Flyway (10 files) | 382s | 523s | **-27%** |
| dpaia\_feature\_service-125 | Multi-layer JPA+service+controller (15 files) | 788s | 1002s | **-21%** |
| dpaia\_spring\_petclinic\_rest-14 | Simple URL prefix replace (7 files) | 188s | 181s | +4% |
| dpaia\_train\_ticket-1 | Extend OrderRepository JPQL (4 files) | 727s | 633s | +15% |

Tasks requiring semantic understanding show the largest speed gains. Simple text replacements perform similarly with or without IDE access.

---

## Install

**Requirements:** IntelliJ IDEA 2025.3+ (or any IntelliJ-based IDE: Rider, Android Studio, GoLand, WebStorm, PyCharm, CLion, etc.)

### JetBrains Marketplace

Search for **MCP Steroid** in **Settings > Plugins > Marketplace**, or install from [plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/30019-mcp-steroid).

### Custom Plugin Repository (recommended for faster updates)

Add this URL in **Settings > Plugins > Gear icon > Manage Plugin Repositories...**:

```
https://mcp-steroid.jonnyzzz.com/updatePlugins.xml
```

### Manual Download

Download the latest ZIP from [GitHub Releases](https://github.com/jonnyzzz/mcp-steroid/releases) and install via **Settings > Plugins > Gear icon > Install Plugin from Disk**.

---

## Connect Your AI Agent

When the plugin starts, it writes the server URL to `.idea/mcp-steroid.md` in each open project. Connect any MCP-compatible client to it:

```bash
# Claude Code
claude mcp add --transport http mcp-steroid http://127.0.0.1:6315/mcp

# Or verify with any agent
claude -p "List all open projects using steroid_list_projects"
```

Any MCP client can connect using the Streamable HTTP transport at `http://127.0.0.1:6315/mcp`.

---

## Compatible AI Agents

Works with ANY MCP-compatible client:

- **Claude** (Claude Code, Claude Desktop)
- **ChatGPT** with MCP support
- **Gemini** CLI
- **Codex** CLI
- **Cursor** IDE
- **Junie**
- **OpenCode**
- Any other MCP-compatible client

---

## Capabilities

### 9 MCP Tools

| Tool | Description |
|------|-------------|
| **Execute Code** | Run Kotlin code inside the IDE's JVM with full API access |
| **Execute Feedback** | Provide execution ratings back to agents |
| **Vision Screenshot** | Capture IDE screenshots with component metadata |
| **Vision Input** | Send keyboard/mouse events, OCR analysis |
| **Action Discovery** | Find and invoke IDE actions and quick-fixes |
| **Capabilities Discovery** | Explore available IDE features |
| **List Projects** | Discover all open IntelliJ projects |
| **List Windows** | Enumerate IDE windows and components |
| **Open Project** | Open projects programmatically |

### 58 MCP Resources

Comprehensive guides and examples covering:

- **LSP Operations** (11) — Go to definition, find references, hover, completion
- **IDE Power Operations** (22) — Refactorings, code generation, project analysis
- **Debugger Integration** (7) — Breakpoints, thread control, debugging workflows
- **Test Runner** (10) — Run tests, inspect results, navigate test trees
- **VCS Operations** (3) — Git annotations, file history
- **Project Workflows** (4) — Open projects with trust levels
- **Skill Guides** (3) — IntelliJ API, debugger, and test runner guides

---

## Featured Demo Videos

| Video | Description | Duration |
|-------|-------------|----------|
| [Codex Debugs in IntelliJ IDEA](https://www.youtube.com/watch?v=HtDDNyAoLak) | Full debugging session with Codex | 1:03:24 |
| [CodeDozer Demo 5](https://www.youtube.com/watch?v=6ByedA15n8Q) | Most popular demo | 1:00 |
| [CodeDozer & IntelliJ Debugger](https://www.youtube.com/watch?v=8MjogrpfXLU) | Debugger integration showcase | 8:25 |
| [Now we call tasks in IntelliJ](https://www.youtube.com/watch?v=JGcRk7Y3-Z8) | Task execution demo | 2:21 |
| [Real Work in Monorepo Part 2](https://www.youtube.com/watch?v=ibc0saTT06M) | Deep dive into real workflow | 18:37 |
| [Cursor Talks with IntelliJ](https://www.youtube.com/watch?v=QIl57FrAJtk) | Cursor integration | 0:44 |

Watch all demos: [MCP Steroid Playlist](https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f)

---

## Configuration

MCP Steroid can be configured via IntelliJ's Registry (`Help > Find Action > Registry`) or JVM system properties.

| Registry Key | Default | Description |
|--------------|---------|-------------|
| `mcp.steroid.server.port` | 6315 | MCP server port (0 for auto-assign) |
| `mcp.steroid.server.host` | 127.0.0.1 | Bind address (use 0.0.0.0 for Docker) |
| `mcp.steroid.review.mode` | ALWAYS | Review mode: ALWAYS, TRUSTED, or NEVER |
| `mcp.steroid.storage.path` | (empty) | Custom storage path (default: .idea/mcp-steroid/) |

See the full [Configuration Documentation](https://mcp-steroid.jonnyzzz.com/docs/configuration/) on the website.

---

## Architecture

- **Technology:** Kotlin 2.2.21 on Java 21
- **HTTP Server:** Ktor 3.1.0 (Streamable HTTP + SSE)
- **Protocol:** Model Context Protocol (MCP)
- **Default Port:** 6315
- **OCR:** Tesseract 5.5.1
- **Platform:** IntelliJ Platform Plugin SDK

The server runs **inside the IDE's JVM process** — no inter-process communication. Direct access to the project model, semantic index, PSI tree, test runner, debugger, and VCS layer.

---

## About the Project

**MCP Steroid** is an open-source project by Eugene Petrenko ([@jonnyzzz](https://linkedin.com/in/jonnyzzz)), licensed under [Apache 2.0](LICENSE).

Read more:
- [MCP Steroid Is Now Open Source](https://jonnyzzz.com/blog/2026/04/07/mcp-steroid-open-source/) — announcement and project history
- [IntelliJ as a Skill Factory](https://jonnyzzz.com/blog/2026/04/08/mcp-steroid-skill-factory/) — build custom agent skills without plugin development
- [Project Assessment: 75 Days, 1300+ Commits](https://jonnyzzz.com/blog/2026/02/23/mcp-steroid-project-assessment/) — architecture and quality deep dive

*IntelliJ IDEA, IntelliJ Platform, PyCharm, WebStorm, and JetBrains are trademarks of JetBrains s.r.o.*

---

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on
how to get started, and [CONTRIBUTORS.md](CONTRIBUTORS.md) for the list of people
who have helped make MCP Steroid better.

---

## License

MCP Steroid is open-source software licensed under the [Apache License 2.0](LICENSE).

---

## Links

- **Website:** [mcp-steroid.jonnyzzz.com](https://mcp-steroid.jonnyzzz.com)
- **JetBrains Marketplace:** [plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/30019-mcp-steroid)
- **Discord:** [discord.gg/e9qgQ7NeTC](https://discord.gg/e9qgQ7NeTC)
- **GitHub Issues:** [github.com/jonnyzzz/mcp-steroid/issues](https://github.com/jonnyzzz/mcp-steroid/issues)
- **GitHub Sponsors:** [github.com/sponsors/jonnyzzz](https://github.com/sponsors/jonnyzzz)
- **Blog:** [jonnyzzz.com](https://jonnyzzz.com)
- **YouTube:** [@jonnyzzz](https://youtube.com/@jonnyzzz)
- **LinkedIn:** [jonnyzzz](https://linkedin.com/in/jonnyzzz)
- **X/Twitter:** [@jonnyzzz](https://x.com/jonnyzzz)

---

<p align="center">
  <sub>Built with care for the AI agent developer community</sub>
</p>
