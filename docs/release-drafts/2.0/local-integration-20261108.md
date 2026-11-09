# Local Apple/Android integration — November 8, 2026

This continuation implements and verifies source changes locally. No push, PR mutation, remote branch write, hosted CI, store operation or production change is authorized or performed.

## Integrated source

Worktree `/private/tmp/whereday-ios-integration-20261108`, branch `local/ios-integration-20261108`, preserves the history of #109 (`9ff11d2c`), #110 (`47712191`) and the stable #111 Android baseline (`2740bb55`). The Android baseline was merged locally without source conflicts. Android recovery work is sibling-owned and will be integrated only from a stable local commit; the earlier failing CI recovery gate is not cleared by this merge.

Apple review found and fixed actual backup data loss and malformed-record admission gaps in local commit `35ab45f3`:

- Restore now retains four original confirmation/place fields used by Undo Correction and two route-sampling fields. Optional Codable additions preserve old backup compatibility without changing persisted models.
- Backups reject inverted visit/session intervals, invalid point accuracy and timestamps outside the ordinary importer's supported range before writing any records.
- Direct model assertions and legacy/malformed XCTest cases replace the blind spot in the existing serialized round-trip check. A host macOS SwiftData harness verifies actual export, public preview, fresh commit, Undo Correction, and preservation of existing storage on rejection.

The independent standards/spec review identified the same two bugs. The follow-up review found both addressed; all persisted model fields are now represented in the backup. No claim of an exhaustive review of every #109 behavior is made.

## Executed verification

- Unsigned generic **iOS Release compilation succeeded** with Xcode 27.2/27B52, pinned package revisions, `-destination 'generic/platform=iOS'`, and `CODE_SIGNING_ALLOWED=NO build`. Phone, widget, Watch and Watch widget compiled. No runtime, signing, archive, export or upload occurred.
- Host SwiftData regression: original service **1/9 passed**, fixed service **9/9 passed**. The original snapshot's hash matches #109; fixed compiler inputs are snapshotted/hashed. See [backup evidence](../../evidence/ios-local-backup-20261108/README.md), [before](../../evidence/ios-local-backup-20261108/before.json), [after](../../evidence/ios-local-backup-20261108/after.json), and [native summary](../../evidence/ios-local-backup-20261108/native-build.json).
- `python3 -m unittest discover -s scripts -p test_test_ios.py`: 14 passed.
- `python3 -m unittest discover -s scripts -p 'test_*localization*.py'`: 9 passed.
- `python3 scripts/localization_catalog.py audit`: 2,553 active keys across 9 catalogs/11 locales; no missing translations or placeholder mismatches. Identical-English diagnostics remain, and machine-written translations are not human-reviewed.
- `python3 scripts/check_map_first_store.py`: passed; all 11 Apple metadata locales, field lengths/legal links/What's New alignment, 10 screen-translation maps and artwork plan counts. Corrected reviewer notes remain within 4,000 characters.
- `git diff --check`: passed.

The generic products use repository defaults **2.0 (60)**. Those are already-consumed version/build numbers, not a new candidate allocation. The build proves compilation only. Added iOS XCTest cases were not executed; an authorized future runtime pass must use the signed `scripts/test-ios.sh` runner. Native compilation ownership was released via Herdr after these checks; other agents' work was not interrupted.

## Corrected candidate and store provenance

The earlier “2.0/build 63 is staged” report is superseded by newer saved November 8 receipts in the shared starting checkout. `docs/evidence/release-1.8.4-64/release.txt` explains that Apple allowed one editable iOS version and the existing 2.0 slot was renamed 1.8.4 for the separate startup patch. Local private receipts confirm:

- `.asc/artifacts/release-1.8.4-64/submission.json`:version 1.8.4, build 64 (`0dbcc134-2e49-4475-80cc-093d4ba766d6`), submitted `2026-11-08T23:24:18.285Z`.
- `final-state.json`:the same version slot (`41f370e9-3029-4ff5-9664-90ba13a871c9`) is recorded as **1.8.4, WAITING_FOR_REVIEW**, build 64.
- The previous 2.0 metadata, reviewer notes, screenshots and Asset Library receipts are preserved at `.asc/artifacts/release-1.8.4-64/backup-2.0/`. Build 63 (`b1c136de-db11-400c-aa46-437a01c62b20`) belongs to earlier source `f2959482` and does not contain the later #109 or local fixes.

These are saved receipts, not a fresh live-store query. No in-review patch is overwritten. A separately authorized release task must wait for Apple's version-slot availability, restore/review the 2.0 draft and choose an unused build number based on actual current store state. Current local reviewer notes are a draft only and have not been applied remotely.

Android 2.0.1 (4)'s recorded completed rollout also does not establish that #111 or later local recovery changes are shipped. Native-language/RTL/large-text/TalkBack and device behavior remain unverified under this task's runtime restriction. Existing saved asset receipts support the earlier eleven-locale upload coverage; they do not prove current placement after the 1.8.4 slot change. Review restored assets, old-design search video disposition, App Privacy and release mode before any future submission.

## Preservation

The starting checkout remains `marketing/map-first-store-art` at `9ff11d2c`, including untracked startup/release evidence. #109/#110/#111 remote branches and PRs are unchanged during this continuation. Existing worktrees, source, logs and QA evidence are preserved. The local integration branch, build outputs, synthetic regression evidence and Herdr tabs are retained for review.
