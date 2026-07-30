# 50k verified daily active installations — 30-day plan

Status: active

Window: 2026-07-16 through 2026-08-15

Primary GitHub epic: [#297](https://github.com/jonnyzzz/mcp-steroid/issues/297)

Growth milestone: [30-day growth: 50k vDAI breakout](https://github.com/jonnyzzz/mcp-steroid/milestone/4)

## Executive decision

The target is 50,000 verified Daily Active Installations (vDAI) in 30 days.
It is a breakout target, not an organic-growth forecast.

The current audience is too small for content and incremental Marketplace
optimization to close the gap:

| Signal | Snapshot on 2026-07-16 |
|---|---:|
| JetBrains Marketplace downloads, cumulative | 788 |
| Latest Marketplace version downloads | 172 |
| Latest devrig GitHub asset downloads | 91 |
| GitHub stars | 60 |
| Discord members | 13 |
| Unique GitHub visitors, prior 14 days | 66 |

Even generous activation and retention assumptions require roughly 250,000
successful installations. A more conservative funnel requires 500,000+.
Therefore, native coding-agent distribution and embedded JetBrains reach are
the primary strategy. Documentation, benchmarks, package managers, community,
and pilots amplify those channels; they cannot replace them.

## Metric contract

Without an explicitly consenting account identity, the product reports
installations, not users.

### Verified Daily Active Installation

Count one consenting installation once per UTC day after both:

1. a successful user- or agent-initiated IDE-backed operation; and
2. a successful verification classified locally as an inspection, build, or
   test.

Do not count:

- startup, session, or heartbeat events;
- page views, Marketplace views, or downloads;
- update checks;
- CI, tests, synthetic runs, or ephemeral evaluation hosts;
- both the devrig and plugin side of the same routed operation.

[#290](https://github.com/jonnyzzz/mcp-steroid/issues/290) defines disclosure
and user control. [#292](https://github.com/jonnyzzz/mcp-steroid/issues/292)
defines event integrity and deduplication.
[#305](https://github.com/jonnyzzz/mcp-steroid/issues/305) owns the operating
dashboard and decisions.

Audience reach, installations, activations, vDAI, retention, qualified leads,
and revenue are reported separately. Reach is never relabeled as activity.

## Product wedge

The product architecture should be legible in one sentence:

> devrig is the local control plane that connects coding agents to every IDE;
> MCP Steroid is the IntelliJ adapter that unlocks full IDE APIs and verified
> semantic workflows.

The built-in JetBrains MCP server is a zero-install standard-capability path,
not an enemy. [#114](https://github.com/jonnyzzz/mcp-steroid/issues/114)
investigates a land-and-expand model:

1. use installed JetBrains capabilities for immediate value where possible;
2. add devrig for agent registration, routing, multiple IDEs, and managed
   backends;
3. add MCP Steroid for full IntelliJ API execution, vision/UI automation, and
   deeper semantic workflows.

The MCP Steroid tool surface remains narrow. Distribution work does not create
new `steroid_*` tools or convenience methods on `McpScriptContext`.

## Channel portfolio

The allocations below are hypotheses, not commitments or forecasts:

| Channel | Role | Breakout contribution hypothesis |
|---|---|---:|
| Native Claude marketplace | Fastest existing implementation path | 10k vDAI |
| Native Codex plugin/marketplace | Native CLI, desktop, and IDE distribution | 10k vDAI |
| JetBrains featuring/co-launch | Direct access to the IDE audience | 25k vDAI |
| MCP registry and package managers | Trusted discovery and install | 2.5k vDAI |
| Community, team, and referral loop | Compounding non-owner acquisition | 2.5k vDAI |

The mix matters more than the exact allocation. If agent and JetBrains
distribution do not materialize, the 50k target remains a moonshot and the
operating forecast must be reduced without changing the metric.

## Workstreams

### 1. Native coding-agent distribution

- [#308](https://github.com/jonnyzzz/mcp-steroid/issues/308) — publish and
  launch the native Claude integration.
- [#307](https://github.com/jonnyzzz/mcp-steroid/issues/307) — publish a native
  Codex plugin and marketplace integration.
- [#222](https://github.com/jonnyzzz/mcp-steroid/issues/222) and PR #140 —
  Claude UX and current implementation.
- [Agent marketplace plan](AGENT-MARKETPLACE-DISTRIBUTION-PLAN.md) — shared
  activation and release contract.

### 2. Embedded and package distribution

- [#298](https://github.com/jonnyzzz/mcp-steroid/issues/298) — JetBrains
  featuring and built-in MCP positioning.
- [#304](https://github.com/jonnyzzz/mcp-steroid/issues/304) — MCP registry,
  Homebrew, Winget/Scoop, and package distribution.
- [#293](https://github.com/jonnyzzz/mcp-steroid/issues/293) — Marketplace
  metadata and trust signals.

### 3. Activation

- [#286](https://github.com/jonnyzzz/mcp-steroid/issues/286) — public
  install-to-first-success funnel.
- [#258](https://github.com/jonnyzzz/mcp-steroid/issues/258) — clean-machine
  end-to-end path.
- [#224](https://github.com/jonnyzzz/mcp-steroid/issues/224) — payload and
  tiered activation.
- [#249](https://github.com/jonnyzzz/mcp-steroid/issues/249) — executable
  first example.
- [#101](https://github.com/jonnyzzz/mcp-steroid/issues/101) and
  [#248](https://github.com/jonnyzzz/mcp-steroid/issues/248) — core and
  agent-shell diagnosis.
- [#138](https://github.com/jonnyzzz/mcp-steroid/issues/138) — agent adoption
  of IDE-backed capabilities.
- [#301](https://github.com/jonnyzzz/mcp-steroid/issues/301) — supported
  platform and IDE-version audience decision.

### 4. Evidence and positioning

- [#251](https://github.com/jonnyzzz/mcp-steroid/issues/251) — repair the
  invalid MCP benchmark arm.
- [#264](https://github.com/jonnyzzz/mcp-steroid/issues/264) — semantic case
  that discriminates IDE value.
- [#288](https://github.com/jonnyzzz/mcp-steroid/issues/288) — publish
  reproducible case studies.

Unsupported 20–54% performance claims must not lead a launch while #251
remains unresolved.

### 5. Retention and migration

- [#294](https://github.com/jonnyzzz/mcp-steroid/issues/294) — plugin-only
  users migrate to devrig.
- [#268](https://github.com/jonnyzzz/mcp-steroid/issues/268),
  [#270](https://github.com/jonnyzzz/mcp-steroid/issues/270), and
  [#295](https://github.com/jonnyzzz/mcp-steroid/issues/295) — recoverable
  installation and updates.
- [#296](https://github.com/jonnyzzz/mcp-steroid/issues/296) — existing-user
  website migration.

Automatic updates matter for retention, but the full lifecycle program must
not delay the first safe, truthful agent-marketplace activation path.

### 6. Operating capacity and community

- [#303](https://github.com/jonnyzzz/mcp-steroid/issues/303) — client-side
  rollout, download capacity, and support.
- [#306](https://github.com/jonnyzzz/mcp-steroid/issues/306) — team, referral,
  and community loop.
- [#287](https://github.com/jonnyzzz/mcp-steroid/issues/287) — paid pilot,
  limited to a small parallel business-validation track.

## Thirty-day sequence

### Days 0–3 — truth and measurement

- Assign owners to #297, #307, #308, #290, #292, #286, and #305.
- Publish the vDAI event/query contract.
- Correct installer, runtime, canonical-domain, Marketplace, and privacy copy.
- Stop promoting benchmark claims invalidated by #251.
- Define the client-side trust boundary in #289.
- Prepare 20 clean-machine activation runs.

### Days 4–7 — installable native integrations

- Resolve or replace PR #140 and make the Claude package marketplace-ready.
- Scaffold and locally install the Codex plugin through a repo marketplace.
- Run the clean-machine activation matrix.
- Reach median install-to-first-verified-value below 10 minutes.
- Require at least 35% activation; target 50%.
- Obtain one credible 100k+ reachable embedded-channel commitment or mark 50k
  as a moonshot rather than an operating forecast.

### Days 8–14 — publish and prove

- Publish the native Claude integration.
- Publish the Codex repo/Git marketplace; submit to the public directory where
  the client-side package is eligible.
- Publish the package-manager/registry matrix.
- Publish one honest reproducible semantic case study.
- Establish one repeatable non-owner acquisition channel.

### Days 15–21 — coordinated launch

- Execute the committed agent/JetBrains launch placements.
- Run short task-specific demos and the semantic-refactoring challenge.
- Review the dashboard and support queue daily.
- Continue only channels with measured installation and activation.
- Require either 20k vDAI trajectory or a scheduled embedded launch capable of
  closing the gap.

### Days 22–30 — retention and expansion

- Repair the dominant activation and D7-retention losses.
- Roll out migration/update behavior in measured stages.
- Expand the best-performing agent/package/community channels.
- Report the actual consenting vDAI, channel mix, retention, support rate, and
  revenue without extrapolation.

## Client-side trust posture

MCP Steroid and devrig run on the customer machine. The plan does not require a
hosted control plane, multi-tenant isolation, SOC program, or enterprise
security project before audience growth.

Required local trust work is narrower:

- make IDE authority and local storage/log behavior explicit;
- keep loopback as the safe default and protect deliberate non-loopback
  exposure proportionally;
- avoid INFO-level source/tool payload logging;
- verify release artifacts and preserve a recoverable installation;
- disclose optional analytics and provide control/deletion;
- provide uninstall and cleanup instructions.

[#289](https://github.com/jonnyzzz/mcp-steroid/issues/289) owns this concise
client-side model.

## Operating cadence and stop rules

- Daily: channel funnel, activation failures, vDAI, support incidents, and
  owner decisions.
- Twice weekly: release/activation gate and distribution commitment review.
- Weekly: retention cohorts, channel capacity, product evidence, and forecast.

Pause a channel after two materially different iterations when:

- high-intent visit to install remains below 10%;
- completed install to activation remains below 35%;
- support exceeds five incidents per 100 activations; or
- the channel has no credible path to non-owner scale.

Do not weaken the metric to preserve the target.
