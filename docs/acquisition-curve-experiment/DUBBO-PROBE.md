# Second host repository: the Apache Dubbo probe — PASSED

Run 1039697519, branch `acquisition-curve-experiment`, `AcquisitionDubboProbe` /
`DubboPrewarmProbeTest`. No agent, no API key, no oracle: a container, a clone, an import, a reactor
build and two PSI questions. It exists to answer whether the acquisition question can be ASKED outside
Keycloak, before any case is designed for it.

## Result

| measurement | Dubbo | Keycloak (reference) |
|---|---|---|
| pinned commit | `6054104`, 2026-08-21 | `60c4d5e` |
| IntelliJ modules after import | **116** | 156 |
| JDK symbols resolve | yes | yes |
| the repository's OWN types resolve in project scope (`org.apache.dubbo.rpc.Filter`) | **yes** | yes |
| clone → import → compile → ready | **660 s** | 384 s |
| whole build incl. image and teardown | 15 min 48 s | ~20 min |

The second row of assertions is the one that matters. A container that indexed only the JDK looks
healthy in every other signal; resolving a type that exists nowhere but this repository proves the
119-module reactor was imported as source.

## What it licenses

A research round in Dubbo costs about the same per cell as Keycloak — roughly 11 minutes of setup
against 8, plus the agent's own 3–7 minutes. The axis is open on infrastructure.

## Why Dubbo and not the alternatives

Measured, not argued:

- **Kill Bill** — rejected: its tests are TestNG throughout and require a database, so a hidden oracle
  cannot be a plain JUnit class in one module.
- **Apache Camel** — rejected: 1 124 build files and 26 537 Java sources against Keycloak's 189 and
  8 263. That is not a second data point, it is a second infrastructure project.
- **Dubbo** — 119 Maven modules, 4 050 Java sources, one protobuf-generating module, no npm step
  anywhere in the reactor, module-scoped builds work without installing the whole reactor first (which
  Keycloak's do not, and which costs ~30 minutes per solving cell there).

## What it does NOT license

Nothing about the hypothesis. A case in Dubbo still has to be designed and calibrated against the same
gates every Keycloak case passed: a behavioural statement that localizes nothing, three obvious shell
commands revealing under half the gold set, at least four checklist facts no reference query can
answer, and — the lesson of `RESULTS-DOWNSTREAM-3.md` — an implementation a weak agent can actually
compile inside the interaction budget, if the case is ever to carry a downstream endpoint.
