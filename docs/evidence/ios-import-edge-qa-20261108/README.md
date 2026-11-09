# iOS import-review edge QA — 2026-11-08

Used Argent on the existing iPhone 18 Pro fixture, iOS 27.0, UDID `9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A`. Synthetic files were downloaded through Safari from a temporary loopback HTTP server and selected through Whereday's actual file picker. The server has been stopped. Copies of the four files are in [fixtures](fixtures); downloaded copies remain in the simulator's On My iPhone → Downloads for reuse.

No imports were committed to the simulator. Settings still reports **132 visits and 111 points**, and Places still had **6 saved places** after cancelling its CSV review. Existing Android and other unrelated changes were left untouched.

## Bugs fixed

### Malformed departure dates become ongoing visits

[qa-bad-departure.json](fixtures/qa-bad-departure.json) has one valid closed visit and a second with `departedAt: "not-a-date"`. Before the fix, review offered **2 new visits** without an invalid-entry warning. The parser discarded the invalid departure value and interpreted the visit as open-ended.

The same pattern existed in Whereday CSV: a nonempty invalid `departed_at` value became nil. The parser now rejects malformed departure values with a visit-index or CSV-row error. Missing, null, and empty JSON departures, and missing/empty CSV departures, retain their existing open-visit meaning. Required fields already fail the whole Whereday file on malformed input; these optional-but-present timestamps now follow that policy.

Argent verified the original downloaded JSON after rebuilding: review shows **IMPORT FAILED · NOTHING WAS SAVED** and `Visit at index 1: invalid departedAt date`, with no import action.

Evidence: [accepted before](bad-departure-accepted-before.png), [rejected after](bad-departure-rejected-after.png). CSV malformed-departure behavior is covered by the regression test.

### JSON Booleans masquerade as numeric fields

[qa-boolean-coordinate.json](fixtures/qa-boolean-coordinate.json) uses `latitude: true`. Before the fix, review offered **1 new visit** because Foundation bridges JSON numbers and Booleans through NSNumber; the cast produced latitude 1.

Numeric JSON fields are now checked before casting. Boolean and mistyped values are rejected for primary visit/point coordinates, Unix timestamps, accuracy, altitude, speed, and correction-audit coordinates/distances. Genuine Boolean fields such as `isOutlier` remain supported. Null optional numeric fields remain supported.

Argent verified that the same file now fails with `Visit at index 0: latitude must be a number`, instead of offering a fabricated-coordinate visit.

Evidence: [accepted before](boolean-coordinate-accepted-before.png), [rejected after](boolean-coordinate-rejected-after.png).

## Other live checks

### Mixed Google history and type selection

[qa-mixed-google.json](fixtures/qa-mixed-google.json) contains one valid point, its exact duplicate, an out-of-range coordinate, and a NaN timestamp string. Review correctly reports **1 new point, 1 duplicate, 2 invalid entries**, with no visits inferred.

Turning points off displays No new records selected and disables Import. Tapping the unavailable visits control does not enable it, and tapping the disabled import action does not advance the flow. Cancelling preserves history. See [disabled selection](google-mixed-disabled-selection.png).

After both fixed error states, Choose another file reopened the actual Downloads picker. Selecting the other malformed file showed its own error; selecting this valid mixed Google file then restored the expected 1/1/2 preview. No stale error state or unusable picker remained.

### Mixed saved-place CSV, multiline fields, duplicate policies

[qa-places-mixed.csv](fixtures/qa-places-mixed.csv) combines quoted multiline names/addresses, a duplicate within the same file, an invalid latitude, and NaN radius.

Keep existing locations correctly reports **2 new, 0 updated, 1 skipped duplicate, 2 invalid rows**. The multiline name/address remains readable, and invalid rows carry field-specific errors. Update matching locations changes the plan to **2 new, 1 update, 0 skipped, 2 invalid**, reflecting a later row updating an earlier incoming location. Cancelling leaves the saved-place count unchanged.

Evidence: [mixed CSV review](csv-mixed-review.png). This review required no code change.

## Automated verification

All iOS runs used `scripts/test-ios.sh` with normal simulator signing and the explicit device destination.

| Run | Result | Artifact |
| --- | --- | --- |
| New tests against original parser | 3 tests failed as expected (19 assertions), 1 compatibility test passed | `/private/tmp/whereday-import-review-red.log` |
| Fixed validation + timeline, saved-place CSV, and backup tests | **32 passed, 0 failures** | `artifacts/test-results/IsoMe-20261108-083511-27910.xcresult` |
| Existing export/import round trips | **12 passed, 0 failures** | `artifacts/test-results/IsoMe-20261108-083621-33222.xcresult` |

Final total: **44 targeted tests passed**. The four new ImportReviewValidationTests cover malformed JSON/CSV departure values, Boolean numeric fields, legitimate null/empty/open values and true Boolean flags, and rejection before a mixed-file preview can offer invalid records while preserving existing history. The round-trip tests verify compatibility with files produced by the app's supported export formats. `git diff --check` passes. The full suite was not rerun.

The initial failing run completed its four test cases but its Xcode process did not exit; that stale runner was stopped after the subsequent successful runs completed. Its requested result-bundle path was `artifacts/test-results/IsoMe-20261108-083412-21762.xcresult`; use the red log for the recorded failures rather than assuming that interrupted bundle finalized normally.

This report claims live Argent review checks and signed automated tests, not a portable saved e2e replay. The native-hierarchy selector mismatch documented in earlier passes remains a limitation for saved replay. Storage commits were tested in isolated test stores; malformed files were never imported into the simulator's existing history.
