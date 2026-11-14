# Google raw-point visit inference — November 13, 2026

Follow-up to `a4bf13af` (multi-file Google import). This is a bounded Issue #59 Phase 2 / review-options slice, not epic completion or release/device acceptance. The [first-wave audit](google-timeline-import-audit-20261113.md) remains a historical checklist snapshot.

## Algorithm and boundaries

`VisitInferenceService` is pure Foundation geometry over imported observations, with deterministic timestamp/coordinate/accuracy ordering. No live tracking, database, clock, network, geocoding or stored schema is added to the service.

- Default radius 150 m, minimum dwell 900 seconds, accepted accuracy 100 m, maximum gap 300 seconds. Valid settings: radius 25–500 m, dwell 300–7,200 seconds, accuracy 1–500 m, gap 30–900 seconds and no longer than dwell. Nonfinite/out-of-range settings throw before planning or saving.
- At least four distinct timestamps are required. Equal-time duplicates cannot increase confidence; conflicting same-time positions break continuity.
- Only original raw Google points can seed inference; semantic activity endpoints never do. Unknown/poor/nonfinite accuracy, invalid coordinates/dates, flagged outliers, gaps and implied speed above 40 m/s break evidence. A teleport endpoint cannot seed a new dwell.
- A candidate must stay within radius of its anchor and inverse-accuracy spherical centroid, last at least the configured duration, and have endpoint displacement at most half the radius. A 1 m weight floor avoids infinite weights at reported zero accuracy. Spherical geometry handles the date line.
- Adjacent qualifying dwells merge **pairwise only** in one uninterrupted session, with gap <=120 seconds (and configured max gap), centers <=half radius apart, every observation within radius of the combined centroid, and combined endpoint displacement <=radius. Rejected movement evidence is a merge barrier. No transitive chaining or repeated scans of an ever-growing merge list.
- Arrival and departure are actual accepted sample timestamps, not interpolation or extrapolation. Inferred names/addresses are absent. Candidate coordinates are estimates; inference cannot distinguish every small movement from a stop and can miss real visits.
- Selected semantic visit windows exclude inference evidence and reject spanning candidates. The worker separately fetches overlapping existing visits, including visits beginning before the raw range. Nonmatching overlaps suppress inferred candidates; heuristic exact repeats reach the usual deduper so reimport counts remain meaningful. Coverage is merged/indexed rather than doing an all-visits scan per candidate.

## Defaults, accuracy and review integrity

`ImportOptions.inferVisits == nil` means on **only** for raw-only Google payloads. Mixed/semantic/device selections default off. Explicit opt-in to mixed Google input uses only its raw subset and still protects semantic windows. Old JSON/CSV/Markdown and full-model backup restoration do not infer or apply Google accuracy filtering.

For Google routes, measured accuracy worse than the threshold is excluded unless **Include poor-accuracy route points** is enabled. **Unknown accuracy (`-1`) is intentionally retained for routes in both toggle states**, preserving unmeasured history; it is always excluded from inference. This is not a defect/poor-fix opt-in bypass. Nonfinite accuracy is invalid, not unknown or opt-in data. Preview separately exposes `filteredPointCount`, `unknownAccuracyPointCount`, `inferredVisitCount` (candidates before duplicate matching), and existing-window suppression count. Invalid source records retain the existing warnings.

The review screen adds opt-out, radius/dwell/accuracy controls and explicit policy copy using existing semantic styling plus native controls. Inference/accuracy changes asynchronously recompute a **read-only** review from the original normalized payload. Stale results are canceled/discarded; confirmation is disabled while reviewing or after an error/policy mismatch. Worker commit independently rejects an unreviewed policy change and rechecks storage. Include-visits/include-points selection remains independent. The existing one-save transaction and rollback/cancellation path are unchanged. No arbitrary skip-duplicates override was added.

The standard preview's displayed file size now uses aggregate bytes carried by the worker, not first-file filesystem metadata. Model schemas, tracking and Watch code are unchanged. `LocationViewModel` changes are limited to one four-line import-review delegation method.

## Fresh tests and receipts

Final source passed the signed required runner, using an existing available test simulator and the worktree's existing isolated DerivedData. A PATH shim adds `-jobs 2` and the **same existing** `IsoMe-ddabxcenyrgroebifefmeebghpvj` derived path; no tracked runner or signing guard was altered.

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=E96738E5-A399-4E1B-96BD-770C2293261A' \
  --result-bundle-path /tmp/whereday-fleet-20261113/google-import-wave2-tests-final.xcresult \
  -only-testing:IsoMeTests/VisitInferenceTests \
  -only-testing:IsoMeTests/VisitInferenceImportTests \
  -only-testing:IsoMeTests/TimelineImportTests \
  -only-testing:IsoMeTests/ImportReviewValidationTests \
  -only-testing:IsoMeTests/ExportServiceRoundTripTests \
  -only-testing:IsoMeTests/WheredayBackupTests
```

**Exit 0: 74 tests, zero failures.** Counts: 16 pure inference + 10 inference integration + 18 TimelineImport + 4 review validation + 12 legacy round-trip + 14 full-model backup tests. Log: `/tmp/whereday-fleet-20261113/google-import-wave2-tests-final.log`; finalized result bundle above. All fixtures are synthetic.

New tests cover stationary/timestamp ordering/determinism, movement, sparse/repeated/conflicting samples, teleport/outliers, long gaps, poor/unknown/nonfinite accuracy, configurable/invalid options, inverse weighting, valid merges/different places/discarded excursions, date-line centroid, semantic overlap, cancellation, raw-only defaults, opt-out/read-before-policy-change, repeat dedupe, read-only preview, aggregate size, existing semantic windows beginning earlier, inferred-only saves/no geocoding, route accuracy opt-in, and unchanged legacy point imports. Existing signed tests preserve six-model multipart backups including large journals and malformed sets. No new mid-save failure-injection test or measured memory/latency acceptance is claimed.

Historical iterations are retained, not hidden: first run executed 70 tests with one failing new merge test; a targeted corrected fixture then demonstrated two dwells failed to merge (one test/two assertions). The combined endpoint rule had made valid merging unreachable. The fix retains tight individual dwells but allows up to one radius of combined endpoint displacement; pairwise/tight-centroid checks and a discarded-excursion regression protect conservatism. Intermediate runs passed 71 then 72 tests at their own source snapshots, not automatically final-source evidence. The final 74-test run includes the later explicit unknown/nonfinite accuracy policy tests.

Additional checks: 14 Python runner-guardrail tests and 5 localization-catalog utility tests passed. Localization JSON parses. Added interface strings have English source/fallback entries; native-language editorial acceptance is outstanding.

Actual producers regenerated both fixture outputs, one at a time:

```sh
python3 android/scripts/generate_google_timeline_fixtures.py
python3 android/scripts/generate_timeline_duplicate_fixtures.py
python3 android/scripts/generate_google_timeline_fixtures.py --check-source
python3 android/scripts/generate_timeline_duplicate_fixtures.py --check-source
```

All exit 0; 50 Google and 50 duplicate input/expected cases remain structurally identical to `a4bf13af`. Only provenance changes. Final SHA-256:

- `android/export/src/test/resources/ios-google-timeline.json`: `b9306952ba52f73753a574ce37d1c4318f6c64cb4d11440912c1c6315e92d790`
- `android/domain/src/test/resources/ios-timeline-duplicates.json`: `ea00a423f7f024b4d6d4fcd3e0d4817a5a37f66574771dbcb716e029228f2f48`

The two references execute parser/match bodies, **not** inference; the signed tests above exercise the actual new inference and SwiftData integration.

## Scheduling transition

Early tests used the original atomic `heavy.lock`. The coordinator interrupted a later command while it was only waiting (no acquired lock or result bundle). Process inspection confirmed no owned waiter/test/producer remained; retention's old lock was untouched. Only `ios-transition-google-import.ready` was written by this lane. After all three iOS readiness files existed, final tests/producers used atomic `heavy-ios.lock` with owner/trap cleanup. No later iOS work acquired the Android `heavy.lock`. Tests were bounded to 600 seconds; producer commands to 150 seconds (existing producer Swift timeout 120 seconds). Commands waited boundedly for admission and never removed another lane's lock.

## Unmet acceptance / local ZIP decision

Folder discovery and folder-picker integration are deliberately deferred. No folder traversal, symlink policy, unrelated-file filtering, file-count discovery cap or folder QA is claimed. Explicit multi-file selection retains strict whole-selection rejection. Source selection limits remain 50 MiB / 250,000 source records; derived visits add bounded retained arrays (at least four observations per candidate). Processing is not streaming, and no worst-case memory/latency benchmark is claimed.

Local ZIP decision: preserve the no-dependency/offline posture. Tap ZIP in Files to unzip, then select supported JSON files manually. Do not add a ZIP library/reader or create a remote decision issue in this authorized slice. Direct ZIP and automatic folder import remain unsupported.

No interactive simulator layout/motion/Dynamic Type/dark-mode/accessibility acceptance was performed. Peekaboo permissions were available, but the Simulator application was not running when inspected (`APP_NOT_FOUND`); no screenshot is presented as evidence of the new review controls. Appllama/Argent interaction tools were not in this lane's tool set. Native UI needs a separate full-flow pass, including rapid option changes, opt-out, cancel and reimport. No physical-device test, packet capture, Watch/widget runtime check, full-suite run, real-data accuracy study or performance/release acceptance is inferred. This feature is not claimed visually finished or the epic complete.
