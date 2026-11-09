# iOS export filename edge QA — November 8, 2026

Target: iPhone 18 Pro simulator, iOS 27.0, `9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A`. Live interaction used Argent.

## Reproduction

Archive → Weekly GPX → Preview Export displayed one GPX containing 111 points. Opened the file and inspected its XML. In All Options, replaced the filename pattern with 300 ASCII A characters. The Archive sample still advertised one valid 31.8 KB file; Export then failed with a system error quoting the entire invalid filename. See `oversized-name-before.png`.

## Fix

The shared export path planner rejects file/folder components exceeding the native filename limit, including the extension. Validation runs again after outing, day-collision, and format-collision suffixes are applied. The limit is measured using decomposed UTF-16 units, matching the names Foundation uses for filesystem writes. UTF-8 byte counting would incorrectly reject valid emoji. Composing long accented names was insufficient: the iOS file-write test demonstrated that Foundation decomposes them again. Both composed and decomposed accents are checked consistently, with successful and rejected actual file writes around their boundary. The live Archive sample now displays the localized validation error; the full preview and export use the same validation. No truncation or silent renaming.

## Regression coverage

Added five ExportKitIntegrationTests covering overlong ASCII/emoji/folder names, successful filesystem writes at valid Unicode boundaries, JSON/OwnTracks collision suffix overflow in preview and export, and split-day suffix overflow. The three rejection tests failed before the fix (six assertions), result `artifacts/test-results/IsoMe-20261108-090628-69948.xcresult`.

An initial test build had a missing fixture initializer argument; that was corrected before recording the red run above.

The first fixed run passed 34 of 35 tests and exposed the Foundation decomposed-accent boundary case above (`artifacts/test-results/IsoMe-20261108-090732-71145.xcresult`). A follow-up run showed composition alone could not fix this: Foundation decomposes the filesystem name. The validator now checks that decomposed form, and an additional test checks actual rejection of both accent representations above the boundary.

Final verification: **36 tests passed** (24 ExportKitIntegrationTests and 12 ExportServiceRoundTripTests), using `scripts/test-ios.sh` with the target simulator and simulator signing enabled. Result: `artifacts/test-results/IsoMe-20261108-091120-76269.xcresult`. `git diff --check` passed for the changed source/test files.

## Live verification after the fix

- The same 300-character pattern now displays “Use a shorter file or folder name.” directly in Archive (`oversized-name-inline-after.png`).
- Full Preview reports the same validation failure (`oversized-name-preview-after.png`).
- Export fails before filesystem writing with that concise error rather than quoting 300 characters (`oversized-name-export-after.png`).
- Loading Weekly GPX restored the readable filename. Full Preview recovered to one GPX / 111 points (`valid-name-recovery.png`).
- Export with the valid name successfully opened the native share sheet (`valid-export-share-sheet.png`); dismissed without selecting a recipient or destination.
- The app was left on Archive with the existing Weekly GPX preset loaded and its standard readable filename; no saved recipe was created or changed.

These UI checks used direct live Argent interactions. No claim of a portable saved e2e replay is made; this session previously encountered an Argent native selector replay issue.

## Scope

No history imported or edited; no files sent to another application or network destination. This check covers native filename-component constraints, not every limit of an external file provider. Earlier test passes already covered import review, calendar handoff, recap, and editors, so this pass concentrated on Archive export preview and naming.
