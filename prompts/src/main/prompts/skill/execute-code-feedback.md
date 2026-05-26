Execute Code: Feedback & Duplicate Submission Rules

Rules for steroid_execute_feedback usage and avoiding duplicate tool submissions.

# Execute Code: Feedback & Duplicate Submission Rules

## Avoiding Duplicate Submissions

If the tool response contains an `execution_id`, the call succeeded — do **not** resubmit the same code. Identical consecutive calls waste turns and produce no different result. Only retry if the response contains an explicit `ERROR` with no `execution_id`.

---

## steroid_execute_feedback — Call for Meaningful Events Only

Use `steroid_execute_feedback` with `execution_id` + `success_rating` when you encounter a real pattern worth reporting:
- An API compile error
- Unexpected IDE behavior
- A significant success (tests passing after your fix)

**Do NOT call after**:
- Routine file reads
- Trivial one-liners
- When you have nothing specific to report

Empty feedback stubs waste a round-trip (~20s) and inflate call counts without adding value.

---

## Common Operations Summary

- **Code navigation**: Find usages, go to definition, symbol search
- **Refactoring**: Rename, extract method, move files
- **Inspections**: Run code analysis, get warnings/errors
- **Tests**: Execute tests, inspect results
- **Actions**: Trigger any IDE action programmatically

## Quick Example
```kotlin
val file = findProjectFile("src/Main.kt")
val text = readAction {
    PsiManager.getInstance(project).findFile(file!!)?.text
}
println("File length: " + text?.length)
```

## When to Use Other Steroid Tools Instead

- `steroid_list_projects` — list open projects and their paths
- `steroid_list_windows` — check window state, indexing progress, modal dialogs
- `steroid_open_project` — open a project directory in the IDE
- `steroid_take_screenshot` / `steroid_input` — visual UI inspection and interaction
