# Growth execution backlog

Canonical plan: [docs/50K-DAU-30-DAY-PLAN.md](docs/50K-DAU-30-DAY-PLAN.md)

Agent distribution plan:
[docs/AGENT-MARKETPLACE-DISTRIBUTION-PLAN.md](docs/AGENT-MARKETPLACE-DISTRIBUTION-PLAN.md)

Primary epic: [#297](https://github.com/jonnyzzz/mcp-steroid/issues/297)

This file is the repository-side execution index. GitHub issues remain the
source of truth for discussion and acceptance criteria.

## P0 — assign and start

- [ ] Assign an owner and target date to #297.
- [ ] Assign the next implementation owners to native Claude #308 and native
  Codex #307.
- [ ] Resolve whether PR #140 is rebased or replaced; do not leave Claude
  publication blocked on a conflicting branch.
- [ ] Scaffold the Codex plugin and local marketplace from the current official
  plugin contract; validate public-directory eligibility for a client-side
  stdio MCP server.
- [ ] Assign #290, #292, #286, #293, and #305 so privacy, measurement,
  truthful activation, listing trust, and daily operations have owners.
- [ ] Record the exact day-7 embedded-distribution commitment gate in #297.

## P0 — truthful activation

- [ ] Ship a public release containing the already-completed Java-21 bytecode
  compatibility fix before broad Android Studio promotion.
- [ ] Fix the public devrig installer/runtime instructions and canonical-domain
  metadata in #286/#296.
- [ ] Promote #224 as a product-wide payload/time-to-first-value task.
- [ ] Run 20 clean-machine activation attempts through #258 and publish the
  failure-stage distribution.
- [ ] Provide one executable first task through #249.
- [ ] Stop leading with the 20–54% benchmark claim until #251/#264/#288 produce
  valid evidence.

## P0 — native agent marketplaces

- [ ] Claude #308: final manifest, user-scope MCP registration, bootstrap,
  progress, first task, compaction recovery, cross-platform tests, submission.
- [ ] Codex #307: `.codex-plugin/plugin.json`, skills, `.mcp.json`, assets,
  marketplace entry, local tests, Git/npm distribution, submission decision.
- [ ] Ensure both packages invoke the same devrig release/install contract and
  contain no bundled stale binary.
- [ ] Keep MCP stdio wrappers stderr-only before exec.
- [ ] Measure install → devrig ready → first verified value through #292.

## P1 — embedded and trusted distribution

- [ ] #298: prepare the JetBrains partner/featuring package and assign placement
  owners.
- [ ] #114: complete the built-in MCP coexistence/capability experiment.
- [ ] #304: publish the MCP registry and package-manager decision matrix.
- [ ] #301: quantify the audience lost to IDE/platform compatibility limits.
- [ ] #293: verify Marketplace vendor, source, support, privacy, compatibility,
  screenshots, and canonical URLs.

## P1 — retention and migration

- [ ] #294/#271: migrate plugin-only users to devrig after explicit consent.
- [ ] Resolve the stateless-devrig decision before implementing #270/#295
  persistent lifecycle state.
- [ ] Keep #268/#270/#295 staged behind the working first-install funnel.
- [ ] Defer generic third-party plugin management #260 unless a distribution
  partner requires it.

## P1 — evidence, community, and business

- [ ] #251 → #264 → #288: repair the benchmark, add a semantic case, publish
  reproducible evidence.
- [ ] #306: run the community/referral loop only after activation reaches 35%.
- [ ] #303: capacity-plan release downloads and establish launch support triage.
- [ ] Limit #287 paid-pilot work to a small parallel track; report revenue
  separately from vDAI.

## Client-side trust scope

- [ ] #289: publish the concise local trust/data-flow model.
- [ ] Reproduce localhost/browser and deliberate non-loopback behavior before
  prescribing additional access controls.
- [ ] Remove or redact INFO-level source/tool request and response payload
  logging.
- [ ] Document local logs/artifacts, uninstall, cleanup, update integrity, and
  optional analytics controls.
- [ ] Do not introduce SaaS compliance or a hosted control plane as a growth
  prerequisite.

## Daily operating checklist

- [ ] Record qualified reach by channel.
- [ ] Record install started/completed and activation.
- [ ] Record actual consenting vDAI and D1/D7 retention.
- [ ] Record the top three activation failures and their owners.
- [ ] Record support incidents per 100 activations.
- [ ] Stop or iterate channels according to #305.
- [ ] Post one concise decision/update to #297.

## Review follow-ups

- [ ] Re-run the independent Claude strategy review. On 2026-07-16, two
  safe-mode repository reviews and one tools-disabled local review timed out or
  failed without producing an opinion; no Claude output was used as evidence.
- [ ] Re-check the official Codex plugin manual immediately before packaging or
  public submission because marketplace contracts can change.
