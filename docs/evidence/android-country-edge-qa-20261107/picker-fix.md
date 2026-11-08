# Applied picker range clears pending inline selection

Fixed `CountryStayEditor.kt` by moving the pending calendar anchor into the editor's saved state. Applying a nested picker range now clears the anchor in the same callback that replaces the dates. Cancelling the picker leaves the anchor intact. Its ISO string representation remains saveable across rotation.

The existing recorded reproduction was converted into `android/.argent/e2e/country-picker-anchor-regression-20261107.yaml`, changing the incorrect November 3 expectation to the correct November 6 result. The original known-bug diagnostic is archived as `picker-original-bug-repro.yaml`.

## Verification

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleMapQa`: successful; 102 unit tests, zero failures/errors/skips.
- Isolated `com.bontecou.whereday.mapqa` updated with `adb install -r`, preserving app data.
- Argent MCP replay on `emulator-5562`: **two consecutive complete passes**, each 71 passed, zero failures/errors/skips, approximately 1m10s. No manual reset between these passes and no selector/idle warnings.
- Each replay tests both the no-pending-anchor control and the formerly broken inline November 3 → picker November 4–5 Apply → inline November 6 path. Both now show November 6 for first and last dates.
- Each draft is discarded; both passes end with the Country Days summary at **0 days**. No stay saved.
- The existing rotation regression also passed on this APK: 42 passed, zero failures/errors/skips, 39.8s. The pending November 3 anchor survived both rotations and completed November 3–6.

The initial attempt to run the corrected regression on the previous installation errored at launch because the emulator stopped responding. No acceptance checks ran in that attempt; it is an environment failure, not a failed date assertion. The task-owned foldable was restarted with guest Vulkan enabled, its device-scoped Argent services refreshed, and the fixed APK installed before the successful passes. The previous pass's recorded known-bug replay remains the before-fix behavior evidence.

Data dependencies: English onboarded mapQa, empty country history, open foldable and November 7, 2026 date context. All final replay targets use semantic identifiers/text and selector-based scrolling; no coordinate exceptions. This verifies selection behavior structurally through first/last date labels, not pixel appearance. iOS source was unchanged and iOS tests were not run. Earlier fixes and unrelated workspace edits were preserved.
