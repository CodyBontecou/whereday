# Android saved-place CSV review rotation

Confirmed on the isolated `com.bontecou.whereday.mapqa` app, API 35 `whereday-redesign-api35` / `emulator-5554`. Existing journal: 25 points, one visit and one saved place. Owner package, iOS files and other agents' devices were untouched.

## Bug and fix

Import the synthetic [CSV fixture](whereday-saved-odd.csv), choose Update matching places, and rotate. The completed review silently disappears and the library replaces it. A fresh accessibility tree showed the library; the recorded `saved-csv-counts` check failed with an unmet condition. The [raw trace](raw-rotation-failure.yaml) also retains an earlier stale discovery read that briefly reported the old review before the fresh read disproved it.

The parsed preview used Compose `remember`, which is lost on Activity recreation. `SavedPlaceImportReview` now owns the completed review in the existing process-level `AppGraph`. CSV rows stay outside saved-state Bundles. Saved picker tickets fence stale reads/cancellations; discard, successful import and journal clearing invalidate the retained review. Confirmation remains an explicit user action. This retains completed reviews through configuration changes and tab navigation; it does not promise durability across process death or continuation of an interrupted file read.

## Verification

- `./gradlew :platform:testDebugUnitTest --tests com.bontecou.whereday.platform.SavedPlaceImportReviewTest :export:test :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleMapQa` exited 0 in 8m48s in the coordinated native slot; [build log](build-verification.txt). The two retained callback-fence tests, 102 app tests, 385 domain tests and 943 export tests passed, zero failures/errors/skips. The run also included a redundant retention check, removed once actual live rotation coverage was established; the two retained methods are unchanged. Lint and QA APK build passed.
- QA APK installed with `adb -s emulator-5554 install -r`, preserving data.
- Fixed-build live/prefix checks retain two valid/two invalid rows and the update policy through landscape/portrait. Navigation to Archive and back retains the review; Archive still shows 25 points. Cancelling the import confirmation returns to review. Discard returns to the original one-place library. No import commit was performed.
- The [full regression](../../../android/.argent/e2e/saved-csv-rotation-herdr-20261108.yaml) also checks BOM/Unicode/multiline quoted CSV parsing, duplicate keep/update plan summaries, confirmation cancellation and cleanup. It requires English, the existing single saved place and the fixture visible in Android Recent documents. Polished selectors contain no raw coordinates or device IDs.
- `git diff --check` passed. No iOS tests were run for this Android-only change.

Two consecutive full runs of the unchanged 48-entry YAML passed all 47 executable actions/checks, zero failed/errored/skipped, in 62.0 s and 44.0 s. Pass 1 started with freshly recycled services scoped only to this device; pass 2 followed immediately without manual state changes. Neither had warnings. See [replay 1](replay-1.txt) and [replay 2](replay-2.txt). An earlier full attempt stopped at an unreadable helper tree after CSV selection ([diagnostic trace](replay-unreadable.txt)); no acceptance verdict was claimed for that attempt, and the same checks remained unchanged for the successful runs.

The old-APK full replay could not launch because the emulator's package-manager resolution timed out; it is not an automated product-red result. The recorded live rotation failure is the pre-fix evidence. The broader Herdr QA objective remains active; this regression is not a broad clean sweep.

## Environment limits

Before this scenario, repeated emulator ANRs and unreadable Argent trees blocked testing. Device-scoped service refreshes, ANR/thread evidence and data-preserving same-AVD restarts were used. A temporary boot with guest Vulkan disabled caused the pinned MapLibre renderer to reject launch; original guest Vulkan was restored. JDI capture resumed/detached normally and its exact temporary forward was removed. The captured main thread was idle, so the earlier ANRs are not attributed to an app-code deadlock. Unverified scenarios and aborted checks remain recorded in the ignored session ledger rather than counted as passing.
