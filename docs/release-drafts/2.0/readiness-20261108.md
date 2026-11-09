# Apple and store readiness — November 8, 2026

Apple CI passed on draft [PR #109](https://github.com/CodyBontecou/whereday/pull/109), head `9ff11d2cd60a7bb2746cf0fbca0e1bcef8189215`. No new Apple source gap was established by this review. Release readiness still has the gaps below. No simulator, emulator, Argent, physical-device QA, store writes or publishing occurred in this review.

## Source and review evidence

- Remote `main` was `70f87d4678570e1d41a1e2c57f6c484f1d455182`; #109 already includes it. Core map-first work and QA #108 are merged. #109 remains open and draft, with no review comments or inline threads at inspection.
- [Apple CI run 42522837011](https://github.com/CodyBontecou/whereday/actions/runs/42522837011) completed successfully at this head. Both **Test iOS runner guardrails** and **Run IsoMe tests** passed. CI uses `scripts/test-ios.sh` with simulator signing enabled.
- #109 records the previous local full suite as **473 XCTest + 20 Swift Testing tests passed**, result `artifacts/test-results/IsoMe-20261108-165745-56826.xcresult`. This is prior evidence, not a new run here.
- Reviewed the existing import/backup admission and restoration changes, export filename safeguards, historical-day/replay clamping, editor save handling, related tests, and the in-app 2.0 release notes. Existing fixes and QA evidence are preserved.
- [Android CI run 42522836942](https://github.com/CodyBontecou/whereday/actions/runs/42522836942) failed overall. The Android sibling reports its build/unit/lint/source-provenance job passed; remaining failures involve location recovery and the SwiftData backup harness. The sibling owns `android/`, including harness/localization integration, on `android/redesign-finish-20261108`. A green Apple job does not clear these shared PR gates.

## Checks performed in this review

Run from the task worktree, without device actions:

| Check | Result |
| --- | --- |
| `python3 -m unittest discover -s scripts -p test_test_ios.py` | 14 passed; runner guardrails only, no iOS build or launch |
| `python3 -m unittest discover -s scripts -p 'test_*localization*.py'` | 9 passed |
| `python3 scripts/localization_catalog.py audit` | 2,553 active keys, 9 catalogs, 11 locales; no missing translations or placeholder mismatches |
| `python3 scripts/check_map_first_store.py` | Apple metadata lengths/legal links/What's New alignment, eleven locales, ten screen-translation maps, artwork plan and reviewer-note length passed |
| Saved screenshot upload and Asset Library receipts | Complete per-locale coverage: 10 iPhone + 5 iPad + 2 Watch, 8 Duo placements + header; retries combined by filename |
| Saved PNG headers | 286 PNGs: expected dimensions and opaque RGB color type in all eleven locales |

The catalog audit also lists non-allowlisted strings identical to English; passing structural checks is not native-language approval. The store check validates source structure and field limits, not typography or translation quality. No new render or browser was launched.

Receipt inputs remain private/local under the starting checkout's `.asc/artifacts/`:

- `stage-2.0-map/attach-build.json` attaches build `b1c136de-db11-400c-aa46-437a01c62b20` to version `41f370e9-3029-4ff5-9664-90ba13a871c9`.
- `stage-2.0-map/screenshot-upload/` supplies the English screenshot results; `stage-2.0-map/design-screenshots/en-US/` and `stage-2.0-map/watch/` supply its PNGs.
- `stage-2.0-l10n/upload-log/` and `stage-2.0-l10n/design-screenshots/` supply the ten translated sets. Partial Arabic iPad and French iPad uploads have retry receipts; coverage was checked by the union of `COMPLETE` result filenames, rather than treating an initial partial result as success.
- `stage-2.0-map/asset-library-receipt.json`, `stage-2.0-l10n/asset-library-receipt.json` and `stage-2.0-l10n/asset-library-receipt-bn-retry.json` record eight Duo placements, a header placement and `done: true` for each locale.
- `play-2.0.1/production-release.json` records **2.0.1 (4)** as `completed`; `commit.json` records its committed edit. These support the dated Android release record, not a fresh live-store query.

No private receipts, credentials, customer location data or large image copies were added to this branch.

## Remaining release gaps

1. **Apple candidate:** staged **2.0 (63)** was built from `f2959482`, before #108/#109 hardening and the in-app 2.0 notes. It must not be described as containing those fixes. A new candidate, correct build attachment, signed tests via `scripts/test-ios.sh`, and archive/export qualification remain for a separately authorized release task. Do not reattach build 62.
2. **Reviewer notes:** the local `reviewer-notes.txt` now describes map-first navigation and design-rendered sample artwork. It labels the staged build and later source separately. No receipt establishes that these corrected notes have been applied to App Store Connect; confirm candidate contents and build number before applying them.
3. **Native review:** all eleven locales' metadata, overlay text and drawn screen labels still need native-language review. Arabic artwork is not mirrored; visual/native review remains necessary.
4. **Old video:** the record says the inherited English App Store search-results video still shows the old design. Decide whether to replace or remove it. No receipt here establishes a later correction.
5. **Final Apple review:** inspect the candidate's current screenshots/header/Duo, App Privacy and manual release setting. This task does not establish current store state, physical-device behavior, submission or approval.
6. **Shared PR:** Android integration and failed CI jobs remain owned by the sibling. Keep #109 draft until relevant checks and review are satisfied. No merge, submission, release or production change is authorized by this review.

## Ownership and preservation

Task branch: `hardening/ios-store-readiness-20261108`, worktree `/private/tmp/whereday-ios-store-readiness-20261108`, based on #109's `9ff11d2c`. Changes are limited to release documentation and a read-only local draft checker. The shared starting checkout stays on `marketing/map-first-store-art`; untracked `docs/evidence/release-1.8.4-64/` and `docs/evidence/startup-investigation-20261108/` were preserved. No other worktree, branch or QA evidence was removed. Herdr background tabs remain open.
