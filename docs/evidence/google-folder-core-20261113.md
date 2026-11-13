# Google folder discovery core — 2026-11-13

## Scope and contract

`GoogleFolderDiscovery` is an unused, Foundation-only component, not a working folder-import feature. No UI, worker, record-codec/default parser wiring or FileProvider download adapter is included. Phase5/#59 is not complete.

The caller supplies a stable, cooperating, already-local authorized root and holds any security scope through synchronous consumption. Static links/aliases, hidden/package scopes and special files are explicitly excluded; regular hard links fail closed. Root/ancestor/file identities are revalidated. These checks do **not** guarantee safety against hostile concurrent same-UID mutation.

`withDiscoveredFiles` supplies a manifest and callback-only reader with opaque session/candidate IDs. Every candidate must be consumed; foreign/repeated/omitted reads fail, and readers expire on callback exit or throw. Discovery either completes the eligible scope or returns no ready subset. Exclusions retain bounded diagnostics and totals. Content hints are filename-independent, not record acceptance: any future integration must use the authoritative full codec and enforce Google-only admission, with no backup restore/mixed mode.

| Bound | Ceiling |
| --- | ---: |
| Entries / regular files probed / candidates |10,000 /1,024 /256|
| Directory / JSON depth |16 /64|
| Per-file / total actual probe bytes |64KiB /8MiB|
| Shared planned and actual candidate payload |50MiB|
| Diagnostic details |32, plus category totals|

Probe and payload accounting are separate. An over-limit detection byte may be read but is not appended/returned as successful payload. Cancellation is checked between bounded operations; framework calls are not claimed preemptively interruptible. Ambiguous encoding/probe bounds fail closed. Known backup markers take precedence; candidates still require full codec validation.

## Actual macOS evidence

All runs compiled the actual module and XCTest source directly for arm64 macOS14 with the conditional standalone entry; no app, simulator, compatibility stubs, filters, shared cache or skipped Finder-alias case.

- **Initial38:**37 passed,1 failed. The Unicode fixture assumed created NFC `é` retained its byte rank; the returned `e`+U+0301 correctly sorted before `z` by UTF-8. Original patch, sources, receipt and logs remain preserved as real red, not relabeled a production/environment failure.
- **Strengthened oracle:** both fixture roots are created first. Independent pre-invocation directory snapshots verify the four created identities/name-byte sets. An explicit unsigned byte-lexicographic reference defines expected output before discovery. Actual byte vectors, cross-creation-order equality and monotonicity are checked; no discovery-output or canonical-String-equality oracle, and no production normalization policy.
- **P2 RED40:** unchanged production plus the strengthened oracle and exactly2 regressions:38 methods passed,2 failed with10 assertion failures,0 unexpected/skips. Both backup/non-array-Google key orders failed; eight tiny UTF16-BOM/UTF32BE prefix-budget assertions failed. All original cases, including bare malformed-Google rejection, passed.
- **Same-test GREEN40:** after minimal backup-precedence/bounded structural-continuation and conservative partial-header fixes,40 passed,0 failures/unexpected/skips. The test bytes were identical to P2 RED. No further run followed.

Both correction pipelines stayed below their60s compile+run deadlines (~5.88s each), used fresh private0700 temp/cache/output roots,2 compiler threads, continuous8GiB-free/512MiB-owned-footprint guards and exclusive fleet admission. Only their owned groups/roots/locks were settled and removed; source copies, binary hashes, logs and receipts were preserved. This was a bounded macOS exception, not app/iOS admission. **iOS39 remains unrun.**

## Frozen hashes and local evidence

SHA-256:

| Input | Hash |
| --- | --- |
| Reviewed542-line production |`88981563455d6ff8123af55d4d1a51150eadad588ab2cf39ec31da255693c9dd`|
| Same553-line40-test source in RED/GREEN |`e21ca962ba7f0abdd196604fb4bbba0dc40d5aec443fbb282f2b3dc7b1dbbe3d`|
| PBX (only8 new D05920 registration lines; old lines preserved) |`7ae98927e5a2a3bbc6ba03454ffc6eb8b15bfeef0f9d9084367349299e6bbf21`|
| Initial/RED531-line production |`9f4afe0d969437d4aaed13b5fe974fcc69b18667cce3135339c9e1b4d38e9e37`|
| Initial469-line38-test source |`37e74e71233d953a05b93fa0564211c635d0860beaf31afc1e937166d4f8bfb3`|

Full local reports under `/tmp/whereday-fleet-20261113/`: `google-folder-core-native-feedback-report.md` and `google-folder-core-corrections-report.md`. Preserved private evidence directories: `google-folder-core-native-feedback-997b-v1`, `google-folder-core-corrections-red-v1`, `google-folder-core-corrections-green-v1`. These machine-local artifacts are not bundled in this commit. The paired P2 regression red/green does not retroactively establish TDD for the earlier source-only milestone.
