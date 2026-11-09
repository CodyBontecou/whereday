# Saved-place chooser rotation fix

Changed `VisitEditor.kt`'s `savedChooser` visibility flag from `remember` to `rememberSaveable`. Restoring the flag keeps the chooser present during activity recreation, allowing its existing saveable search query to restore too. Dismissal and selection behavior are unchanged.

## Verification

The corrected regression failed against the previous APK because no `saved-chooser-search` element existed after the rotations. [Before-fix screenshot](chooser-regression-before-fix.png). This is a real failed acceptance assertion, distinct from the historical diagnostic that asserted the wrong behavior.

Updated isolated mapQa with `adb install -r`. After the emulator restart described below, the same regression passed **twice consecutively** with no changes or manual resets between runs:

| Run | Result |
| --- | --- |
| Fixed APK, first run | 35 passed, 0 failed/errored/skipped, 29.0s |
| Fixed APK, immediate second run | 35 passed, 0 failed/errored/skipped, 30.6s |

There are 36 directives including the echo. Both runs enter a parent visit name, open the saved-place chooser, enter a search query, rotate landscape and portrait, assert that the query remains, dismiss the chooser, assert the parent name remains, discard the draft, and assert the Map still shows “No places yet.” No visit was saved.

Active test: `android/.argent/e2e/manual-visit-saved-chooser-rotation-regression-20261107.yaml`. Runner: Argent MCP `e2e-execute`, explicit `emulator-5562`. Prerequisites: English onboarded empty mapQa, open foldable. Device-scoped services refreshed before the first successful pass. Semantic targets throughout; no coordinate or raw-gesture exceptions. The second run reported residual motion in one idle gate after name entry; the name and subsequent chooser state were verified explicitly rather than inferred from stillness.

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleMapQa` succeeded. **102 unit tests passed**, zero failures/errors/skips. `git diff --check` passed. No iOS source changed; iOS tests were not run. Unrelated workspace edits and earlier fixes were preserved.

## Verification interruption retained separately

Before restarting the emulator, one fixed-APK run stopped during visit-name typing with Android's “app isn't responding” dialog, and the following attempt timed out at app launch. Neither reached the chooser rotation assertions. [Screenshot](verification-interrupted-by-anr.png), [Android last-ANR diagnostic](verification-lastanr.txt). The latter is the device's reported last ANR snapshot and is not sufficient to establish the cause of this interruption.

Closed the stalled app, restarted only this task's `whereday-m3-fold-api35` emulator with guest Vulkan enabled, refreshed its scoped Argent services, then obtained the two passes above. No app data was cleared. The ANR cause remains unestablished; the successful reruns establish the chooser fix, not resolution of every possible responsiveness problem.
