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

## Requirement boundary / open gates

This is progress toward #59, not epic completion. Remaining: actual Files folder picker and authorized local/FileProvider policy with useful errors/progress, folder review presentation including exclusions, current visible inference/review UI verification, on-device memory/latency evidence, Watch/widget refresh acceptance, and explicit ZIP decision documentation/issue. Hosted Android/iOS checks must also pass on the pushed head. There is no fresh device UI/screenshot, physical import, network capture, or million-record claim in this receipt. Keep original exports; approximate deduplication and conservative inference are not universal lossless Google migration.
