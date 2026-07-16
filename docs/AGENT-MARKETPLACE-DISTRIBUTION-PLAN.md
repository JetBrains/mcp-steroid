# Native Claude and Codex marketplace distribution

Status: active

Parent growth epic: [#297](https://github.com/jonnyzzz/mcp-steroid/issues/297)

Claude issue: [#308](https://github.com/jonnyzzz/mcp-steroid/issues/308)

Codex issue: [#307](https://github.com/jonnyzzz/mcp-steroid/issues/307)

## Goal

Make devrig/MCP Steroid a native installable capability inside the coding
agents developers already use. The package should provide discovery,
installation, local MCP configuration, IDE-first guidance, diagnosis, and one
verified task. It should not require users to copy an MCP JSON fragment from a
website before seeing value.

Claude and Codex use different package contracts, but the product behavior must
remain the same.

## Shared product contract

### Install

1. The user discovers devrig through the native plugin/marketplace browser.
2. The listing explains that devrig and MCP Steroid run locally.
3. Installation contributes agent-native instructions and local MCP
   configuration.
4. If devrig is missing, the integration offers the canonical installer with
   explicit consent, download/runtime information, progress, retry, and
   recovery.
5. Agent registration is user-scoped and survives repository changes and
   devrig upgrades.

### Activate

1. Detect a healthy devrig and compatible IDE/plugin.
2. Run a read-only doctor/status check.
3. Offer one executable first task against a known project.
4. Complete an IDE-backed operation and an inspection, build, or test.
5. Explain the next high-value semantic workflow.

### Retain

- Restore IDE-first guidance after context compaction or a new session.
- Diagnose missing devrig, missing plugin, incompatible versions, no running
  IDE, indexing, and failed tool calls without retry thrashing.
- Use the common release/update contract; integrations do not ship independent
  devrig binaries.
- Keep all stdio wrappers silent on stdout before exec.

## Claude workstream

The Claude path builds on:

- [#222](https://github.com/jonnyzzz/mcp-steroid/issues/222) — Claude UX epic;
- PR #140 — current plugin branch;
- [#137](https://github.com/jonnyzzz/mcp-steroid/issues/137) — setup command;
- [#201](https://github.com/jonnyzzz/mcp-steroid/issues/201) — bootstrap proxy;
- #223–#227 and #242–#249 — progress, messages, recovery, metadata, first
  example, and context-compaction behavior.

### Deliverable

A marketplace-ready Claude package with:

- final plugin manifest and marketplace metadata;
- local devrig MCP registration;
- setup/status/doctor/first-task commands;
- cross-platform wrappers and tests;
- concise recovery messages;
- privacy, support, source, terms, and version links;
- a stable marketplace install link.

### Claude launch gates

- PR #140 is rebased/replaced and reviewable.
- Twenty clean-machine runs cover macOS, Windows, and Linux.
- Median marketplace install to verified value is under 10 minutes.
- At least 50% of completed plugin installs activate before broad promotion.
- The integration survives a new Claude session and context compaction.

## Codex workstream

The current official Codex plugin contract supports:

- a required `.codex-plugin/plugin.json`;
- `skills/`, `hooks/`, `.mcp.json`, and presentation assets at the plugin
  root;
- repo/personal marketplaces under `.agents/plugins/marketplace.json`;
- Git-backed and npm-backed marketplace sources;
- `codex plugin marketplace add` and the CLI `/plugins` browser;
- plugin browsing in Codex desktop and the IDE extension;
- a public plugin submission portal with verified publisher, listing, legal
  links, starter prompts, and positive/negative test cases.

Official references:

- [Build plugins](https://learn.chatgpt.com/docs/build-plugins)
- [Plugins](https://learn.chatgpt.com/docs/plugins)
- [Submit plugins](https://learn.chatgpt.com/docs/submit-plugins)

### Proposed Codex plugin structure

```text
devrig-codex/
├── .codex-plugin/
│   └── plugin.json
├── .mcp.json
├── skills/
│   ├── devrig-first/
│   │   └── SKILL.md
│   └── verify-with-ide/
│       └── SKILL.md
├── hooks/
│   └── hooks.json
└── assets/
    ├── icon.png
    └── logo.png
```

The plugin should package workflow knowledge and configure the local
`devrig mcp` server. It must not introduce a hosted MCP service merely to
satisfy a public-listing format.

### Codex distribution stages

1. Local plugin and personal marketplace.
2. Repository/Git marketplace installed through
   `codex plugin marketplace add`.
3. npm-backed marketplace package if it reduces installation friction without
   lifecycle scripts or a second binary/update path.
4. Public Plugins Directory submission where the client-side package is
   eligible.

The public submission flow may require a hosted MCP URL for MCP-backed apps.
That eligibility must be confirmed. If a local stdio MCP server is not accepted:

- submit the reusable skills package where eligible;
- publish the complete local-MCP plugin through Git/npm marketplace sources;
- do not build a hosted control plane solely to obtain a listing.

### Codex launch gates

- The plugin installs from a clean marketplace source in CLI, desktop, and IDE
  surfaces where supported.
- A new session exposes the skills and local MCP server.
- Missing-devrig bootstrap is explicit and recoverable.
- Five positive and three negative test cases pass.
- Publisher, support, privacy, terms, source, assets, and starter prompts are
  production-ready.
- Install, activation, and verified-value events follow #290/#292.

## Shared test matrix

| Area | Required scenarios |
|---|---|
| Platforms | Windows x64/arm64, macOS arm64/x64 policy, Linux glibc x64/arm64 |
| State | devrig missing, healthy, incompatible, offline, partial install |
| IDE | compatible running IDE, missing plugin, no IDE, managed backend |
| Agent | first install, new session, compaction, plugin disabled/removed |
| Network | interruption, proxy/TLS failure, retry, cached artifact |
| Storage | non-admin user, disk full, uninstall/cleanup |
| Result | first IDE action, verification success, actionable failure |

## Marketplace listing contract

Both integrations use the same claims:

- devrig is the local agent-to-IDE control plane;
- MCP Steroid is the IntelliJ adapter for full IDE APIs;
- supported agents, IDEs, platforms, and limitations are explicit;
- the installer provisions the tested runtime;
- download size and storage locations are disclosed;
- analytics are optional and controllable;
- benchmark claims link to reproducible evidence;
- support and privacy URLs are live before submission.

## Metrics

Per marketplace:

- listing view or qualified reach;
- install started/completed;
- devrig ready;
- IDE/backend ready;
- first IDE-backed action;
- first verified result;
- D1/D7 return;
- support incidents per 100 activations.

Attribution uses explicit campaign/channel identifiers only. It never includes
repository, path, prompt, source, or tool output.

## Scope discipline

Native agent distribution is the immediate priority. Defer:

- new MCP tools;
- broad generic plugin management;
- a hosted SaaS/control plane;
- marketplace-specific forks of devrig;
- agent integrations without a measurable distribution surface.
