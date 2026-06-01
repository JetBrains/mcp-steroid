# exec_code options redesign — quorum-approved proposal (2026-05-31)

Status: **IMPLEMENTED & VALIDATED** (unit + `DialogKillerIntegrationTest` Docker green; prompts/server
text aligned; 10-iteration review applied). The proposal/quorum history below is kept for the rationale;
the authoritative current spec is this top section.

---

## SPEC — `steroid_execute_code` modality (current, implemented)

One MCP parameter: **`modal: ModalMode`** (enum, default `smart_non_modal`). Wire values
`smart_non_modal` | `non_modal` | `unleashed`. (Replaces the former `dialog_killer` / `cancel_on_modal` /
`allow_modal` booleans and the runtime `doNotCancelOnModalityStateChange()` — clean break, no aliases.)

| `modal` | Pre-flight | During the body | Post-flight | Use for |
|---|---|---|---|---|
| `smart_non_modal` *(default)* | close leftover modal dialogs (deepest-first) → require non-modal: fail (+screenshot +thread dump) if one survives → commit+save documents + VFS refresh (bounded 60s) → wait for smart mode (bounded 60s; point-in-time) | poll ~1s; a modal dialog still showing is closed and the run FAILS (+screenshot +thread dump) | re-sync documents iff non-modal | PSI / editing / build / test / read-only — the safe default |
| `non_modal` | require non-modal at the START only (fail +screenshot if modal); nothing else | nothing | nothing | "clean non-modal start, I'll prep myself via context methods" |
| `unleashed` | nothing | nothing | nothing | intentional modal-dialog workflows (open/inspect/close) + trivial hardcoded actions; NOT PSI |

**Fine control — `McpScriptContext` methods (any mode; profiles are sugar over these):**
- `closeModalDialogs(): Int` — sweep showing modal dialogs deepest-first; captures screenshot + thread dump
  (skipped on an empty sweep); returns count; does NOT fail the run.
- `monitorAndCloseModalDialogs()` — start a ~1s poll watcher; a modal still showing at a tick is closed and
  the run FAILS (screenshot + thread dump). No-op if already active. Does not sweep dialogs already up.
- `allowModalDialog()` — disable that watcher for the REST of the run (no auto-resume); re-arm by calling
  `monitorAndCloseModalDialogs()` again.
- `syncDocuments()` — commit PSI + save documents + VFS refresh; asserts non-modal (fails on a modal).
- `waitForSmartMode()` — wait for indexing; asserts non-modal; bounded (fails on timeout). Point-in-time —
  index reads still need `smartReadAction { }`, and `Observation.awaitConfiguration(project)` after a
  Gradle/Maven sync (the wait is dumb→smart-mode only).

**Invariants / guarantees:** the modal check is Yuriy's `isModalEdt()` (`EDT + ModalityState.any()`,
`current() != nonModal()`), shared by the gate, killer, and exec_code. Every modality-sensitive EDT op is
bounded (`withTimeout → ToolCallErrorException` + thread dump) so a stuck modal fails fast, never hangs.
A `smart_non_modal` call may FAIL *before* the body (gate / commit guard / smart-mode guard) — documented
behavior, not a script bug; failure artifacts (screenshot, thread dump) land in the execution's storage
folder. There is intentionally **no "close a mid-run dialog and keep going" mode** (`smart_non_modal` fails;
use `unleashed` + `closeModalDialogs()` to tolerate). `ScriptExecutor.executeWithProgress` asserts a
background thread (`ThreadingAssertions.assertBackgroundThread()`) — exec_code must never be driven from the
EDT (a `timeoutRunBlocking`-on-EDT test would deadlock the `withContext(EDT)` dispatches; unit tests set
`runInDispatchThread()=false`).

**Touch points:** `mcp-steroid-server/.../ExecuteCodeTool.kt` (enum + schema), `ij-plugin/.../execution/{ScriptExecutor,McpScriptContext,McpScriptContextImpl,DialogWindowsLookup}.kt`,
`npx-kt/.../DevrigBridgeToolHandlers.kt` (forwards `modal`), prompts `skill/execute-code-tool-description.md`
+ `mcp-steroid-info.md`, tests `ModalModeTest` / `ExecutionManagerTest` / `DialogKillerIntegrationTest`.

---

## Original proposal + quorum history (rationale)

Quorum (run-agent.sh): model approved 3/3 (Gemini APPROVE; Claude CHANGES; Codex CHANGES — both CHANGES
were refinements, all incorporated). The naming/migration the maintainer first floated below
(`auto_close_dialogs` / `disableDialogAutoClose()`) was superseded by the enum above; clean break (no aliases).

## Motivation

Today's options are tangled: `dialog_killer` and `cancel_on_modal` are AND-ed (co-dependent);
`cancel_on_modal` is vestigial (not in schema, never set, always true; its `ModalityStateMonitor`
was removed); `doNotCancelOnModalityStateChange()` is misnamed (no longer cancels execution — it
just stops the killer job); `waitForSmartMode()` is not auto-run despite the corpus claiming it is.

## LOCKED (Revision 5 + final refinements, 2026-05-31) — implementing

Final refinements on top of Revision 5 (maintainer LGTM):
- `closeModalDialogs()` returns what it closed AND has diagnostic side effects: **captures a thread dump
  and screenshots** before closing each dialog.
- The periodic-monitor context API is `monitorAndCloseModalDialogs()` (not `startDialogKiller`) — watches
  all dialogs continuously.
- **Unified monitor semantics for all scenarios:** when the monitor detects a modal dialog it **closes it,
  captures screenshot + thread dump, and STOPS the execution (fails)** — same behavior + side effects
  whether the monitor came from the `smart_non_modal` profile or was started via
  `context.monitorAndCloseModalDialogs()`.
- The one-shot `closeModalDialogs()` is explicit cleanup the script asked for → it does NOT fail the job;
  the pre-flight gate still fails if a modal SURVIVES the initial sweep.
- npx-kt devrig bridge updated to forward the new single `modal` param (drop `dialog_killer`/`allow_modal`).

Validation plan: 3× quorum review of the implementation + live MCP Steroid IDE validation, plus
`DialogKillerIntegrationTest` (rewritten to the new modes/context APIs) and unit tests.

## REVISION 5 (2026-05-31): one `modal` profile enum + context APIs for everything else

Final direction (maintainer): collapse all modal/dialog/prep behavior into ONE `modal` enum of 3
profiles, and move every fine-grained control to `McpScriptContext` methods so the profiles are just
sugar over context calls (and `unleashed` code can opt into any behavior on demand).

### `modal` enum (single MCP param, default `smart_non_modal`)

| Value | Pre-flight + run behavior | For |
|---|---|---|
| `smart_non_modal` *(default)* | sweep dialogs (close leftovers, deepest-first) → assert non-modal (fail+screenshot on a surviving modal dialog) → `sync_documents` (commit+save+VFS) → `wait_for_smart_mode` → run with the dialog killer monitoring (close + fail on a modal appearing during the run). | PSI / code-management flows — the safe default. |
| `non_modal` | assert non-modal at the gate (fail+screenshot if modal); **no** sweep, sync, smart-mode, or killer. Just guarantees a non-modal start, then runs. | "I need non-modal but will prep myself via context APIs." |
| `unleashed` | nothing swept / checked / validated; modal dialogs may be present; just run the code. | Trivial code / hardcoded IDE management. NOT for PSI / code flows (`allow_modal`'s old role — no guarantees). |

### McpScriptContext APIs (fine control; available in every mode; make the profiles composable)

- `waitForSmartMode()` — wait for indexing. **Asserts the EDT is non-modal (before and after); fails the
  execution if a modal is present** (a modal can be a side effect of indexing/sync). Bounded.
- `closeModalDialogs()` *(NEW — the dialog killer, the maintainer's ask)* — run the killer sweep on demand:
  enumerate showing modal dialogs (deepest-first), screenshot, close. Lets `unleashed`/`non_modal` code
  kill dialogs explicitly. Returns what it closed.
- `syncDocuments()` *(NEW)* — commit PSI + save documents + VFS refresh on demand; asserts non-modal, fails
  on a modal (sync can surface a modal as a side effect).
- `allowModalDialog()` *(NEW — replaces `doNotCancelOnModalityStateChange()`)* — in `smart_non_modal`,
  suppress the during-run killer's close+fail for a dialog the script is about to open on purpose.

Equivalence: `smart_non_modal` ≡ `unleashed` + `closeModalDialogs()` + (assert non-modal) + `syncDocuments()`
+ `waitForSmartMode()` + (start monitor). The enum just bundles the common safe sequence.

### Does this implement the DialogKiller tests? (sufficiency check)

| Test | Mode | Body / context calls |
|---|---|---|
| `explicit dialog killer via script API` | Step1 `unleashed` (open modal, survives — no killer) ; Step3 `unleashed` + `context.closeModalDialogs()` | the explicit kill is the context API |
| `automatic dialog killer closes test modal dialog` | Step1 `unleashed` ; Step3 `smart_non_modal` | pre-flight sweep closes the leftover modal |
| `dialog killer captures screenshot before closing` | Step1 `unleashed` ; Step3 `smart_non_modal` (or `unleashed`+`closeModalDialogs()`) | sweep/closeModalDialogs screenshots before closing |
| `screenshot tool works in IDE` | `unleashed` (open modal) | `context.takeIdeScreenshot()` |
| `kills 4 nested modal dialogs deepest-first` | Step1 `unleashed` (open 4 nested) ; Step3 `smart_non_modal` (or `closeModalDialogs()`) | killer closes deepest-first |

✅ Sufficient — **iff** `closeModalDialogs()` is added to the context (the maintainer's ask). Notes:
- Step 1 "open & keep my modal" = `unleashed` (no killer fights it). The `doNotCancelOnModalityStateChange()`
  calls in the current test are DROPPED — `unleashed` makes them unnecessary.
- Cross-test modal leakage: each test's Step 3 closes its modal (sweep or `closeModalDialogs()`), so the next
  test's `unleashed` Step 1 starts clean. A working killer (Rev: commit `16f7b07a`) is the precondition.
- `isModalEdt()` is dynamic: the `smart_non_modal` monitor + the `*` context APIs' non-modal asserts cover
  modals that appear mid-run (incl. as a side effect of sync / smart-mode), failing the execution.

### Open
- Names: `smart_non_modal` / `non_modal` / `unleashed`; `closeModalDialogs` / `syncDocuments` /
  `allowModalDialog`. (`unleashed` per maintainer; alt for it: `raw`, `as_is`.)
- Re-quorum this profile+context-API model before implementing?

---

## (Superseded by Revision 5) REVISION 4: full combination table → natural representation

Options: **S** `close_dialogs_on_start`, **A** `allow_modal` (gate), **R**
`close_dialogs_while_running_and_fail`, **D** `sync_documents`, **W** `wait_for_smart_mode`.

D and W are orthogonal: each runs iff the body runs non-modal; ignored (no-op) when proceeding under a
modal; they never interact with S/A/R or each other. So all interaction is in **S × A × R** (8 rows);
the full 32 = 8 × (D,W) adds no new interaction. "modal present" below = a real modal dialog is up at
that point (after the sweep, for the gate).

| S | A | R | Start | Gate (modal present) | During body (modal appears) | D/W | Verdict |
|---|---|---|---|---|---|---|---|
| T | F | T | sweep | fail+screenshot | close + fail | run if non-modal | **STRICT — default.** Clean. |
| T | F | F | sweep | fail+screenshot | tolerate (keep script's own modal) | run if non-modal | **OPEN-OWN.** Clean. |
| T | T | F | sweep | proceed under modal | tolerate | **ignored if modal** | BEST-EFFORT (close, proceed if survives). Previously disallowed; D/W silently ignored. |
| T | T | T | sweep | proceed under modal | R closes + fails the *allowed* survivor | ignored if modal | **CONTRADICTION** — A says proceed, R fails it. |
| F | F | T | — | fail+screenshot | close + fail | run if non-modal | STRICT-NO-SWEEP. Clean (niche). |
| F | F | F | — | fail+screenshot | tolerate | run if non-modal | FAIL-AT-GATE-ONLY (strict start, lax during). Odd but valid. |
| F | T | F | — | proceed under modal | tolerate | **ignored if modal** | **PERMISSIVE.** Clean. |
| F | T | T | — | proceed under modal | R closes + fails | ignored if modal | **CONTRADICTION** — same as row 4. |

### Dynamic modality — `isModalEdt()` changes during the run

Modality is a MOVING TARGET, not a fixed pre-flight fact. The gate check and the post-flight check are
point-in-time SNAPSHOTS; between them the body can flip the state either way:
- **non-modal → modal**: the body (or a background task) opens a dialog / starts a `Task.Modal`. This is
  exactly what `close_dialogs_while_running_and_fail` (R) exists to catch — a run that passed the gate
  non-modal can still hit a modal mid-flight.
- **modal → non-modal**: under `run_under_modal`, the body may close the modal it was running under. The
  **post-flight** re-check (`!isModalEdt()` at step 6) is what lets sync/commit happen in that case — it
  must NOT reuse the gate snapshot.

Consequences for the model:
- The gate decision (`require_non_modal` fail vs `run_under_modal` proceed) is about the state AT the gate.
- `sync_documents` / `wait_for_smart_mode` run on the gate's non-modal snapshot; if the body later opens a
  modal that is the body's concern (and R's, if enabled). If a modal appears WHILE a non-modal-prep step
  is mid-flight, the step's own `withTimeout` guard is the backstop (it would otherwise hang).
- Post-flight sync is keyed on a FRESH `isModalEdt()` read, never the gate value.
- R is therefore not redundant with the gate: the gate guards the start, R guards the whole body.

### Findings from the table
- **Co-dependency `A ∧ R` is a contradiction** (rows 4, 8): `allow_modal=true` tolerates a modal, `…and_fail=true` fails on it → `A=true ⟹ R must be false`. The booleans re-introduce exactly the kind of co-dependency we set out to remove.
- **D/W are ignored** in every row that proceeds under a modal (rows 3, 4, 7, 8) — silent option-ignoring.
- **A is a no-op** whenever no modal is present at the gate (the common case after a successful sweep).
- **Redundant/odd combos:** row 3 (best-effort, previously disallowed), row 6 (fail-at-start-but-lax-during).
- Of 8 combos, only **3 are clean + sensible + allowed**: STRICT (1), OPEN-OWN (2), PERMISSIVE (7).
  (STRICT-NO-SWEEP (5) is a niche 4th.)

### Conclusion — more natural representation (fewer edge cases, no co-dependencies)
The modal-handling booleans S/A/R generate contradictions and redundancy; the real intent space is 3
states. Represent the modal stance as ONE enum (intent-named, so it does not read as a mechanism bundle),
and keep the genuinely-orthogonal non-modal prep as independent toggles:

| Param | Type | Default | Meaning (⇒ S/A/R tuple) |
|---|---|---|---|
| `modal` | enum | `require_non_modal` | `require_non_modal` = sweep on start, fail+screenshot if any modal at the gate OR appearing during the run (closing it) ⇒ (S=T,A=F,R=T). `allow_own_modal` = sweep on start, fail at the gate, but tolerate modals the script opens during the run ⇒ (S=T,A=F,R=F). `run_under_modal` = don't touch dialogs, run under any modal, skip non-modal prep ⇒ (S=F,A=T,R=F). |
| `sync_documents` | boolean | `true` | commit + save + VFS refresh; runs iff non-modal (ignored under `run_under_modal`). |
| `wait_for_smart_mode` | boolean | `true` | wait for indexing; runs iff non-modal (ignored under `run_under_modal`); bounded. |

- The `A ∧ R` contradiction is **unrepresentable** (collapsed into the enum).
- "best-effort" and "fail-at-start-only" odd combos are excluded by construction.
- D/W remain clean independent toggles; their "ignored when modal" is now an explicit, documented property
  of `run_under_modal` (the only enum value that proceeds modal), not a silent surprise.
- Net surface: **1 enum + 2 booleans**, zero contradictions, one documented ignore (D/W under `run_under_modal`).
- STRICT-NO-SWEEP (niche row 5) is dropped; reintroduce as a 4th enum value only if a real caller needs it.

---

## (Superseded by Revision 4) REVISION 3: pre-flight pipeline — independent, runtime-conditioned steps

The enum (Rev 2) was wrong: it conflated "close dialogs before start" (an ACTION) with "fail if a modal
is present" (a GATE POLICY) — different logic. And `commit`, `VFS refresh`, `wait_for_smart_mode` are a
group that only runs when non-modal. The clean framing: exec_code pre-flight is a **pipeline of steps**,
each its own toggle. The "co-dependency" was an illusion of framing — the non-modal-prep steps are
conditioned on the **runtime modal state**, not on another option's value, so every option is
independently valid in every combination (no invalid combos, nothing to disallow).

The sweep is ALWAYS deepest-first wherever it runs — not a parameter.

### Pre-flight pipeline (each step independent; defaults = the generic bundle)

| # | Option | Default | Runs when | Behavior |
|---|---|---|---|---|
| 1 | `close_dialogs_on_start` | `true` | always | Sweep leftover modal dialogs (deepest-first) before running. |
| 2 | `allow_modal` | `false` | always (the gate) | If a modal DIALOG is still present after step 1: `false` = fail fast + screenshot (require non-modal); `true` = run under it. (Progress/indexing-only modality never fails — it is not a dialog.) |
| 3 | `sync_documents` | `true` | **non-modal only** | Commit PSI + save documents + VFS refresh. Skipped + warned when running under a modal. |
| 4 | `wait_for_smart_mode` | `true` | **non-modal only** | Wait for indexing (dumb mode) to finish. Bounded by a timeout; point-in-time (`smartReadAction{}` still needed). Skipped + warned when running under a modal. |
| 5 | `close_dialogs_while_running` | `true` | during body | Monitor + close modal dialogs that APPEAR while the body runs. Set `false` for a script that opens (and wants to keep) its own modal — replaces the removed runtime hatch. |
| 6 | (post-flight) | — | non-modal only | Re-run `sync_documents` after the body iff `!isModalEdt()`; log when skipped. |

Key property: there is no invalid combination. `wait_for_smart_mode=true` + `allow_modal=true` is valid —
it waits when the run turns out non-modal, and is skipped when a modal is actually up. "Only possible when
non-modal" is a RUNTIME condition on the step, not a constraint between options.

This separates the two concerns the maintainer flagged:
- step 1 (`close_dialogs_on_start`) = "close before start" — an action.
- step 2 (`allow_modal`) = "fail vs proceed if a modal is present" — a policy.
- step 5 (`close_dialogs_while_running`) = "kill modals that appear during the run" — a third, distinct
  concern (separate from step 1; this is what the script-opens-its-own-modal case turns off).

Default bundle (all defaults) = "kill dialogs before start + require non-modal + sync + wait for smart mode
+ kill dialogs during run" — the maintainer's generic profile.

Open sub-decisions:
- Keep `sync_documents` as one toggle (commit+save+VFS), or split into `commit_documents` + `refresh_vfs`?
- Names: `close_dialogs_on_start` / `close_dialogs_while_running` / `allow_modal` / `sync_documents` /
  `wait_for_smart_mode` — or shorter (`sweep_dialogs`, `kill_dialogs`, `sync`, ...)?
- Removed (unchanged from before): `cancel_on_modal`, `dialog_killer`, `doNotCancelOnModalityStateChange()`.

---

## (Superseded by Revision 3) REVISION 2: options are not orthogonal → one enum + one boolean

Maintainer constraints: `auto_close_dialogs` is only meaningful when `allow_modal=false`, and
`wait_for_smart_mode` is only possible when `allow_modal=false`. So `auto_close_dialogs` ×
`allow_modal` is NOT two independent booleans — only 3 of the 4 combos are valid (the
"close-but-proceed-if-one-survives" best-effort combo is intentionally disallowed). The honest
encoding is a single 3-value enum, which makes the invalid combo unrepresentable.

### Proposed shape

| Param | Type | Default | Values / meaning |
|---|---|---|---|
| `on_modal` (name TBD) | enum | `close` | `close` = sweep leftover modals + monitor during; require non-modal (fail-fast w/ screenshot if a real dialog survives). `fail` = don't touch dialogs; require non-modal (fail-fast if any real modal present). `allow` = don't touch; run under the modal (skip commit/VFS + skip smart-mode wait, with a warning). |
| `wait_for_smart_mode` | boolean | `true` | Wait for indexing before the body. Effective only on the non-modal path; **skipped under `allow`** (and on the rare elevated-progress branch). Bounded by a timeout. Point-in-time only (`smartReadAction{}` still needed). |

`waitForSmartMode` could instead be a non-boolean `smart_mode_timeout_seconds` (0 = skip) — folds
"whether" + "how long" — but the bound is a deadlock safety net, not a tuning knob, so a boolean +
internal timeout is recommended. (Open for maintainer.)

### Behavior matrix (component × mode)

| Component | `close` (default) | `fail` | `allow` |
|---|---|---|---|
| 1. Sweep on start | yes (deepest-first) | no | no |
| 2. Periodic monitor | yes | no | no |
| 3. Gate: non-modal | → commit + smart-mode + body | → commit + smart-mode + body | → commit + smart-mode + body |
| 3. Gate: real modal dialog present/survives | FAIL (screenshot) | FAIL (screenshot) | PROCEED: skip commit, warn |
| 3. Gate: progress-only modality (no dialog) | skip commit, warn, proceed | skip commit, warn, proceed | skip commit, warn, proceed |
| 4. Pre-flight commit/VFS | iff non-modal | iff non-modal | iff non-modal |
| 5. `wait_for_smart_mode` (if true) | iff non-modal, bounded | iff non-modal, bounded | skipped when modal |
| 6. Body | runs | runs | runs (maybe under modal) |
| 7. Post-flight commit | iff `!isModalEdt()` now | iff `!isModalEdt()` now | iff `!isModalEdt()` now |

`close` vs `fail` differ ONLY in sweep+monitor (both fail on a surviving real dialog). `fail` vs
`allow` differ ONLY in the modal-present action (fail vs proceed). So the enum cleanly spans the two
real axes (try-to-close? / tolerate-a-modal?) minus the disallowed corner.

Test mapping (`DialogKillerIntegrationTest`): Step 1 "open & keep my modal" → `on_modal=fail`
(no monitor, modal it opens survives; fails only on an unexpected pre-existing modal). Close/kill
steps → `on_modal=close` (sweep closes deepest-first). `doNotCancelOnModalityStateChange()` calls drop.

### Implication vs the prior 3-boolean proposal
- REPLACE `auto_close_dialogs` + `allow_modal` (two booleans) with ONE enum `on_modal`.
- KEEP `wait_for_smart_mode` boolean (dependent: skipped under `allow`).
- Net request-time surface: **1 enum + 1 boolean** (was 3 booleans), zero co-dependent/invalid combos.
- `cancel_on_modal`, `dialog_killer`, `doNotCancelOnModalityStateChange()` all still removed (clean break).

---

## (Superseded by Revision 2 above) Final option set — 3 independent MCP options (no runtime hook)

| Option | Default | Meaning |
|---|---|---|
| `auto_close_dialogs` | `true` | Proactively close modal dialogs: a synchronous sweep before the body starts (clears leftover consent/error modals, deepest-first) AND continuous monitoring while the body runs. Closes via `DialogWrapper` cancel — **does not save** any unsaved dialog state. Set `false` for a script that opens (and wants to keep) its own modal — then neither sweep nor monitor runs. |
| `allow_modal` | `false` | Policy when a modal still blocks after the sweep. `false` = require non-modal: fail fast with a screenshot if a **real modal dialog** (not mere indexing/progress) remains. `true` = run anyway, skipping the modal-sensitive commit/VFS refresh AND `wait_for_smart_mode`, with a warning. |
| `wait_for_smart_mode` | `true` | Wait for indexing (dumb mode) to finish before running the body. Point-in-time wait only — `smartReadAction {}` is still required for index-sensitive reads. Runs ONLY on the confirmed non-modal path; bounded by a timeout. |

No runtime escape-hatch method. The old `doNotCancelOnModalityStateChange()` is **removed** entirely:
"open a modal on purpose, don't auto-close it" is now expressed at the tool call via
`auto_close_dialogs=false` (a request-time boolean is sufficient because the periodic monitor is an
all-or-nothing per-call concern; the prior call's killer already closed any leftover, so there is
nothing left to sweep mid-script). This drops the `onDoNotCancelOnModalityStateChange` plumbing in
`McpScriptContextImpl` and the `McpScriptContext` method.

Dropped: `cancel_on_modal` (vestigial). Removed: `dialog_killer` param, `doNotCancelOnModalityStateChange()`
method + its context plumbing.

Independence — all `auto_close_dialogs` × `allow_modal` combos valid, none co-dependent:
- `true,false` (DEFAULT): clear modals + monitor; if one survives, fail. + wait_for_smart_mode.
- `true,true`: best-effort clear; if uncloseable modal remains, run anyway (skip commit/VFS + smart-mode).
- `false,false`: don't touch dialogs; if any modal present, fail (strict).
- `false,true`: don't touch dialogs; run even under a modal (e.g. testing dialog behavior).

## Pre-flight flow

1. if `auto_close_dialogs`: synchronous sweep (close leftover modals, deepest-first).
2. if `auto_close_dialogs`: start periodic monitor.
3. modality gate (Yuriy's `isModalEdt`) → compute `ranNonModal` once:
   - not modal → commit+save+VFS; `ranNonModal=true`.
   - modal && `allow_modal` → skip commit/VFS + warn; `ranNonModal=false`.
   - modal && !`allow_modal`:
     - real modal `DialogWrapper` → fail fast with screenshot.
     - elevated modality without a dialog (indexing/progress) → skip commit/VFS + warn, proceed;
       `ranNonModal=false` (progress is transient, not a user-consent dialog).
4. if `wait_for_smart_mode` && `ranNonModal`: `waitForSmartMode()` bounded by
   `withTimeout → ToolCallErrorException` (mirrors the commit guard), handling `ProcessCanceledException`.
   Otherwise skip with a warning (running it under a modal would hang).
5. run body.
6. post-flight: commit+save+VFS iff `!isModalEdt()` now; log when skipped due to elevated modality.

## Required changes folded in from quorum

- Bound `waitForSmartMode()` + skip it whenever proceeding under a modal (Claude + Codex — the hang).
- `wait_for_smart_mode` description must not over-promise (point-in-time; `smartReadAction` still needed) (Codex).
- Reconcile prompt corpus / KDoc claiming "waitForSmartMode runs automatically" with the new default +
  the `allow_modal` skip (Claude). Files incl. `McpScriptContext.kt`, `prompts/.../skill/*`, guides.
- Settle `mcp.steroid.dialog.killer.enabled` registry vs `auto_close_dialogs`; fix the misleading
  "Default: use registry setting" schema text (Claude).
- Schema doc: `auto_close_dialogs` closes dialogs without saving (Gemini); `disableDialogAutoClose()` stops
  only ongoing monitoring and is a no-op when auto-close is off (Claude).
- Post-flight keyed on `!isModalEdt()`, with a skip log (Codex + Claude).
- Preserve the real-dialog vs progress/indexing distinction in the fail path — don't fail on background
  progress (Claude).

## Touch points (clean break)

- `mcp-steroid-server/.../ExecuteCodeTool.kt` — `ExecCodeParams` + schema params.
- `ij-plugin/.../execution/ScriptExecutor.kt` — pre-flight flow, gating, bounded smart-mode.
- `ij-plugin/.../execution/McpScriptContext.kt` + `McpScriptContextImpl.kt` — REMOVE
  `doNotCancelOnModalityStateChange()` + the `onDoNotCancelOnModalityStateChange` hook plumbing.
- `npx-kt/.../DevrigBridgeToolHandlers.kt` — forward new param names.
- Tests: `DialogKillerIntegrationTest` — Step 1 (open & keep modal) → `auto_close_dialogs=false`
  (drops the `doNotCancelOnModalityStateChange()` calls); close/kill steps → `auto_close_dialogs=true`.
  npx-kt schema-parity; any other `dialog_killer`/`doNotCancel...` callers.
- Prompt corpus under `prompts/src/main/prompts/` referencing waitForSmartMode/dialog_killer.
