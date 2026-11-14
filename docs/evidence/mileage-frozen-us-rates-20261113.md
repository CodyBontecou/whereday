# Frozen US business mileage catalog — 2026-11-13

Local catalog component only; **not signed iOS, UI, Phase 3 or full Mileage acceptance**.

`MileageRateProvider()` remains empty. Callers must explicitly select
`frozenUSBusiness2022Through2026(timeZone:customRate:customSource:)` or supply their
own schedule/override. Rates are exact Decimal USD/mile; no 2021/2027 extrapolation.

## Published snapshot and provenance

Research snapshot: **2026-11-13**. The [IRS historical table](https://www.irs.gov/tax-professionals/standard-mileage-rates)
provides the period labels, including both 2022 and 2026 midyear splits. This is a
frozen snapshot, not an automatically updated rate source.

| Inclusive civil dates | USD/mile | Primary rate/boundary sources |
|---|---:|---|
| 2022-01-01–2022-06-30 | 0.585 | [Initial release](https://www.irs.gov/newsroom/irs-issues-standard-mileage-rates-for-2022), [explicit half-year table](https://www.irs.gov/newsroom/irs-increases-mileage-rate-for-remainder-of-2022) |
| 2022-07-01–2022-12-31 | 0.625 | [Announcement 2022-13](https://www.irs.gov/irb/2022-26_IRB#ANN-2022-13), [midyear release](https://www.irs.gov/newsroom/irs-increases-mileage-rate-for-remainder-of-2022) |
| 2023-01-01–2023-12-31 | 0.655 | [Notice 2023-3](https://www.irs.gov/irb/2023-03_IRB#NOT-2023-3), annual endpoint from IRS table |
| 2024-01-01–2024-12-31 | 0.67 | [IR-2023-239](https://www.irs.gov/newsroom/irs-issues-standard-mileage-rates-for-2024-mileage-rate-increases-to-67-cents-a-mile-up-1-point-5-cents-from-2023), annual endpoint from IRS table |
| 2025-01-01–2025-12-31 | 0.70 | [IR-2024-312](https://www.irs.gov/newsroom/irs-increases-the-standard-mileage-rate-for-business-use-in-2025-key-rate-increases-3-cents-to-70-cents-per-mile), annual endpoint from IRS table |
| 2026-01-01–2026-06-30 | 0.725 | [Initial release](https://www.irs.gov/newsroom/irs-sets-2026-business-standard-mileage-rate-at-725-cents-per-mile-up-25-cents), limited by [Announcement 2026-11](https://www.irs.gov/irb/2026-29_irb) and IRS table |
| 2026-07-01–2026-12-31 | 0.76 | [Announcement 2026-11 in Internal Revenue Bulletin 2026-29](https://www.irs.gov/irb/2026-29_irb), explicit second-half period in IRS table |

The factory retains primary URLs/provenance per period. Annual endpoints for
2023–2025 come from the table, not invented December 31 quotations in the releases.

## Date and policy contract

The caller explicitly supplies the report timezone. Gregorian civil midnights
become absolute half-open intervals in that zone; invalid/normalized civil dates
fail closed without a UTC fallback. Use the same zone as `MileageReportOptions`.
Current report calculation selects by `trip.startedAt` for the whole trip, not the
trip's stored timezone; there is **no proration or tax-eligibility date policy**.
An explicit custom override retains existing initializer semantics, including
finite dates outside catalog years. None of this establishes deductibility,
compliance, or tax/legal/accounting advice.

## Actual Mac-only reference verification

A private dependency-free SwiftPM module `IsoMe`, macOS 14+, copied exact real
Trip, Vehicle, report options, provider, report service and the full test class.
No model stubs, assertion filters, external packages, default store or simulator.

- **Late counterfactual negative control**, after implementation: only the private
  factory body was replaced with the retained empty factory. All **27 XCTest
  methods** executed: **19 passed, 8 failed, 60 assertion failures, zero skips**;
  all original 18 passed. This is not chronological TDD.
- Actual complete factory, identical full test class: **27 passed, zero failures,
  zero skips**. Includes all 18 original bodies unchanged plus 9 additions.
- Coverage includes all periods, both July splits, independent ISO/Decimal
  expectations, UTC/Los Angeles/+14/+05:45, leap day, missing years, custom/zero/
  invalid rates/provenance, overflow, no-proration and mixed known-2026/missing-2027
  business reports (known subtotal $7.60, one missing rate, incomplete estimate).
- Two jobs, each under 60 seconds; peak sampled allocated outputs/evidence under
  123 MiB, minimum sampled free disk above 11 GiB. Guards were sampled, not OS quotas.
- An earlier attempt failed the old logical-size guard before tests. Its failed
  evidence remains unchanged. A separate diagnostic found three compilation-cache
  files totaling 37 GiB logical capacity with about 3 MiB allocated at capture;
  the later physical envelope was explicitly authorized, not retroactively applied.

Verified provider SHA-256:
`aaf8d1ff629560747b1277cca65648970f4df67405d0fe56451eca890668983c`

Identical full test-class SHA-256 in both reference runs:
`01bc4501e3e233c032539d3861ff2ec7ea918c383a1b95a872223d07803734eb`

Signed iOS verification still requires `scripts/test-ios.sh` after normal host
admission. UI/default catalog wiring, store/migration/physical/export acceptance
and broader Mileage Mode work remain pending. No issue or full-phase completion.
