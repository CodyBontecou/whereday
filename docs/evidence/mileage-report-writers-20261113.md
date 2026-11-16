# Mileage report writers — 2026-11-13 Mac reference checkpoint

**Coordinator's fresh Mac reference:49/49 passed,0 failures/unexpected/skips.**
Narrow #61 CSV/JSON writer component only; NOT iOS/app/UI/purchase/Phase2 acceptance.
Independent actual-artifact audit and explicit commit authorization remain pending.
Base: `87769325ceb40120da72b10864426b8995b2e813`.
Branch: `fleet/20261113-mileage-report-writers`; old mileage/rates branches preserved.

## Boundary

`MileageReportExporter.csv(report, timeZone:)` and `.json(report, timeZone:)` return
in-memory UTF-8 Data from actual `MileageReportService.Report`; no file IO, second
calculator, rate selection, persistence, clocks or generated IDs. One `MileageReportTrip`
initializer additionally captures actual Trip origin/destination names/addresses,
stored zone, raw source, notes and odometers. Capture on the model context's actor.
No SwiftData/schema/model changes. Report math, filters and the existing27 tests
(original18 plus9 catalog additions) remain byte-unchanged.

## CSV v1

The issue enumerates19 fields; all are retained first/in order. There are **26 columns**:

```text
trip_id,date,start_time,end_time,origin_name,origin_address,destination_name,destination_address,classification,business_purpose,vehicle,distance_miles,rate_per_mile_usd,deduction_usd,review_status,source,notes,start_odometer_miles,end_odometer_miles,row_type,vehicle_id,trip_time_zone,rate_source,rate_is_custom_override,metadata_key,metadata_value
```

Every cell is quoted with RFC4180 doubled quotes, UTF-8/no BOM and CRLF record endings.
Quoted embedded CR/LF and Unicode bytes are retained except the documented protection.
Every record has26 cells. Consumers **must filter `row_type=trip`**. Record order:
header, lexicographically keyed metadata, service-ordered trips, summary, vehicle totals.

- `metadata`: schema_version1, format `whereday-mileage-report`, verbatim disclaimer,
  Gregorian calendar, explicit report time_zone, decimal_encoding `base10-strings`,
  csv_formula_policy `apostrophe-prefix-v1`; key/value cells carry these values.
- `summary`: key/value cells carry miles.unclassified/business/personal, reviewed_business_trips,
  business_trips_missing_purpose/rate, known_estimated_deduction_usd, is_estimate_complete.
- `vehicle_summary`: vehicle_id, distance_miles and **vehicle as a JSON string array**
  of all historical name snapshots. Unassigned ID is empty. Other cells empty.
- Non-trip records always have empty trip_id. Missing rate/deduction/optional text are
  empty cells, never invented numeric0. CSV conflates null and empty text: not a backup.
- Formula defense prefixes ASCII apostrophe when the first meaningful scalar after
  whitespace/control/format characters is `= + - @`, or the original begins tab/CR/LF.
  Already apostrophe-prefixed literals stay unchanged. Numeric report fields are
  nonnegative. Other Unicode control characters, including NUL/DEL, fail closed.
  This is a documented common-spreadsheet defense, not a guarantee for every importer.
  Text changes are deliberate; use JSON for lossless text. No spreadsheet app was run.

## JSON v1 / precision / dates

Compact JSON with recursively sorted object keys, no trailing newline or generated_at.
Root keys: format, metadata, rows, schema_version, summary. Rows contain the19 issue
fields plus vehicle_id, trip_time_zone, rate_source, rate_is_custom_override. Optional
keys are present as null; empty strings remain strings. Version/counters are integers,
flags booleans. Summary includes all classification miles, business counters, known
subtotal/completeness and totals_by_vehicle (ID, name_snapshots, distance_miles).

All Decimal distances/rates/money are locale-invariant NSDecimalString base-10 strings,
without conversion through Double, new arithmetic, display rounding or fixed scale.
Trailing fractional zeros may normalize away. Consumers must parse decimals, not floats.
Unknown/nonbusiness deduction remains null; known subtotal0 plus is_estimate_complete=false must
not be confused with a complete deduction. Explicit zero rates remain non-null0.
Odometers originate as model Doubles: use shortest round-trip decimal spelling through
Decimal, reject negative/nonfinite/unrepresentable/underflow or changed Double readings.
This cannot recover precision absent from storage; signed zero may normalize to0.

Gregorian/en_US_POSIX dates use the mandatory caller zone, not current/stored Trip zone:
date `yyyy-MM-dd`; both times full RFC3339 with milliseconds and per-instant DST offset.
Submillisecond precision is not retained. Dates outside Gregorian CE years1...9999 fail.
Both endpoint offsets must be whole minutes (`secondsFromGMT(for:) % 60 == 0`);
CSV/JSON throw invalidDate(trip.id) otherwise. Historical second-granularity offsets
are rejected, never truncated or silently reformatted to change the represented instant.
Caller must coordinate the same zone for options/rate factory/rendering; existing Report
has no zone field to validate this. Stored trip_time_zone is provenance, not a fallback.
Array order comes from the actual deterministic report. No cross-TZDB/SDK canonicality claim.

## Honest chronology / actual verification

1. Initial16/21-test absent-API snapshots were SOURCE ONLY; no compile RED was run.
   Added22nd exporter method for actual1971 Monrovia -2670-second endpoint offsets.
2. First actual full49 on unfixed exporter66e53f… / testd015bbc5…:47 passed,2 failed
   methods/3 assertions; old27 all passed. Timestamp CSV/JSON failed to throw, with no
   offset/row assertion errors. Formula JSON helper also lost leading FEFF. Thus NOT
   sole-intended RED; logs/receipts/unfixed sources remain preserved, never relabeled.
3. Coordinator's per-method review authorized only per-endpoint minute-offset guard096c494e….
   No production JSON encoding change. Separate admitted one-shot FEFF diagnostic captured
   actual1458 wire bytes retaining EFBBBF3D31; typed JSONDecoder retained [65279,61,49],
   JSONSerialization helper dropped FEFF on SAME Data and raw/escaped byte controls.
   Diagnostic0 meant capture complete, not tests pass; finding is host/runtime-scoped.
4. After actual-byte review, only formula method's oracle changed to typed JSONDecoder of
   actual writer Data. Private required-string Row maps decoded fields; ALL9 expected
   literals/CSV/assertions unchanged. All other48 methods and old27 file byte-preserved.
   Test hash changed d015bbc5…→4a1f0136… legitimately: NOT same-byte paired RED/GREEN.
5. First verification invocation failed plan-hash preflight, ZERO native launches/root/lock.
   Coordinator reviewed final documentation plan3f3b9c58… with unchanged controller/source,
   issued NEW admission2, then ONE actual unfiltered full49 SwiftPM pipeline succeeded:
   native0/controller0,49 starts/ends/passes,0 errors/failures/skips/unknown events.
   Both admissions and failed preflight output remain intact; no native retry was hidden.

Actual Mac package uses real six Foundation/SwiftData production files + two XCTest files,
no stubs or compatibility adaptations. tools5.9/macOS14 IsoMe/IsoMeTests, private cache/jobs2.
Existing27 = original18 plus9 catalog additions; exporter22 includes independent RFC4180
reader canary, escaping/CRLF/Unicode/formulas, date/DST/nonhour/historical offsets, snapshot
immutability, optional/unknown/zero rates, summaries, exact Decimal and deterministic bytes.
Typed JSONDecoder and independent CSV/literal oracles are not writer-only round trips.
All fixtures synthetic/nonPII. No unrelated accepted baseline rerun.

| Verified input | SHA256 |
|---|---|
| Exporter | `096c494eaaed2fd73bd828b43d7dba6e4cf9056d410ab292858ce7c764c00565` |
| Corrected full22 tests | `4a1f01367fd3ce95b1eaa84be36503e6e90cbe9e68c02847a81f582e7ed55e3b` |
| Unchanged legacy27 | `01bc4501e3e233c032539d3861ff2ec7ea918c383a1b95a872223d07803734eb` |
| ReportService snapshot | `c9a2090e36790a205416febd21ea7e65778aa0c62732da8973da88792e1dcfe9` |
| Frozen verification manifest | `fd894f4c6e53424c7429f53d936e2c37eb7bb7f9a97d9919d13eda2826f8cbb2` |

Primary verification artifacts under `/tmp/whereday-fleet-20261113/`:
`mileage-report-writers-verification-mac-owned-v1/phases/verify/{native.log,receipt.json}`.
Native log SHA6325162f2dec31d01f91f153c6db13c343a78970a17fe85b834a56e848707c05;
receipt SHA7ffe63308f8f15decf63672a4622fb577cbe40468395d6166ac50bd0a1f21fd6.
Peak sampled allocated disk133005312 bytes; min free23359975424; settlement9.995s,
pre-final10.040s, own group settled/lock released, no signals/stop. Sampling is NOT quota,
complete logical inventory or fork theorem. Source/receipt input hashes match before/after.
This documentation-only update does not change any of the eight executed source/test inputs.

## Remaining boundaries

Independent verification-artifact audit is active; commit waits for concrete acceptance AND
coordinator authorization. No commit, push, merge, new native run, SDK command or cache cleanup.
No UI/purchase path changed; future UI must use existing purchase gating. Signed iOS, actual
app/project builds, migration, UI/paywall, large-input performance and full Phase2/#61 remain
unverified. No tax/legal/accounting advice or compliance claim. No further execution authority.
All earlier scratch chronology/failures preserved; final report `mileage-report-writers-verified-report.md`.
