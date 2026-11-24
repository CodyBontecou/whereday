# Private staging timestamp candidate — measured, full-scale acceptance pending

## Failure surface

Hosted run `43547932939` timed out each million-record JSON/CSV/Markdown scenario at the unchanged 20-minute deadline. The actual logs show first review and first commit completed; timeout occurred during reimport, not an unbounded input read or missing test result. JSON review took 513,235 ms, first commit 232,391 ms, and its last inspection 122,151 ms. All original assertions remain required.

## Actual profile, not an exit-code proxy

Recorded the unchanged `DiskHistoryImportScaleTest#tenThousandJsonPointsReviewCommitDeduplicateAndClear` using installed NDK 27.1 simpleperf, app-scoped `task-clock:u`, 200 Hz, native/managed call chains, 20-second recording request. Private read-only API 35 emulator, two cores/2 GiB, ADB 5064/emulator-5586; no physical/default device or external import data.

The first recorder command returned zero but its child failed creating a post-processing file after package cleanup: the profile was **empty and unusable**, preserved as `import-profile-before-1` with additive `profile-audit.json`. It is not a successful CPU profile. A scratch copy of the strict native runner then held only host-side package cleanup until profiling post-processing completed; native assertions/parser/time budgets were unchanged. Validity checks require nonempty data and successful host report parsing, not merely recorder exit zero.

The valid baseline has 1,061 samples. Inclusive call-chain attribution: `ImportStageRow.point` 22.05%, Gson decoding 21.30%, stage digest 19.60%, `Instant.parse` 11.22%. These overlap and must not be added. This supports investigating repeated private decoding/date conversion rather than changing checksum coverage or the public parser speculatively.

## Narrow candidate

`ImportStageCodec` alone now streams exact `[epochSecond, nanosecond]` integer tuples with a null-safe Gson TypeAdapter. It rejects fractional/exponent/string/boolean integers, invalid tuple length, normalized/out-of-range nanoseconds and unrepresentable instants. No Double conversion is used. Optional nulls and all existing metadata are preserved.

This Gson is only for live, non-resumable private staging and baseline hashes. Public JSON/CSV/Markdown backup serialization, source identities, duplicate matching, read snapshots, transactional writes/rollback, stage checksum contents, chronology validation, page/byte ceilings, cancellation and cleanup remain unchanged. Stale workspaces are reaped, never automatically replayed across app restart; no persisted-workspace compatibility promise is introduced.

## Test chronology / correctness

- Added two unit tests covering exact numeric representation, negative epoch nanoseconds, Instant.MIN/MAX, null, and malformed/precision-losing tuples. Actual RED: four codec methods ran, the new representation assertion failed; the other three passed.
- Final **151 platform JVM tests +948 export JVM tests pass**, zero failures/skips. Existing public export tests are unchanged.
- Actual strict native verification passes **35 cases, no skips**: 14 DiskHistoryImport, 13 GoogleHistoryImport, seven ImportResourceBounds, and the original 10,000-point control. This includes metadata, scratch tampering/chronology/checksum, cancellation/rollback, duplicate/replacement behavior and bounded resources, not only a codec round trip.
- Local receipts: `artifacts/merge-readiness/instant-codec-{red,final-unit}.log`, `instant-codec-native-35/{session.json,native/verification.json,native/instrumentation.log}`.

## Repeated comparison — no wall-time improvement claimed

Two valid profiles per representation used the same unchanged control, recording options and owned emulator configuration. The second baseline temporarily rebuilt the exact original production source (`git diff --exit-code` confirmed it); the candidate was restored and reverified afterward. A separate attempted after run refused a port-in-use preflight before boot; it is not a benchmark sample. No listener remained; the scratch preflight subsequently allowed TIME_WAIT reuse while still refusing an active bind.

| Version / session | Native complete ms | Sampled task-clock ns | Samples |
| --- | ---: | ---: | ---: |
| Before / before-2 | 13,209 | 5,305,000,000 | 1,061 |
| Before / before-3 | 10,510 | 5,150,000,000 | 1,030 |
| Candidate / after-1 | 11,846 | 4,760,000,000 | 952 |
| Candidate / after-3 | 12,109 | 4,550,000,000 | 910 |

Mean elapsed time is effectively flat/slightly worse (11,859.5 vs 11,977.5 ms), within observed run variance. Sampled app CPU work is about 11% lower in these two pairs; this is not a calibrated population estimate. The first candidate profile attributes only 3.05% to `Instant.parse` versus 11.22% before; public source parsing still uses it. Sampled peak Java heap was roughly 52–57 MB, with a 192 MiB limit; no memory improvement or device-energy claim is made.

Trace hashes and exact metrics are in `import-instant-codec-profiles-20261114.json`; raw traces/phase logs/parsed reports remain under `artifacts/merge-readiness/import-profile-{before-2,before-3,after-1,after-3}`. All owned runs restored/read back animation settings, removed scoped packages/scratch, gracefully exited the emulator and stopped the private ADB server.

## Open acceptance

This candidate must face the **unchanged full-size hosted gates** before any performance acceptance. Small-control CPU attribution is not a million-record pass. Keep #114 draft: road resume's exact request count, complete UI/database/downstream matrix, and all three full-size formats remain open. If the full-size gates still fail, retain the result and reconsider the candidate/next measured bottleneck rather than raising deadlines or declaring success.
