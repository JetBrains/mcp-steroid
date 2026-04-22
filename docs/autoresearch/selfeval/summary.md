# Autoresearch iteration summary

Running tally of Claude's self-eval report across prompt-tuning iterations.

Metric legend:
- **(a)** tasks where MCP Steroid adds capability
- **(b)** tasks where MCP Steroid applies but offers no improvement
- **skip** tasks marked "no suitable candidate in this codebase"
- **err** tasks where a tool call errored
- **time** wall-clock for the testSerenaSelfEvalPrompt run (mm:ss)

| iter | commit | (a) | (b) | skip | err | time | focus of change |
|------|--------|-----|-----|------|-----|------|-----------------|
| 00   | 534c008c (baseline) | — | — | — | — | — | — |
