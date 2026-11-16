# Daily timeline merge verification — November 12, 2026

Branch: `fix/ios-daily-timeline`, based on `f003fe3e` (`origin/main`).

## Scope

Daily timeline queries and half-open day slices; explicit complete-outing navigation; daily mutation protection; accurate recording-preset selection and recording/privacy/Watch copy; localization; synthetic continuous-recording fixture; regression tests.

Excluded: Android ScrawlKit commit, temporary scroll diagnostics and launch flags, Xcode user state, unrelated release and recovery artifacts.

## Fresh verification

All iOS runs used `scripts/test-ios.sh` with normal simulator signing and explicit destination `platform=iOS Simulator,id=50225EBD-E03C-4094-B351-A859EF8B9FB8` (iOS 27.0). The automatic destination selection failed because the selected Xcode SDK is 27.2 and no matching runtime is installed. The documented explicit destination override worked.

- Targeted run: **71 passed, 0 failed, 0 skipped** across `LocationViewModelPointScalingTests`, `TimelineStaySummaryTests`, `RouteSamplerTests`, and `LocationManagerRouteSamplingTests`.
  - Result: `artifacts/test-results/IsoMe-20261112-145752-8648.xcresult`.
- Initial full run: one failure in the unchanged `StoreManagerTests.testStoreKitCatalogTrialPurchaseExpirationAndLifetime`, at the `XCTAssertFalse(store.isPurchased)` assertion after expiring the test subscription. All other tests passed.
  - Result: `artifacts/test-results/IsoMe-20261112-145919-10012.xcresult`.
- Isolated retry of that StoreKit test: **passed**, with no code changes.
  - Result: `artifacts/test-results/IsoMe-20261112-150040-12081.xcresult`.
- Unchanged full-suite rerun: **508 passed, 0 failed, 0 skipped** (488 XCTest + 20 Swift Testing).
  - Result: `artifacts/test-results/IsoMe-20261112-150115-12708.xcresult`.
- `python3 scripts/localization_catalog.py audit`: passed across 11 locales; no missing translations or placeholder mismatches.
- `python3 -m unittest discover -s scripts -p test_onboarding_localization.py`: 4 passed.
- `python3 -m unittest discover -s scripts -p test_test_ios.py`: 14 passed.
- `git diff --check`: passed.

## Existing visual evidence

The adjacent November 11 screenshots and `verification.json` document the earlier synthetic 1,000-hour/60,001-point simulator verification. They are not newly captured in this merge-preparation run. `daily-details.png` shows 1,440 daily points and the complete-outing link; `complete-outing.png` shows the complete recording. No customer export was used.

No fresh physical-device tracking check or Watch battery measurement was performed. No store upload, release, or merge was performed during preparation.
