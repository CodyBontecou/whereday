# Issue 59 checklist audit — November 13, 2026

This is the **first-wave snapshot**. The subsequent [inference slice](google-timeline-inference-20261113.md) supersedes its inference/accuracy/size-label gaps; folder discovery remains unimplemented. Historical results below retain their original source boundary.

Baseline: `f003fe3e50e8066c0ca263b69d2f29ab6392d1a2` (merged PR 112). Read the complete issue 59 and PR 112 bodies using read-only `gh` commands. This is an iOS slice, not epic completion or Android parity. Source/test presence is distinguished from runtime verification; October QA and PR 112 CI results are historical, not reruns at this head. The fleet report records exact fresh commands and results.

## Requirement-to-artifact audit

Legend: **Implemented** = source inspected with tests where named; **Partial** = not the full proposed checklist; **Missing** = no implementation found. These labels do not claim physical-device acceptance.

| Phase / checklist item | Status and concrete evidence |
| --- | --- |
| 0: import-specific detection | Implemented: `TimelineImportService.parse`, `ImportSourceFormat`, `ImportPayload`, `ImportOptions`, `ImportPreview`, `ImportCommitResult`; `ImportService` remains the legacy codec. |
| 0: combined JSON imports both | Implemented: `ImportService.importFile` handles both root arrays; `TimelineImportTests.testCombinedIsoMeBackupImportsBothVisitsAndPoints`. |
| 0: preserve JSON/CSV/Markdown | Implemented codecs: `ImportService`; `ExportServiceRoundTripTests` covers all three plus correction metadata. Fresh targeted verification recorded separately. |
| 0: unsupported KML/GPX/GeoJSON copy | Implemented: `ImportError.unsupportedFormat` explicitly lists one-way formats; `testUnsupportedMalformedAndEmptyFilesDoNotProducePreviews`. |
| 0: combined regression / acceptance | Test above exists; `WheredayBackupTests` separately covers full six-model single/multipart backups. Full suite not implied by targeted checks. |
| 1: Google parser | Implemented: `GoogleTimelineImportService.swift`. |
| 1: raw locations detection | Implemented: `TimelineImportService.parse`; `testRawGoogleCoordinatesTimestampVariantsAndUnknownAccuracy`. |
| 1: semantic timelineObjects detection | Implemented: same detector; `testSemanticVisitsAndTimestampedPaths`. |
| 1: device semanticSegments detection | Implemented, including root arrays: `testDeviceTimelineSupportsGeoDegreeCoordinatesAndPathOffsets`, `testIOSDeviceVisitAcceptsStringPlaceLocationAndRejectsReversedTimes`. |
| 1: E7 conversion | Implemented: `GoogleTimelineImportService.coordinate`; raw/semantic tests above. |
| 1: geo coordinates | Implemented: `coordinate` accepts `geo:` and degree strings; device tests above. |
| 1: timestamp variants | Implemented: `date`, `milliseconds`, `appendPath` (offset minutes); raw/semantic/device tests above. |
| 1: raw normalization to points | Implemented: parser preserves accuracy, altitude and nonnegative velocity; raw test above. |
| 1: semantic/device normalization to visits | Implemented: `appendVisit`, source/place source set to import; semantic/device tests above. |
| 1: inline fixture tests / acceptance | Implemented: `TimelineImportTests` parses without persistence. Synthetic fixture also in `IsoMeTests/Fixtures/google-timeline-preview.json`. Not universal Google-schema support. |
| 2: VisitInferenceService | Missing: no service or inferred raw visits. Raw-only preview explicitly says so. |
| 2: configurable dwell radius/minimum/accuracy | Missing. `ImportOptions` contains only visits/points selection. |
| 2: suppress inference over semantic ranges | Missing because there is no inference. |
| 2: stationary/moving/sparse/merged/teleport tests | Missing for inference. No claim that imported raw points apply the proposed 40 m/s outlier heuristic. |
| 2: deterministic inferred visits acceptance | Unmet. Raw-only imports remain points-only. |
| 3: ViewModel preview/commit methods | Implemented: `LocationViewModel.prepareImport(from:)`, `commitImport`; worker actor owns contexts. No wholesale ViewModel edit in this slice. |
| 3: within-payload deduplication | Implemented: `makePlan` buckets and distance checks; `testPreviewIsReadOnlyAndRepeatImportSkipsDuplicates`, point/visit tolerance tests. |
| 3: existing rows fetched by date range | Implemented: `makePlan` uses range predicates with tolerance margins; new multi-file sort regression protects chronological endpoints. |
| 3: insert in batches | Partial: cancellation/progress checks every 500 records, but all insertions are saved once intentionally for atomicity. Not bounded-memory/streaming persistence. |
| 3: refresh caches + Watch/widget | Partial source evidence: `commitImport` clears loaded points, calls `loadData` and `syncDataToWatch`; `loadData` reloads ViewModel collections. No explicit widget refresh in this method and no fresh Watch/widget runtime check. |
| 3: repeated in-memory SwiftData tests / acceptance | Implemented: repeat/stale preview tests and new cross-file repeat test; existing records never overwritten. |
| 4: Settings async preview/confirm | Implemented: `.sheet` uses `ImportPreviewView`; `.task` calls async ViewModel method. Preview is read-only. |
| 4: ImportPreviewView | Implemented: reading/ready/saving/complete/failed states, detected format/range/counts/warnings. |
| 4: all proposed options | Partial: visits/points toggles only. Duplicates always skipped. No inference toggle, duplicate override or accuracy threshold. |
| 4: progress + completion | Implemented: `ImportProgress`, `savingBlock`, completion counts/duplicates. Stages are coarse; not separate scanning/parsing/deduping counters. |
| 4: file/folder picker types | Partial: `SettingsView` accepts JSON/CSV/Markdown and multiple selection; no folder type. |
| 4: KML picker removal | Implemented: KML absent from allowed types. |
| 4: counts shown before saving acceptance | Source and in-memory preview tests support this; October manual QA is historical. No new UI/device verification in this slice. |
| 5: multiple Google JSON selection | Added in this slice: `TimelineImportService.preview(urls:)` aggregates supported Google families into one preview, retains warnings, rejects mixed Whereday selections. Existing picker already enables multiple selection. |
| 5: folder selection | Missing. |
| 5: recursive discovery | Missing. |
| 5: ignore unrelated Takeout files | Missing automatic filtering: explicitly selected unrelated/empty/malformed files reject the entire selection, rather than silently disappearing. Documented in README. |
| 5: combine semantic visits and raw points | Added bounded aggregation and chronological sort; `testMultipleGoogleFamiliesShareOneReadOnlyPreviewAndRepeatSafeCommit`. No inference needed/invented. |
| 5: avoid duplicate raw/semantic paths | Existing point matching now spans all selected files (rounded second and <=10m), with mixed-family regression. Approximate matching is not source-ID reconciliation. |
| 5: multi-file aggregation tests | Added mixed-family/read-only/reimport, reversed file order/existing rows, invalid selection, aggregate byte and record bounds. Existing `WheredayBackupTests` protect multipart sets. |
| 5: unzipped folder one-confirmation acceptance | Unmet for folders. Manually selected supported JSON files share one confirmation. |
| 6: dedicated ZIP decision issue | Not created: remote mutation forbidden in this lane; no existing decision issue inferred. |
| 6: option A unzip workflow | README documents tapping ZIP in Files before selecting JSON. Picker only allows supported file types. Direct `parse` ZIP requests are rejected. |
| 6: options B/C ZIP dependency/reader | Deliberately absent; no dependency or ZIP reader added. |
| 6: ZIP acceptance | Partial: docs instruct unzip first; UI does not offer ZIP or an explicit unzip-specific error. No direct ZIP support. |
| 7: README instructions | Updated Google import subsection for multi-file selection, budgets, rejection semantics and unzip workflow. |
| 7: supported/unsupported formats | README and unsupported-format error document families and one-way formats; no KML or universal Google-schema promise. |
| 7: troubleshooting huge/unknown/no inference/duplicates | README covers 50 MiB/250,000 selection limits, malformed/empty files, always-on approximate dedupe and points-only raw imports. |
| 7: offline/privacy | Parser uses Foundation only; persistence uses local SwiftData/CoreLocation distance, sets `geocodingCompleted: true`; test `testSelectedTypesOnlyAndImportedVisitNeverNeedsGeocoding`. No OAuth/API/upload/geocoder added. No packet-capture or unrelated app network behavior claim. |

## Bounded change and limits

The highest-priority bounded gap chosen is multi-file Google aggregation: the picker already permits multiple files for multipart backups, but the baseline worker rejects every multi-file non-backup selection. The fix reuses the existing duplicate planner, commit transaction and review screen rather than adding a competing import path. The aggregate ordinary selection is capped at 50 MiB and 250,000 normalized visits/points before duplicate elimination; each Google source/path array retains its existing record cap. Full-model multipart backups retain their separate complete-set validation and are not subject to the aggregate ordinary-selection cap.

Remaining large-data limits: whole JSON deserialization, retained normalized arrays and duplicate indexes are not streaming. A byte/record cap is not an on-device memory/latency benchmark. The review screen's size label still reports the first file, while the filename list/counts/range represent the aggregate. Folder discovery, inference, accuracy/outlier filtering and full stage progress remain separate follow-ups. Keep original files; no completeness, recovery, lossless universal migration or epic-complete claim.

## Integration warning

`android/scripts/generate_google_timeline_fixtures.py` and `generate_timeline_duplicate_fixtures.py` fingerprint the entire Swift worker, even when the extracted parser/match bodies do not change. The coordinator explicitly authorized this lane to regenerate these two fixture outputs with the actual Swift producers. Both producers ran successfully; their 50 Google cases and 50 duplicate-boundary cases remain structurally identical to baseline. Only provenance changes. The coordinator must rerun the actual producers after any integrated shared-source edits and review generated differences; do not hand-edit hashes or weaken guards. Other lanes changing the same worker or `ImportSourceFormat` must reconcile serially. No Android implementation is changed here.

## Fresh verification

All heavy commands acquired `/tmp/whereday-fleet-20261113/heavy.lock` atomically and released only this lane's lock in a trap. The signed runner's default selection exited 2 before building: installed SDK 27.2 has no matching simulator. Per CONTRIBUTING.md, the explicit available iOS 27.0 destination succeeded:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=E96738E5-A399-4E1B-96BD-770C2293261A' \
  --result-bundle-path /tmp/whereday-fleet-20261113/google-import-tests-override.xcresult \
  -only-testing:IsoMeTests/TimelineImportTests \
  -only-testing:IsoMeTests/ImportReviewValidationTests \
  -only-testing:IsoMeTests/ExportServiceRoundTripTests \
  -only-testing:IsoMeTests/WheredayBackupTests
```

Exit 0: **48 tests, zero failures** (18 TimelineImport, 4 ImportReviewValidation, 12 ExportServiceRoundTrip, 14 WheredayBackup). This includes six new multi-file tests, aggregate byte/record rejection, repeated imports, combined legacy JSON, all three legacy codecs and full-model multipart behavior. Result bundle finalized successfully. DerivedData was worktree-specific: `IsoMe-ddabxcenyrgroebifefmeebghpvj`. No UI, physical-device, full-suite, memory benchmark, network capture or inference acceptance is inferred.

Also passed:

- `python3 -m unittest discover -s scripts -p test_test_ios.py` — exit 0, 14 runner-guardrail tests.
- `python3 android/scripts/generate_google_timeline_fixtures.py` — exit 0, 50 actual Swift parser references.
- `python3 android/scripts/generate_timeline_duplicate_fixtures.py` — exit 0, 50 actual CoreLocation duplicate references.
- Both commands with `--check-source` — exit 0 after regeneration. Before regeneration both correctly failed with exit 1 on source drift.
- Independent JSON comparison against baseline `git show HEAD:<fixture>` — all 100 input/expected cases unchanged.
- `git diff --check` — exit 0.

Generated SHA-256:

| Output | SHA-256 |
| --- | --- |
| `android/export/src/test/resources/ios-google-timeline.json` | `008918e1363b466e9009dd95f252e37c8881200992745254ee870a93cd681f10` |
| `android/domain/src/test/resources/ios-timeline-duplicates.json` | `3560826019441750217aa5cf45b35341f13eeef434055615b8aa9aad26b54e36` |
