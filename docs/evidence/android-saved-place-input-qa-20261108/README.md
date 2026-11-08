# Android saved-place input QA — 2026-11-08

Found and fixed a saved-place editor crash through live Argent exploration on `emulator-5554` (`whereday-redesign-api35`, API 35). Only the isolated `com.bontecou.whereday.mapqa` package was used. The owner app was untouched; the QA APK was updated with `adb install -r`.

## Crash and fix

Open Places → Add place, clear the default `150.0` radius, then enter `NaN`. Before the fix, the app crashes immediately, before Save. The captured [crash log](crash-log.txt) reports `IllegalArgumentException: Cannot round NaN value` in Material3 Slider measurement. The [raw reproduction](raw-crash-reproduction.yaml) retains the original failed check after the crash.

`PlaceEditor.kt` now permits only finite parsed values to drive the slider. The raw text stays editable, while the existing saved-place validation rejects invalid radii on Save. This also safely handles `Infinity` and exponent overflow (`1e309`).

## Coverage

- Original NaN crash path remains in the editor on the fixed build.
- With a valid name and coordinates `(0, 0)`, NaN, Infinity, and `1e309` remain editable and Save shows the validation error without closing the editor.
- Cancel opens the discard confirmation; discarding returns to the existing single saved place.
- Search `%_` is treated literally and produces no matches. Clear search restores the original list.

The final reusable [Argent regression](../../../android/.argent/e2e/saved-radius-nan-regression-20261108.yaml) was assembled from the recorded crash path and [fixed-build continuation](raw-fixed-continuation.yaml). All interactions were recorded before polishing. The YAML quotes `1e309` so it remains text rather than numeric infinity. No scripts, fixture writes, or data clearing are used.

## Verification

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleMapQa` passed. App unit results: 102 tests, zero failures/errors/skips.
- Two consecutive full Argent replays passed all 58 executable steps (59 entries including echo) in 59.8 s and 48.7 s; see [replay 1](replay-1.txt) and [replay 2](replay-2.txt).
- `git diff --check` passed.

The existing Unicode saved-place record (ID `979264b0-0b82-4d4d-bdf0-695b5b956ff2`, 25 m radius) remains; the final list shows `1 of 1 saved place`. Drafts were discarded. No successful saves, deletes, imports, route-history mutations, or permission changes were performed. The preexisting QA journal contained 25 points and one visit; journal contents were not re-counted after the pass.

Argent briefly lost the device service connection during installation; recycling services scoped to this device restored access. A blinking text caret also produced an idle warning; explicit editor, error, and list assertions gate the relevant transitions. No iOS code changed and no iOS tests were run.
