# Country stay fix and continued Android QA

**Later update:** The pending-anchor finding below has also been fixed; see [picker fix verification](picker-fix.md). This document preserves the discovery and original verification results.

The unfinished inline date range now survives rotation. `CountryStayEditor.kt` stores the pending anchor as an ISO date string in `rememberSaveable`, alongside the already saved month and form dates.

The corrected regression failed against the old installed APK: after November 3 → rotate landscape and portrait → November 6, `stay-first` contained November 6 instead of November 3. After the fix, the same full path passed: **42 steps passed, zero failures/errors/skips, 35.1 seconds** (43 directives including the echo). It also checks the normal two-tap control and discards the draft. [Before-fix failure](rotation-regression-before-fix.png).

Active regression: `android/.argent/e2e/country-calendar-rotation-regression-20261107.yaml`. The original wrong-behavior diagnostic is archived as `rotation-original-bug-repro.yaml` and is not an active regression.

Validation: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleMapQa` completed successfully. **102 unit tests passed**, zero failures/errors/skips. Updated only the isolated `com.bontecou.whereday.mapqa` installation on `emulator-5562` with `adb install -r`.

## Follow-up finding: P2 applying a picker range retains an old inline anchor

1. Add a country stay and tap November 3 in the inline calendar, starting an unfinished range.
2. Open Choose stay dates, select November 4 and November 5, then Apply.
3. Confirm the form shows November 4–5.
4. Return to the inline calendar and tap November 6.
5. **Actual:** the form jumps to November 3–6, reusing the hidden earlier anchor. **Expected:** a new same-day range on November 6, matching the control without the earlier inline tap.

This can silently add unintended dates if the user saves the draft. No stay was saved during QA. [Incorrect result](stale-anchor-after-picker.png), [control result](picker-control-no-stale-anchor.png).

Cause from code inspection: `CustomDateRange`'s Apply callback updated the parent first/last dates but did not reset the independent pending anchor inside `StayCalendar`. This behavior predated the rotation fix: the original code also retained its plain remembered anchor while the nested dialog was applied. This separate issue is now fixed.

Original recorded diagnostic, now archived: [picker-original-bug-repro.yaml](picker-original-bug-repro.yaml). Full unattended replay passed **71 steps, zero failures/errors/skips, 51.1 seconds** (72 directives including echo). It asserts the correct control and the observed incorrect result, so its PASS confirms reproduction, not correct app behavior. Both drafts are discarded; the final Country Days assertion is **0 days**. Requires English onboarded mapQa, an open foldable, guest Vulkan enabled, and the November 7, 2026 date context.

## Additional exercised cases

| Case | Result |
| --- | --- |
| Apply November 4–5 with no pending inline selection, then tap inline November 6 | Correct November 6-only range; checked in unattended replay |
| Start pending November 6, change nested picker dates to November 4–5, then Cancel | Parent remains November 6-only; checked manually |
| After cancelling that nested edit, choose inline November 3 | Correctly completes reverse-order November 3–6 range; checked manually |
| Discard each modified draft | Returned to zero country days; checked live and in replay |

`picker-raw-exploration.yaml` is a diagnostic transcript, not a portable regression. It includes coordinate taps, transition-time gestures, and one wait that saw a stale tree. Device-scoped service recycling refreshed that tree; the unsuccessful wait is not counted as an app failure. The polished replay replaces those actions with semantic selectors, scroll targets, and screen-settling waits. One Settings idle step warned of residual motion; the following Country Days visibility check passed and the full flow completed.

All testing used the isolated QA package. No owner journal data, permissions, recording, purchases, country analysis, or saved stays were changed. Unrelated workspace edits and earlier fixes were preserved. iOS source was not changed for this fix, so iOS tests were not run.
