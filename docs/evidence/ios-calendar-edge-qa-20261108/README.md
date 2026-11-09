# iOS visit-to-Calendar edge QA — 2026-11-08

Explored the previously untested visit-to-Calendar review flow with Argent on the existing iPhone 18 Pro fixture (iOS 27.0, `9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A`). This pass focused on unsaved visit dates, closed/open-ended transitions, invalid departure times, and snapshot semantics. No calendar events were saved and no fixture visit edits were committed. Verification launches used English and the existing process-only webhook-disabled override.

## Finding: Calendar handoff ignores visible edits

Opened Places → Home → the November 3 visit, then moved its day to November 4 without pressing Save Time Changes. Both clock times correctly moved together and the unsaved-changes button appeared. Opening Calendar review nevertheless showed November 3 for both dates. The handoff constructed its draft from the stored Visit rather than the editor's visible values. Its title had the same dependency on focus-loss saving; current notes already had an explicit override.

Evidence: [edited visit day](visit-unsaved-day.png), [stale Calendar dates before the fix](calendar-stale-before.png).

## Fix

Added a CalendarEventDraft initializer accepting the visit editor's current name, arrival, optional departure, and notes. It preserves the source location metadata while taking a value snapshot of those edits. Titles are trimmed with a fallback to the stored display name when blank. A nil edited departure explicitly means Still here, even if the stored visit was closed, and uses the supplied current time with the existing nonnegative-duration bound. Snapshot creation never saves or mutates the source Visit.

The visit screen uses this initializer for its Calendar action and disables that action while its time validation is failing. The accessibility hint carries the current validation explanation.

## Live verification

- Repeated the November 3 → November 4 unsaved date edit on the rebuilt app. Calendar review showed **November 4, 8:21–9:35 AM**, matching the visible editor. See [fixed dates](calendar-dates-after.png).
- Cancelled Calendar review; the November 4 draft remained in the visit editor.
- Toggled Still here and reopened Calendar review. The edited November 4 arrival was retained and the event ended at the current November 8 time instead of the stored November 3 departure. Cancelled without opening the system save flow.
- Turned Still here off and changed departure to **7:35 AM**, before the **8:21 AM** arrival. The departure validation message appeared, Save Time Changes and Calendar became disabled, and tapping Calendar did not open its review. Live AX checks confirmed the review heading was absent and the validation message remained visible. See [invalid-time handoff blocked](invalid-times-calendar-blocked.png).
- Closed the visit editor without saving. The source list still showed **Tuesday November 3, 8:21–9:35 AM, 1h 14m**.

The edited-title override and source-model immutability were checked with unit tests rather than changing an existing fixture name, because the visit screen normally saves names on focus loss.

## Tests

Used `scripts/test-ios.sh` with normal simulator signing and the explicit device destination.

| Run | Result | Bundle |
| --- | --- | --- |
| New snapshot overload delegating to the original stored-visit behavior | Both new tests failed as expected; existing tests passed | `artifacts/test-results/IsoMe-20261108-081652-83350.xcresult` |
| Final fix plus adjacent remote-visit tests | **24 passed, 0 failures** | `artifacts/test-results/IsoMe-20261108-081753-84958.xcresult` |

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A' \
  -only-testing:IsoMeTests/CalendarEventDraftTests \
  -only-testing:IsoMeTests/RemotePastVisitTests
```

The final run includes 12 CalendarEventDraftTests and 12 RemotePastVisitTests. The two new cases exercise the actual handoff initializer and resulting unsaved EKEvent: edited title/date/note overrides, a 26-hour edited stay, closed → Still here, blank-name fallback, cleared notes, and unchanged original fields. Existing session-calendar and visit-calendar behavior remains covered. `git diff --check` passes. The full suite was not rerun for this pass.

This report claims direct Argent UI verification and signed unit tests, not a saved e2e replay. The previously established native-hierarchy selector mismatch remains a limitation for portable replay. The import-review workflow was inspected in source but was not exercised with a new file in this pass.
