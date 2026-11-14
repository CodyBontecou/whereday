# Hosted scale result and rejected row-reuse experiment

## Current published result: still red

Unchanged full-size gate run **43557730812**, head `bf035f191f388b1d4de16492ee6f494b6cdb5d37`, import-scale job132334281884: **four native tests, one pass, three failures, zero skips/errors**. Artifact14100404797 was downloaded and inspected, not inferred from the job icon.

| Case | Native elapsed seconds | Actual outcome |
| --- | ---: | --- |
| Original10k JSON control | 13.785 | Pass |
| Million Markdown | 1200.084 | TimeoutCancellationException,1200000ms |
| Million CSV | 1200.126 | TimeoutCancellationException,1200000ms |
| Million JSON | 1242.205 | Same1200000ms timeout; additional teardown latency, not a raised limit |

Actual phase logs show JSON initial review451599ms, initial commit180251ms, initial inspection93186ms. Review/commit were513235/232391ms in the earlier failed run43547932939. This is an observed cross-run comparison, not a calibrated causal estimate. JSON reimport still did not finish; its later inspection took113908ms and its interrupted final apply logged132703ms, including98161ms verification. Markdown/CSV also completed initial review/commit and timed out later. **The Instant candidate does not establish full-scale acceptance.** No unchanged retry was dispatched.

Retained hosted logs/artifact: `/tmp/whereday-merge-readiness/instant-codec-hosted-scale{.log,/}`. Original review/commit/deduplicate/local-edit/clear assertions,1M records and20-minute limits are unchanged. Road request-count ownership and the full downstream matrix remain open independently.

## Ranked next hypotheses

1. Immutable staged rows are decoded repeatedly by chronology/admission/write callers: reusing one row's decoded value should lower actual pipeline CPU, not just a microbenchmark.
2. Chronological joins fetch randomly keyed payloads from a WITHOUT ROWID table: a different private physical layout should reduce insert/read cost without changing returned identities/payloads/order.
3. Repeated full integrity scans are expensive. Any future work must retain their checksum/chronology coverage; skipping them is not an acceptable optimization.

Only hypothesis1 was implemented and tested in product source during this experiment. It was then **reverted**, not shipped. Hypothesis2 has only a synthetic host-SQL probe so far.

## Rejected candidate / feedback boundary

The candidate used one default thread-safe lazy decoded value on each immutable `ImportStageRow`. It was transient, excluded from row serialization, reset by data-class copy, never global and not shared across fresh pages/checksum scans. Kind/identity checks and the full checksum logic remained intact.

Actual RED preceded it: five codec methods ran, one reuse-identity assertion failed. Candidate GREEN: **152 platform JVM tests**, no failures/skips; **35 native cases**, no failures/skips (14 DiskHistoryImport,13 GoogleHistoryImport,seven ImportResourceBounds,original10k control). Tests covered point/visit identity, metadata, copied/malformed/wrong-kind rows and serialization remaining unchanged after reads. This verifies the cache mechanism/correctness, not the1M deadline symptom. No claim that an identity assertion alone is a performance regression test for that symptom.

A seconds-scale JDK17/Gson2.13.2 diagnostic read each of200000 rows three times, retaining only200 rows/page, with fixed128MiB heap. Two processes per variant, three measured trials each after warmup, verified identical metadata/read checksums. Old wall377–398ms versus candidate125–138ms; main-thread CPU371–388ms versus122–131ms. This isolates repeated decoding; it omits Android, SQLite, parsing, verification and transactions. The initial microbenchmark cache preceded the later transient annotation; serialization is not part of that measured loop.

The actual native pipeline did **not** provide equally strong evidence:

| Execution order | Original10k complete ms | Sampled task-clock ns | Sampled Java peak bytes |
| --- | ---: | ---: | ---: |
| Candidate after-1 | 9831 | 4160000000 | 70385040 |
| Published before-1 | 11346 | 4600000000 | 47803824 |
| Restored candidate after-2 | 11005 | 4610000000 | 116053520 |

Before-1 temporarily rebuilt the exact published production file (`git diff --exit-code` confirmed it). After-2 restored/rebuilt the candidate and reran its JVM suite before profiling. All three used the unchanged native control, same owned API35 read-only emulator configuration, same app-scoped simpleperf settings and strict native assertions. Each trace was nonempty and successfully parsed; zero recorder/native/parser exits. Traces/metrics/SHA256s are in the adjacent `native-profiles.json` and local `artifacts/merge-readiness/row-profile-*`.

One candidate run had lower sampled CPU; the repeat was essentially unchanged, and sampled heap peaks were higher (all below the existing192MiB limit). These few runs cannot establish a stable full-pipeline benefit, a memory regression's exact cause, or safety at1M scale. **Do not extrapolate the3× microbenchmark or the first good native run into a gate pass.** Rather than add complexity and restart an hour-long gate on weak evidence, the candidate was removed.

The exact rejected patch is adjacent for audit, not applied. Its unit/native results remain under `artifacts/merge-readiness/row-reuse-*`. Production and test files were restored byte-for-byte to `bf035f19`; the original test APK was rebuilt and **151 JVM tests passed** afterward so the current built artifact is not silently the cached variant. No repeated native corpus was claimed after that rollback.

## Next measured storage probe — not implemented

`layout-benchmark.py` reproduces the private records/google_order schema, duplicate lookup, bounded200-row chronological join and1MiB SQLite page cache using100000 fully synthetic records. It changes only records' WITHOUT ROWID declaration, checks every identity/order field and compares complete payload hashes across both layouts. Host SQLite, not Android:

- WITHOUT ROWID: insert3.531s; scans0.255/0.244/0.251s; file52137984bytes.
- Rowid table: insert2.855s; scans0.196/0.183/0.177s; file56258560bytes.
- Both plans use the chronology covering index and indexed identity join. Payload digests match. The rowid variant is about8% larger on disk.

This is a reason to construct an **Android-native storage counterfactual**, not proof to change production or rerun the full gate yet. No schema change, new Android layout test, public format change or deadline change has been made. Scripts/results are adjacent; private host databases were deleted by their temporary-directory context.

All owned native runs restored/read back animation settings, removed scoped packages/scratch, gracefully exited their emulator and stopped private ADB. No physical/default device or original dirty fleet worktree was touched. #114 remains draft and the overall goal incomplete.
