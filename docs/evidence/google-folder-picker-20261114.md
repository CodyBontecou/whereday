# Native Google folder-picker bridge — 2026-11-14

PR #119 remains draft. This connects the existing bounded local-folder worker to the real picker/review/commit flow; it does not establish cloud-provider, full accessibility or device-performance acceptance.

## Actual missing-entry RED → GREEN

The existing Settings and Today pickers return `[URL]` to `LocationViewModel.prepareImport`, which calls `TimelineImportService.preview(urls:)`. A folder selected through that path previously went to ordinary file reading instead of the already-tested `preview(folder:)` adapter.

Two new tests exercised the **unchanged public entry signature** and actually failed with Cocoa256 / POSIX21, “Is a directory,” before implementation:

- one selected nested Google folder must preview without saving, then commit only on explicit request;
- a folder mixed with selected files must fail admission, not offer a partial preview.

The worker now checks directory metadata with security-scope access on its own actor before decoding any file. A lone folder delegates to the existing bounded scanner; any folder/file or multiple-folder mixture is rejected. Normal file/backup parsing, aggregate byte/record bounds, duplicate planning, transaction behavior and read-only preview remain intact. The scanner's local/cooperating-root and metadata-recheck limitations are unchanged; no hostile-writer protection is claimed.

Both actual SwiftUI file importers now include `.folder`. Settings explains selecting files **or one unzipped Google folder**, making it available offline in Files, and that ZIP is unsupported. No ZIP dependency or automatic materialization was added.

A small Sendable preview summary carries the number of validated Google files and excluded entries; re-review preserves it. Exclusions include directory entries, not a promised recursive count of descendants beneath skipped directories. The review displays both counts and whole-folder validation rejection behavior without showing source paths. Folder discovery errors now provide actionable localized-string keys instead of an opaque enum error. Catalog translations for this new copy remain open.

## Tests and provenance

- Targeted **26 TimelineImportTests pass**, including both new entry tests, actual folder metadata/re-review preservation, existing single/multi-file and backup behavior, malformed-folder rejection, bounds and repeated safe commit.
- Full signed `scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9DBF4207-832B-4A34-88DB-AE841A6383DA'`: **583 XCTest +20 Swift Testing**, zero failures. Result `artifacts/test-results/IsoMe-20261114-080108-28539.xcresult`; logs `folder-picker-{red,class-green,full}.log` under `artifacts/merge-readiness/`.
- Whole-file source provenance correctly rejected two old hashes after the worker changed. Executed both real Swift fixture producers; **all50 Google +50 duplicate case outputs are unchanged**. Only provenance metadata changed. All **31 actual CI source checks** now pass. No hashes were hand-pinned.

## Real Files picker, not an injected preview

Created separate owned UI-only iPhone17Pro/iOS26.5 guest **DD0819DF-30A5-460C-8781-57A7537A60C2**; no XCTest ran on it. Installed the actual app and a separate, adhoc-signed synthetic helper with Files sharing enabled. **Production app file sharing/Info.plist was not changed** and private journal storage was not exposed. The helper contained only synthetic raw/semantic Google JSON plus unrelated settings/text files. Its source/plist are adjacent documentary files, not product inputs.

Actual path: Archive → Settings → Import files or a folder → system Files Browse → On My iPhone → Folder Import QA → Synthetic Takeout → Open. The real picker granted the external folder URL, and the ordinary async entry displayed:

- two validated Google JSON files, two excluded entries;
- two new points, one visit and one duplicate point;
- inference off for this mixed semantic/raw selection.

Cancelled the first review: real Settings counts remained **0 visits /0 points**. Reopened the same folder through Files, confirmed once, and observed **“2 points and1 visit added” /“1 DUPLICATES SKIPPED”**. After Done, actual Settings refreshed to **1 visit /2 points**. These frames and raw semantic hashes are adjacent; they were inspected, not inferred from file existence. The apparent Dec31 date is the local display of the synthetic Jan1 UTC fixture, not a timezone-independent date claim.

The system picker remote view did not expose its full tree through AXe, so observed screenshot coordinates were used only for its Browse/folder/Open controls. A generic coarse scroll-seek unexpectedly navigated into Listening and exhausted its bound; that is retained as a failed interaction attempt, not a product defect or replay pass. Returned using the observed Back control and used a fine-step explicit swipe to the actual import row. One reopened-picker screenshot caught presentation animation; the settled frame was observed before the next input. There is no flawless automated e2e, Argent replay, full-motion or spoken-accessibility claim. Raw artifacts: `/tmp/whereday-merge-readiness/google-folder-ui-1/`.

The app/helper were terminated, the helper uninstalled, and the owned UI guest shut down; the test host and other owned UI-only guest also verified shut down. No default/physical device or real user export was used.

## Remaining acceptance

Cloud-only/downloaded/stale/denied FileProvider cases and coordination/materialization policy, granular scan/parse/dedupe progress, error-path UI and dark/large-text/localized/spoken/full-motion coverage, inference-control/device performance, Watch/widget refresh and a dedicated ZIP decision issue/documentation remain. A local-provider path working is not proof for every provider. The original backup formats/admission/transaction rules remain preserved; keep original exports.
