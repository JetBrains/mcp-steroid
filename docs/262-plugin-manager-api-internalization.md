# Memo: IntelliJ 2026.2 internalizes the plugin-enumeration / descriptor APIs

**Status:** known, accepted for the 0.100 release. Ship targeting 2026.1 (`261`), which is
clean. The two internal-API usages on 2026.2 EAP are deferred forward-compat debt.

**Date:** 2026-06-02 · **Applies to:** `:ij-plugin` · **Decision owner:** Eugene

**Reported upstream:** [IJPL-246183](https://youtrack.jetbrains.com/issue/IJPL-246183) —
requests clarification of the intended public migration path and a replacement available in
both 261 and 262. Revisit this memo when that issue is answered.

## TL;DR

Across `261 → 262`, the JetBrains platform is progressively marking the entire
plugin-enumeration / plugin-descriptor lookup surface `@ApiStatus.Internal`. The public
successor — `PluginDetailsService` — exists only in `262+`, **not** in `261`. Because we
compile against and support `261`, there is currently **no single typed public API** that is
internal-clean on both `261` and the latest `262` EAP. We ship against `261` (0 internal,
the hard gate) and accept 2 internal usages on `262` EAP until we drop `261` or adopt the
reflection dual-path below.

## The two call sites in our code

| # | Our code | API used | Purpose |
|---|---|---|---|
| 1 | `PluginDescriptorProvider.descriptor` | `PluginManagerCore.getPlugin(PluginId)` | resolve **our own** plugin descriptor (version/id/name) from the id in `plugin.xml` |
| 2 | `ScriptClassLoaderFactory.orderedPluginDescriptors()` | `PluginManagerCore.loadedPlugins` | enumerate all loaded plugins to assemble the kotlinc script classpath (main + content-module classloaders) |

## What is internal in which build (verified by `javap` of the exact IDE jars + Plugin Verifier)

| API | 261.22158.277 | 262.6228.19 | 262.6653.22 |
|---|---|---|---|
| `PluginManagerCore.getPlugin(PluginId)` | public | **internal** | **internal** |
| `PluginManagerCore.getLoadedPlugins()` / `loadedPlugins` | public | **internal** | **internal** |
| `PluginManagerCore.getPlugins()` / `plugins` | public | **internal** | **internal** |
| `PluginManager.findEnabledPlugin(PluginId)` (instance) | public | public | **internal** |
| `PluginManager.getPlugins()` (static) | public | public | **internal** |
| `PluginManagerCore.getPluginSet()` | internal | internal | internal |
| `PluginDetailsService` (`@ApiStatus.Experimental`) | **absent** | absent | **present** |

Direction of travel (master / 263): everything in the table above except `PluginDetailsService`
is `@Internal`. `PluginDetailsService.getActivePlugins()` / `findDetails(PluginId)` is the
intended public replacement, and `PluginDetailsService.findDetails(...)` itself delegates to
`PluginManagerCore.getPlugin(...)` internally — i.e. the platform keeps the implementation in
`PluginManagerCore` but only exposes it through `PluginDetailsService`.

## Why we can't just "fix" it for 0.100

- Switching to `PluginManager.findEnabledPlugin` + `PluginManager.getPlugins()` is **lateral**:
  clean on `261`, but those became internal in `262.6653`, so the EAP count stays at 2 (just
  different methods). Verified by re-running the Plugin Verifier.
- Switching to `PluginDetailsService` would be clean on `262`, but the class **does not exist in
  `261`**, so the plugin would not compile against our `261` SDK — breaking the primary target.
- There is no typed API in the intersection of "public in 261" and "public in 262.6653".

## Migration path (do this when we drop 261, or sooner via reflection)

### Option A — when 261 support is dropped (typed, clean)
Compile against `262+` and switch both call sites to `PluginDetailsService`:

```kotlin
// #1 own descriptor
val details = PluginDetailsService.getInstance().findDetails(PluginId.getId(id))
// -> map PluginDetails back to the fields we need (version/name/id)

// #2 enumerate loaded plugins for the script classpath
PluginDetailsService.getInstance().getActivePlugins()   // Sequence<PluginDetails>
```
Confirm `PluginDetails` exposes the underlying `IdeaPluginDescriptor` (for `contentModules` +
`pluginClassLoader`) before committing — `ScriptClassLoaderFactory` needs the descriptor, not
just metadata. If it doesn't, `#2` needs a different source.

### Option B — reflection dual-path (clean on both, while still supporting 261)
Prefer `PluginDetailsService` reflectively when the class is present (262+); fall back to
`PluginManagerCore` on 261. Reflection here is in **plugin infra**, not a shipped
`steroid_execute_code` recipe, so it does not violate the "typed imports in recipes" rule —
but it is still ugly and should be commented as a temporary 261/262 bridge.

### Sub-option for #1 only — `PluginAwareClassLoader`
A plugin can get **its own** descriptor from its classloader:
`(javaClass.classLoader as? PluginAwareClassLoader)?.pluginDescriptor`. Verify its
`@ApiStatus` in both 261 and 262.6653 before relying on it. This would drop the EAP count from
2 → 1 without touching enumeration (#2), if a partial fix is ever wanted.

## Release-gate note

Per `release/release-instructions.md` §5b, the hard gate is **zero internal-API usages on the
supported (stable) IDEs**. `261` is the stable target and is `0`. `262` is an EAP secondary
verification target; its 2 internal usages are informational for this release and are tracked
here. Re-check this memo when `262` goes stable — at that point the count must reach `0`
(Option A or B), or it becomes a real release blocker.
