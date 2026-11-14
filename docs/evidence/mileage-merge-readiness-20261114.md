# Mileage foundations and vehicle-store current-main checkpoint

## Scope

Isolated branch based on main `5344145e`. Reused existing committed mileage models/calculator/reports (`faecacaf`, `23b91978`), frozen explicitly selected US rate catalog (`5cb0e205`) and CSV/JSON writers (`b7a1a501`). Resolved project conflicts by registering only mileage sources/tests, not unrelated Smart Start or Google-import registrations. Main's six prior model types remain; Trip and Vehicle make eight in this independent branch. The separate Watch PR adds its receipt model later and must preserve all entities when schemas are integrated.

Actual country/distance/outing/Markdown producers regenerated source provenance for the scalar `LocationPoint.tripID` addition. Non-provenance outputs are identical to current main for all four fixtures. No hashes were hand-pinned.

## Vehicle-store draft verification

Copied the existing uncommitted `MileageVehicleStore.swift` and its 19 authored tests from the preserved vehicle lane into this isolated branch. The original lane remains unchanged. Production source bytes are unchanged from that draft: a private-context, synchronous MainActor unit of work returns immutable snapshots, normalizes one active default, archives rather than deletes, and never rewrites historical Trip vehicle snapshots.

The first actual signed iOS execution of those **19 tests passed**. Added one real SQLite store close/reopen case: two vehicles, a historical Trip snapshot, rename/archive/default reassignment, then reopened persisted rows and original Trip vehicle snapshot. It passes in the final full suite. The 19 original test methods/assertions are unchanged; their stale authored-only header is replaced with an accurate general test description.

The store remains backend-only, not a user-facing vehicle screen. The rollback hooks prove owned-context rejection behavior, not a physical SQLite write-error fault injection. Cross-process identity uniqueness and arbitrarily hostile writers are not claimed.

## Fresh signed iOS evidence

1. Committed foundations/writers on current main: **567 XCTest +20 Swift Testing tests passed**.
2. Unmodified vehicle draft cohort: **19 tests passed** (`vehicle-first-run.log`).
3. Final tree including SQLite reopen case:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A'
```

**587 XCTest cases, zero failures; 20 Swift Testing tests passed**. Result: `artifacts/test-results/IsoMe-20261113-230902-82695.xcresult`; local log `artifacts/merge-readiness/mileage-final-ios.log`.

This includes the actual `MileageMigrationTests` old LocationPoint schema → shared app schema → save/reopen test, plus models, distance, rate/report, CSV/JSON and vehicle-store cohorts. That migration test populates Visit, LocationPoint and RecordingSession legacy fields; it does not establish every historical store version, every field of all six legacy entities, or a physical user-store migration.

All **30** CI source-reference checks, project syntax (`plutil -lint`) and `git diff --check` pass.

## #61 requirement boundary

These are tested foundations, not complete Mileage Mode. Still missing: manual recorder integration and relaunch recovery through the real tracking call sites, vehicle management UI, Mileage/trip review/detail/classification UI, report actions/purchase gating/rate-selection UI, complete Trip/Vehicle backup round trips, user-facing migration/device acceptance, and later automatic drive detection/backfill. The existing dirty recorder draft is deliberately not copied: it depends on Smart Start session types and needs its own integration/verification contract.

CSV includes the issue's original 19 fields plus documented versioned metadata/summary columns; JSON preserves optional text and decimal strings. Formula defenses are documented common-spreadsheet precautions, not a guarantee across arbitrary importers. All fixtures here are synthetic. No spreadsheet application or user spreadsheet was accessed.

No tax/legal/accounting advice or compliance claim. Rates remain explicitly selected and frozen; no future-year extrapolation or automatic deductible classification. Hosted checks on the pushed head are still required; no fresh physical tracking, UI, energy or actual user-data result is claimed.
