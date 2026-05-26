# devrig npm launcher

This package is now only a thin launcher stub for the Kotlin `devrig` CLI.
It does not contain a TypeScript MCP proxy implementation.

Set `DEVRIG_KOTLIN_LAUNCHER` to the Kotlin devrig executable and run:

```bash
npx devrig --help
```

## What devrig does

devrig is a stateless stdio MCP server + CLI that discovers running
IntelliJ instances on the host and routes MCP tool calls to them.
The project / backend naming contract, the on-demand routing model,
and the JSON output schemas are specified in
[`docs/devrig-naming.md`](../docs/devrig-naming.md) (with the
rationale for on-demand vs background scanning in
[`docs/devrig-scanning-research.md`](../docs/devrig-scanning-research.md)).
