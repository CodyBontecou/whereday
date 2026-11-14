# Private point tuples — measured candidate, full-scale acceptance pending

## Storage-layout hypothesis tested and rejected

The earlier host SQL probe was followed by a real API35/SQLite3.44.3 diagnostic, not promoted directly to production. Four100000-row runs used ABBA layout order,1MiB SQLite cache,200-row pages and representative private point payloads/UUID identities. Every identity, timestamp/order field and decoded point was checked; all12 complete payload SHA256 scans matched. Source and raw metrics are adjacent.

The first WITHOUT ROWID run had substantial startup overhead (insert10907ms); it is retained, not used as a3× layout claim. Warm WITHOUT ROWID insertion3467ms versus rowid3213/3191ms; warm query-only scans281/288/315ms versus rowid244–264ms. Private files grew34168832→37437440bytes (~9.6%).

A separate full-controller100000-point control then measured **41381ms/39431process-CPUms published versus41554ms/38901CPUms rowid**. Review, commit, deduplication, local-session/accuracy preservation and clear assertions all ran. Whole wall time was flat/slightly worse despite faster isolated SQL. The rowid candidate was **rejected and fully reverted**; no schema change is included here. `DiskHistoryImportStage.kt` and the original scale-test class have zero diff.

The first attempt to run the added control failed JUnit initialization because its expression body inferred Log.i's Int return; it performed no import and is not a benchmark sample. Explicit `runBlocking<Unit>` fixed the diagnostic. That failed receipt remains at `layout-control-before-1`, not hidden or called a product regression.

## Narrow retained candidate

Only the live private `ImportStageCodec` point representation changes. A streaming TypeAdapter writes exactly11 fields:

`[id, latitude, longitude, time, accuracy, altitude, speed, outlier, countryCode, timeZone, countryResolutionAttemptedAt]`

Both timestamps use the already-verified exact integer second/nanosecond codec. Null slots remain explicit; unknown/known-zero/empty values, negative zero, finite Double extremes, nanoseconds and UTF-8 strings survive. The reader enforces field types, finite numbers, required time, coordinate validity and exact tuple length. No reflection/name lookup is needed for each private point decode. Visits retain their existing private object encoding.

This Gson is restricted to ephemeral workspace payloads and same-review baseline hashes. Public JSON/CSV/Markdown exports/readers, Room schema, source identity, admission/selection, transactional rollback, cancellation, page/byte limits, integrity scans and the fields covered by checksums remain unchanged. Workspace hashes are computed/compared with the same codec during that live review. Stale workspaces are reaped, not replayed across restart; no public-format or persisted-workspace compatibility promise is changed.

## Actual tests and counterfactual measurements

- Actual RED: six codec methods ran; the new fixed-point-representation assertion failed, the other five passed.
- Follow-up RED: seven codec methods ran; a nonfinite-writer rejection test failed. Gson calls custom adapters with a lenient writer, so direct JsonWriter calls had bypassed its normal reflective finite-double validation. Explicit finite checks now reject NaN/Infinity in accuracy, altitude, speed and legacy malformed coordinate objects, preserving the old rejection policy.
- GREEN: **154 platform JVM +948 export JVM tests**, zero failures/errors/skips. Complete metadata, nullable fields, zero/empty distinctions, Instant.MIN/MAX, negative-zero/finite Double extremes, malformed tuples and hashing semantics are exercised.
- Actual native correctness/resource corpus: **35 cases**, zero failures/errors/skips (14 DiskHistoryImport,13 GoogleHistoryImport,seven ImportResourceBounds,original10k control). Existing tamper/chronology/checksum, duplicate/replacement/local metadata, cancellation/rollback and bounded resources remain tested. No row cache was introduced.

The separately added100000-point control called the unchanged full-controller exercise. It is a diagnostic, **not** a smaller replacement for any million-record case and not a full-scale pass:

| Variant / run | Complete ms | Process CPU ms | Sampled Java peak bytes |
| --- | ---: | ---: | ---: |
| Published point objects / layout-control-before-2 | 41381 | 39431 | not retained |
| Point tuples / point-control-after-1 | 37844 | 34335 | not retained |
| Object codec counterfactual / point-control-before-3 | 40242 | 39601 | 55278160 |
| Restored tuples / point-control-after-2 | 35657 | 33966 | 50133424 |
| Final finite-guarded tuples / point-control-after-3-finite | 37398 | 34366 | 50424384 |

The second object counterfactual disabled only TrackPoint adapter registration, leaving its unused definition present; its runtime marker confirms `{`. Restored candidate confirms `[`. This is not claimed byte-identical to the whole published production file; it isolates the encoding choice. Both runs used identical extra diagnostic logging. Initial before/after pair predated heap/encoding markers; neither pair changed original assertions.

Two initial repetitions and the final guarded run show roughly7–11% lower elapsed and13–14% lower process CPU in this **100k JSON control**, not all imports/devices. Sampled heap is only one paired observation, not a calibrated memory-improvement claim; both are below the existing192MiB heap limit. API35 owned read-only emulator,2cores/2GiB, private ADB5064/emulator-5586, same canonical strict instrumentation runner. Repeated measurements include real storage/controller work, not only the encoder microbenchmark.

## Preserve the original hosted gate

After measurement, the additional100k method and its conditional heap logger were removed from the shipped test class. Their exact patch is retained adjacent for audit/reproduction. `git diff --exit-code` confirms the original `DiskHistoryImportScaleTest.kt` is unchanged: original three1M methods,10k control,25MiB requirement,20-minute timeout, local-edit/dedup/clear assertions and original test ordering are preserved. No extra warmup was left in the hosted gate. The temporary SQL probe class was also removed from app test sources; its source is evidence only.

The canonical test APK is rebuilt after this cleanup. Native35 coverage above ran the same candidate production logic; only the temporary unselected diagnostic method/logger was subsequently removed. No native result is being relabeled as million-scale acceptance.

Prior published-head run43557730812 still has three1M timeouts. The first point candidate6ae9d013 briefly started run43569920207; it was cancelled when the new nonfinite-writer RED exposed that correctness edge. It is superseded, not a performance retry or a passing result. The corrected finite-guarded candidate was remeasured above and all35 native cases passed again after diagnostic cleanup. This corrected candidate must now face the original full-size hosted gate. Keep #114 draft: full-scale performance, actual road four-call ownership and the complete downstream baseline remain unresolved. No completion claim follows from this receipt.

Raw local receipts: `artifacts/merge-readiness/{row-layout-native-1,layout-control-before-1,layout-control-before-2,layout-control-after-1,point-control-after-1,point-control-before-3,point-control-after-2,point-tuple-native-35,point-control-after-3-finite,point-finite-native-35}` and point-tuple build/unit logs. Adjacent JSON records exact values/native verdicts. All owned runs restored/read back animation settings, removed scoped packages, gracefully exited the emulator and stopped private ADB; the dedicated metric collector stopped. No physical/default device or original dirty fleet lane was touched.
