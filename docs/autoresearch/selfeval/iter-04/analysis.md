# iter-04 plan

## Gap surfaced by iter-03'

§4 token-efficiency analysis (iter-03'):

> **Kotlin boilerplate overhead:** Every MCP Steroid call includes ~100-200 chars
> of boilerplate (imports, readAction wrappers). This partially offsets output savings.

The "imports" portion of that overhead is a perception gap. The preprocessor
(`kotlin-cli/.../CodeWrapperForCompilation.kt:16-27`) already auto-imports:
  com.intellij.openapi.project.*
  com.intellij.openapi.application.* (incl. readAction, writeAction)
  com.intellij.openapi.vfs.*
  com.intellij.openapi.editor.*
  com.intellij.openapi.fileEditor.*
  com.intellij.openapi.command.*
  com.intellij.psi.*
  kotlinx.coroutines.*
  kotlin.time.Duration.Companion.{seconds, minutes}

But `skill/execute-code-overview.md` §99 told agents:
  "NO AUTO-IMPORTS — Every IntelliJ Class Must Be Imported Explicitly"

That is factually wrong. Agents read this, defensively add imports for
`VirtualFile`, `PsiFile`, `PsiDocumentManager`, `readAction`, `writeAction`,
`CommandProcessor`, etc. every time, and then count the bytes against MCP
Steroid in the category-(b) analysis.

## iter-04 edit

Rewrite the "NO AUTO-IMPORTS" section into two lists:

1. **Auto-imported — do NOT repeat**: the full list from CodeWrapperForCompilation.kt
2. **Still need explicit import**: the FilenameIndex / GlobalSearchScope /
   refactoring-processor / PsiTreeUtil classes that really aren't auto-added.

Adds a rule of thumb: "com.intellij.openapi.*" listed subpackages are free;
everything under `com.intellij.psi.search.*`, `com.intellij.refactoring.*`,
and non-listed `openapi` subpackages (`roots.`, `wm.`, `module.`,
`externalSystem.`) needs explicit import.

## Expected report change

- §4 Token-efficiency should drop the "100-200 chars of boilerplate per
  script" line, or narrow it to "~50-100 chars for the non-auto-import classes".
- §6 "Compounding cost" should shrink: per-call authoring overhead is
  smaller than the agent previously estimated.
- §1 (b) list may lose "small text edits" entirely (the perceived overhead
  dropping below Edit's ~100 bytes puts MCP Steroid at parity instead of
  slightly-worse).
