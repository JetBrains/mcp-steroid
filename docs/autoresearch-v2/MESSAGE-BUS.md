
## Integration Test Results (2026-04-16)

EVAL: GradleTestExecutionTest PASSED (2m57s) — GradleRunConfiguration + setRunAsTest + SMTRunner works in Docker
EVAL: MavenTestExecutionTest FAILED — dialog_killer kills Maven's own JobProviderWithOwnerContext progress dialog, cancelling the run. MavenRunner API itself works but dialog_killer is too aggressive.

Finding: dialog_killer needs to whitelist Maven/Gradle runner progress dialogs. Currently it kills ALL modals indiscriminately, which breaks Maven test execution via MavenRunConfigurationType.
