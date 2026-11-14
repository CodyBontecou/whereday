# Google import current-main checkpoint

## Reused work and new seam

Isolated branch based on main `5344145e`. Reused existing multi-file, inference/review ownership, and bounded folder-discovery commits (`a4bf13af`, `2a35c4ef`, `372daeb4`, `e0c7ea3a`) instead of reimplementing them. All are now compiled together with current main. No subscription, Watch-manual-visit, Smart Start or mileage feature branches are bundled into this branch.

New `TimelineImportService.preview(folder:)` connects the existing local-folder discovery capability to the actual Google codecs, aggregate record admission, duplicate planner, inference policy and preview. Root security-scope access remains held until discovery/consumption finishes. Every candidate is read through the borrowed reader and codec-validated; a malformed candidate rejects the entire preview. It is read-only. Aggregate bytes stay bounded by the discovery manifest; aggregate record admission uses the same helper as manual multi-file selection. Only an explicit subsequent commit writes records and rechecks duplicates.

The adapter supports an already-local, authorized directory. It does not materialize cloud FileProvider contents, coordinate hostile external writers, add a folder picker, or make ZIP support available. No folder URL is retained after preview. Excluded unrelated/backup files are not imported. The folder discovery core retains bounded metadata/recheck rules and symlink rejection; these are not a claim of hostile same-UID race protection.

## Test chronology

1. Full current-main suite on the reused work passed before the new adapter.
2. Six new real-worker integration tests failed compilation with the intended missing `preview(folder:)` API (`google-folder-red.log`, exit 65).
3. After implementation, the targeted worker + folder cohort passed **63 tests** (24 TimelineImport + 39 GoogleFolderDiscovery).
4. Shared aggregation retains the original in-place accumulator mutation so the refactor does not introduce a second retained aggregate before each append.
5. Final full signed runner:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A'
```

**580 XCTest cases, zero failures; 20 Swift Testing tests passed.** Result: `artifacts/test-results/IsoMe-20261113-225112-66180.xcresult`. Local log: `artifacts/merge-readiness/google-folder-full.log`.

The six new cases cover nested mixed Google families with unrelated/backup exclusions, preview with no writes followed by repeated safe commit, malformed-candidate all-or-nothing rejection preserving existing history, full codec validation after prefix hints, device-root arrays, aggregate pre-deduplication record limits, and symlink exclusion. These assertions use the actual actor and in-memory SwiftData, not mocked folder outputs.

Actual Swift Google/duplicate fixture producers were rerun; all 100 non-provenance case outputs remain unchanged from the reused branch. All **30** CI source-reference checks and `git diff --check` pass.

## Hosted compiler feedback follow-up

Hosted iOS run `43548700543` at `c6ff203a` failed before tests: Swift could not type-check the single-expression centroid fixture in `VisitInferenceTests.swift:71` in reasonable time. The local suite above genuinely passed, but did not establish compatibility with the hosted compiler.

Split only that fixture expression into an explicitly typed point array, timestamp, latitude and accuracy values. The six point arguments and all assertions are unchanged; no production inference code, test selection, timeout or skip changed. Fresh full local verification after this test-only correction again passes **580 XCTest +20 Swift Testing tests** (`artifacts/merge-readiness/google-compiler-fix.log`). The next hosted run must verify its compiler accepts the change; no hosted GREEN is claimed yet.

## Hosted interoperability dependency follow-up

At `b09a6e78`, the hosted Android backup→SwiftData job failed before execution because its standalone `swiftc` source list omitted `VisitInferenceService.swift` and `GoogleFolderDiscovery.swift`, newly referenced by the complete import worker. Added those two real sources to both `verify_place_provenance.py` and `verify_timeline_admission.py`; no importer stubs, source extraction shortcuts or assertions were substituted.

Downloaded the actual synthetic `android-place-provenance-inputs` artifact from run `43549650710`. The first local attempt compiled but failed the pre-existing timezone-less Markdown expectation on this UTC−4 host (`17:00Z` vs UTC fixture `13:00Z`). Preserved that failure log; it is not a Google inference/folder assertion. With `TZ=UTC` matching the Android fixture's generation zone, the real harness passes **12 Android files previewed/committed/deduplicated**, and the complete SwiftData admission harness passes **7 scenarios**. Commands:

```sh
TZ=UTC python3 android/scripts/verify_place_provenance.py --input /tmp/whereday-merge-readiness/google-interop-inputs/android-exports.json --output artifacts/merge-readiness/google-swift-commit-acceptance.json
TZ=UTC python3 android/scripts/verify_timeline_admission.py --output artifacts/merge-readiness/google-swift-admission.json
```

These local results establish the corrected source lists and existing UTC fixture scenarios, not timezone-independent Markdown round trips or fresh hosted acceptance. The next hosted checks still must pass.

## Older SwiftData predicate compatibility follow-up

Hosted iOS job `132314320865` at `45acec20` compiled successfully but failed five inference-import methods: its SwiftData runtime rejects `PredicateExpressions.ForcedUnwrap`. Reproduced the **same five failures among ten methods** on a newly created, owned iOS 26.5 test simulator; this is a real runtime RED, not a source-only counterfactual.

Replaced the forced unwrap in the existing bounded overlap query with `(departedAt ?? distantFuture) >= start`, matching the already-used ManualVisitStore pattern. Arrival bounds, inclusive endpoint semantics and read-only preview behavior are unchanged. Added a four-case regression covering open visits, a closed visit ending before the range, exact arrival-boundary overlap, and a closed overlapping visit.

The complete signed suite on iOS 26.5 now passes **581 XCTest +20 Swift Testing tests**, result `artifacts/test-results/IsoMe-20261113-233821-9184.xcresult`. Logs: `google-ios26-predicate-red.log` and `google-ios26-predicate-green.log`. The owned compatibility simulator was deleted afterward; a preceding shutdown command reported it was already shut down, not a successful shutdown operation. No pre-existing simulator was altered.

Actual Google/duplicate fixture producers were rerun: all 100 non-provenance outputs unchanged; all 30 source-reference checks pass. Current actual UTC interop/admission harnesses also pass 12 Android files and seven SwiftData scenarios (`google-predicate-interop.log`). Fresh hosted gates still remain required on this pushed source; earlier newer-runtime local passes did not establish older-runtime compatibility.

## Requirement boundary / open gates

This is progress toward #59, not epic completion. Remaining: actual Files folder picker and authorized local/FileProvider policy with useful errors/progress, folder review presentation including exclusions, current visible inference/review UI verification, on-device memory/latency evidence, Watch/widget refresh acceptance, and explicit ZIP decision documentation/issue. Hosted Android/iOS checks must also pass on the pushed head. There is no fresh device UI/screenshot, physical import, network capture, or million-record claim in this receipt. Keep original exports; approximate deduplication and conservative inference are not universal lossless Google migration.
