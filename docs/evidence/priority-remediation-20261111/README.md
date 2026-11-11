# Priority remediation — November 11, 2026

Baseline main: `f003fe3e50e8066c0ca263b69d2f29ab6392d1a2`.
The owner's active working checkout remains untouched; work uses isolated worktrees.

## Current requirements and evidence

| Priority | Current evidence | Remaining gate |
| --- | --- | --- |
| PR cleanup | Integrated #104/#110/#111 and superseded #62/#79 closed, original descriptions preserved. Useful Android export instructions are in draft #114, including generator source and HTML/Markdown. | Merge/publish the guide with its verified PR. |
| Android baseline | #114 head `561febce`: all 61 affected UI tests and 13 unchanged road/overlap tests pass locally. Both review axes pass. Full hosted heavy run [43049439309](https://github.com/CodyBontecou/whereday/actions/runs/43049439309) is active on this exact head. | Complete full hosted baseline and all million-record format gates. |
| Subscription #113 | Published `980cabb2`: 28 billing + 8 subscription-text tests, six native presentation tests, and all fresh-head CI checks pass. An added real Settings navigation test also passes locally (seven native tests together). | Finish visual/RTL/large-text checks, review/publish final QA additions and merge after baseline gates. |
| Watch #84 / #75 | Published `1228bafb`: targeted 6 XCTest + 22 Swift tests and full signed 482 XCTest + 42 Swift tests pass. iOS CI passes. Both review axes pass. | #84 remains draft; paired hardware acceptance in #75 remains pending. No connected usable iPhone/Watch; requested hardware access has no response yet. |
| Localization | Open [#115](https://github.com/CodyBontecou/whereday/issues/115) records native-language, RTL, large-text and TalkBack acceptance. Retention review pack prepared with 51 English strings and ten translation inputs/hashes. | All native-language approvals and broader actual-device accessibility acceptance remain pending. |

## Android scale diagnosis

Main heavy run `42845017675` reported three million-record timeouts at 1,200,000 ms,
plus 12 UI failures and an incomplete app selection. The 61-test fixes preserve
wire-format, saved-setting, delivery and draft-protection assertions. The remaining
road-display/overlap failures have not reproduced locally; 13 tests pass unchanged.

The local million JSON test on `561febce` also **failed the unchanged 20-minute
gate during reimport**. It completed first review in 675,473 ms and committed all
one million points in another 268,156 ms. Apply included 139,506 ms of stage
verification. A successful first import is not a complete scale pass.

Further stage changes remain uncommitted pending full-size acceptance:

- Skip point-duplicate SQL only for buckets above the maximum read inside the
  same transaction; retain matching for equal/older buckets and reset correctly.
- Hash payload/metadata and chronology in one validated, bounded scan. Preserve
  payload identity/time checks, table/join completeness, both transaction-boundary
  verifications, cancellation and rollback. Reuse the four-byte length buffer.
- Add regressions for descending admission, cross-call duplicates/reset, missing
  and orphaned index rows, valid altered payloads, timestamps and metadata.

The revised path passes **148 platform unit tests and 33 native import tests**
(12 disk-history, 13 Google, 7 resource-bound, one small control). Both static
review axes report no concrete defect. The small control completed in 16.144 s;
earlier controls ranged 14.896–20.560 s, so no full-size performance conclusion
is established. Original million counts, local-edit preservation, deduplication,
cleanup, memory and duration assertions remain unchanged.

A manual heavy-suite selector (`all`, `database`, `import-scale`) is prepared and
passes actionlint. Scheduled/default dispatches run both suites; scoped concurrency
allows a new scale run while the current full baseline continues.

## Other verification

- #113 CI on `980cabb2`: Android build/unit/lint/provenance, map-point scale,
  actual location recovery, purchase verifier, analytics contract,
  Android-to-SwiftData interop and iOS XCTest all pass.
- #114 website validation `43049408332` passes on `561febce`. Locally: 58
  JavaScript tests, 16 Python tests, full build/check (39 pages, 1,753 local
  targets), and stable second-pass generation. CI caught the initial generated-only
  edit; `98c6d31a` corrects the source and generated Markdown.
- Latest Watch full bundle:
  `/private/tmp/whereday-watch-fix-20261111/artifacts/test-results/IsoMe-20261111-111737-16675.xcresult`.
  This run needed no UI intervention. An earlier StoreKit simulator sign-in was
  canceled and notifications denied; no credentials were entered.

## Evidence and isolation

Adjacent verification receipts retain exact passing native cases, APK hashes,
and disposable-emulator identity checks. The batching receipt predates the later
single-scan change; the single-scan receipt covers the revised path.

Native QA uses only the task-started `whereday-priority-20261111-api35`
(`emulator-5558`, guest Vulkan enabled for MapLibre) and disposable mapQa or
platform test packages. The owner's main package, other emulators and physical
phones are not test targets. No release or store submission is established here.

## Audit correction

Google imports still use one file. Multiple selection supports matching multipart
Whereday backups; `TimelineImportService.preview(urls:)` rejects multiple
non-backup files. #79 was superseded because newer guides exist, not because
its single-file import instruction was wrong. Keep the broader import epic #59 open.
