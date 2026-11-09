# Manual visit interruption QA — November 7, 2026

**Update:** The saved-place chooser rotation bug is now fixed. See [fix verification](chooser-fix.md). This document preserves the original QA findings.

Continued Android QA on the isolated `com.bontecou.whereday.mapqa` app, open `whereday-m3-fold-api35` emulator (`emulator-5562`). No source code changed and no visit or saved place was saved.

## New P2: rotating the saved-place chooser dismisses it and loses search

1. Map → Add a place → Log a past stop.
2. Enter a draft name, then open Choose saved place.
3. Enter a search query (`missing place QA` in this test).
4. Rotate to landscape.
5. Actual: the chooser closes and the parent visit editor appears. The parent name survives.
6. Rotate back and reopen Choose saved place: the query is empty.

Expected: remain in the chooser with the search query intact, as with the other saved editor and picker state. This interrupts selection and discards the user's search, but does not lose the parent visit draft. [Result after rotation](saved-chooser-dismissed-on-rotation.png).

Code inspection supports the cause: `VisitEditor.kt` declares `savedChooser` with plain `remember` at line 85, so recreation restores the editor fields while resetting the flag that presents `SavedPlaceChooser`.

Historical semantic reproduction, now archived: [saved-chooser-original-bug-repro.yaml](saved-chooser-original-bug-repro.yaml). Full unattended Argent MCP replay: **40 passed, zero failures/errors/skips, 29.4 seconds** (41 directives including the echo). This diagnostic deliberately checks the observed incorrect dismissal and blank reopened query. Its PASS confirms reproduction, not correct app behavior. The search is shown before the hidden check; the parent editor and draft name are checked after rotation. The final steps discard the draft and assert Map shows “No places yet.”

Prerequisites: English onboarded mapQa, open foldable and empty journal. No hardcoded serial is stored in the YAML; supply the device at execution. No coordinate or raw-swipe exceptions in the polished reproduction. It uses semantic taps, recorded keyboard input, and identity checks followed by idle gates. No warnings in the final replay.

## Other checks

| Case | Observed result | Evidence scope |
| --- | --- | --- |
| Manual visit latitude `NaN`, longitude `Infinity`, Save | Rejected with “Choose a location or enter valid coordinates.” | Live inspection and recorded text assertion after scrolling to the error |
| Select November 3 in arrival-date picker, rotate landscape and portrait | Selected November 3 retained; Apply updates the parent arrival date correctly | Live tree inspection; raw trace |
| Parent visit fields across rotation | Name and non-finite coordinate drafts retained | Live inspection; name also asserted in unattended replay |
| Back from a dirty visit | Discard confirmation displayed; discarding returns to empty Map | Live and unattended replay |
| Emoji/CJK text input via Argent keyboard | Tool rejected the input before typing | Automation limitation; not counted as an app bug or tested Unicode support |

The invalid-coordinate feedback is below the initially visible portion of this long form. It was located by scrolling; this pass does not treat that as a second confirmed defect.

`raw-exploration.yaml` includes one failed empty-search wait caused by a stale accessibility tree after reopening the chooser. Refreshing only this device's Argent services exposed the empty field and a subsequent recorded check passed. The focused unattended reproduction excludes this ambiguity and confirms the behavior through the runner's tree. It also excludes the initial unsupported Unicode attempt and broad timestamp/validation exploration. Rotation actions in the raw exploration were issued without settle gates between them; the timestamp observation is a manual check, not a portable automated regression.

No location permissions, recording, network analysis, purchases, app data, owner package, or owner phone were changed. The task's reusable emulator services remain available. Unit and iOS tests were not run because this pass changed no app source.
