# Google Timeline import MVP QA

Validated on 2026-10-25 with the iOS 26.5 simulator (`iso-me-crash-repro-20261025`).

## Automated coverage

`TimelineImportTests` covers classic raw Location History, legacy Semantic Timeline, device Timeline (object and iOS array), E7/geo/degree coordinates, ISO/millisecond/offset timestamps, invalid records, unsupported files, file size limits, combined iso.me backups, cancellation, selection, read-only preview, duplicate tolerances, stale previews, and repeated imports. Existing visits retain their names and confirmation state. Imports do not request geocoding.

For current verification use `scripts/test-ios.sh -only-testing:IsoMeTests/TimelineImportTests -only-testing:IsoMeTests/ImportReviewValidationTests`; use `scripts/test-ios.sh` for the full suite. See CONTRIBUTING.md for signed simulator requirements and destination overrides. The observations below are historical, not fresh-head verification.

## Manual UI acceptance

Fixture: `IsoMeTests/Fixtures/google-timeline-preview.json` (synthetic coordinates and dates).

1. Save the fixture in Files. Open Settings > Data Import > Import File and select it.
2. Preview identifies Google Device Timeline and shows 1 new visit, 2 new points, 1 duplicate visit, 2 duplicate points, and 1 invalid entry. Nothing is saved yet.
3. Confirm. Completion reports 1 visit and 2 points imported. Settings totals increase by exactly those amounts (6/111 to 7/113 during this run).
4. Select the same file again. Preview shows 0 new visits/points and 2/4 duplicates. Confirmation is disabled.

Observed all expected results. The system file picker did not expose a reliable accessibility tree through Argent; it was checked visually. A draft recorder was discarded instead of keeping brittle selectors. Automated UI replay is not claimed.

## MVP boundaries

At the October 25 validation, Google imports accepted single JSON files only. The November 13 follow-up adds combined Google multi-file preview/commit; see [the checklist audit](google-timeline-import-audit-20261113.md) for current coverage and remaining gaps. Unzip Takeout manually. No folder recursion, direct ZIP import or inferred visits from raw points. Ordinary selections accept up to 50 MiB and 250,000 visits plus points total. Duplicate thresholds and privacy behavior are documented in the README. New interface copy currently uses English fallback in other locales.


## Expanded QA run

Repeated manual acceptance on implementation commit `e53d328` with synthetic files in Files > On My iPhone > Downloads.

| Scenario | Observed result |
| --- | --- |
| Fresh device export preview | 1 new visit, 2 new points, 1/2 in-file duplicates, 1 invalid record |
| Both selection toggles off | Selected counts become zero; confirmation visibly disabled |
| Cancel preview | Settings totals unchanged at 7 visits / 113 points |
| Reopen canceled file | 1 visit and 2 points remain available; options reset to on |
| Import with Visits off | Completion reports 2 points; totals become 7 visits / 115 points |
| Reopen after points-only import | 1 visit still available; 0 new points and 4 duplicate points |
| Raw Location History | Correct format; 0 visits / 1 new point; Visits toggle remains off when tapped |
| Malformed JSON | Import Failed message; no confirmation action; Cancel works |
| Recover after malformed JSON | Valid file previews successfully with the expected remaining visit |

No product defects were found in these checks. The exploratory Argent record lives in `/private/tmp/isome-google-import-qa/`; its system-picker selectors are unreliable, so it is not a verified automated UI test. Checks used screen inspection and accessibility descriptions.

The first automated test attempt was interrupted by starting UI automation before the runner finished. It is excluded from the verdict. The independent rerun after UI automation and instrumentation stopped passed all 313 tests with zero failures. Log: `/private/tmp/isome-google-import-qa-final.log`.

Xcode stalled while finalizing the result bundle after the complete 313-test pass summary. The runner was stopped with SIGINT after roughly two minutes. Test-case results above passed, but a successfully finalized `.xcresult` bundle is not claimed.
