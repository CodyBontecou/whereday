# Priority remediation — November 11, 2026

Baseline main: `f003fe3e50e8066c0ca263b69d2f29ab6392d1a2`.
The owner's active working checkout remains untouched; work uses isolated worktrees.

## Current requirements and evidence

| Priority | Current evidence | Remaining gate |
| --- | --- | --- |
| PR cleanup | Integrated #104/#110/#111 and superseded #62/#79 closed, original descriptions preserved. Useful Android export instructions are in draft #114, including generator source and HTML/Markdown. | Merge/publish the guide with its verified PR. |
| Android baseline | #114 head `561febce`: all 61 affected UI tests and 13 unchanged road/overlap tests pass locally. Both review axes pass. Full hosted heavy run [43049439309](https://github.com/CodyBontecou/whereday/actions/runs/43049439309) completed with failures on this exact head. | Complete full hosted baseline and all million-record format gates. |
| Subscription #113 | Published `980cabb2`: 28 billing + 8 subscription-text tests, six native presentation tests, and all fresh-head CI checks pass. Final follow-up `8765f32a` passes 13 native subscription/navigation/dialog tests, build/lint and focused visual checks; it fixes sheet system-icon contrast. | Final QA additions and owner acceptance are published through `e242284c`; wait for fresh-head CI and baseline gates before merge. |
| Watch #84 / #75 | Published `1228bafb`: targeted 6 XCTest + 22 Swift tests and full signed 482 XCTest + 42 Swift tests pass. iOS CI passes. Both review axes pass. | #84 remains draft; paired hardware acceptance in #75 remains pending. No connected usable iPhone/Watch; requested hardware access has no response yet. |
| Localization | The owner explicitly accepted all current localizations. PR #113 head `e242284c` records 1,779 English inputs and ten pack hashes. Eight durable screenshots verify focused English, Arabic RTL/dark and Spanish large-text flows. | Actual spoken TalkBack verification remains in open [#115](https://github.com/CodyBontecou/whereday/issues/115). |

## Android scale diagnosis

Main heavy run `42845017675` reported three million-record timeouts at 1,200,000 ms,
plus 12 UI failures and an incomplete app selection. The 61-test fixes preserve
wire-format, saved-setting, delivery and draft-protection assertions. The remaining
road-display/overlap failures have not reproduced locally; 13 tests pass unchanged.

The local million JSON test on `561febce` also **failed the unchanged 20-minute
gate during reimport**. It completed first review in 675,473 ms and committed all
one million points in another 268,156 ms. Apply included 139,506 ms of stage
verification. A successful first import is not a complete scale pass.

Further stage changes are committed in `eea46060`, published via #114 head
`464598d9`, and remain pending full-size acceptance:

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

A manual heavy-suite selector (`all`, `database`, `import-scale`) is published and
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

Import-only hosted run `43054310121` now targets revised head `464598d9`;
the earlier full baseline completed on `561febce`: all 594 app cases ran, with 21 failures and two skips. Nineteen failures are Espresso close-keyboard callback timeouts; one calendar intent wait and one stale `Json` format label also fail. Later database/platform selections did not execute after the app failure.

Revised `464598d9` also failed the original local million-JSON gate during
reimport. First review took 755,652 ms (inspection124,802 ms), first apply
267,754 ms (verification125,597 ms). Overall timing varied with the shared host;
these results do not prove a full-size improvement. The exact failure logs are
retained. The identity-query change passes native verification: reuse same-snapshot review membership and fetch commit membership
in page-sized SQL queries inside the write transaction. It never trusts stale
review membership for commit.

## Identity queries and private null omission

The write transaction now loads existing IDs in bounded pages; review can reuse
membership loaded in the same snapshot. The private scratch codec omits absent
optional fields, reducing the sample point payload from 234 to 134 bytes. Public
backup serializers are unchanged. Null, zero, empty strings, metadata and session
hash distinctions remain covered. Both review axes found no defect.

All 34 native cases pass (13 disk history, 13 Google, seven resource bounds and
the 10,000-record control). The control completed in 10.124 seconds, with a
13,607,472-byte sampled Java peak under the 201,326,592-byte heap limit. This
compares with 15.700 seconds for identity batching alone on this shared host;
it does not establish full-size acceptance. The exact receipt and phase output
are adjacent. Original million-record requirements remain unchanged.

Hosted run `43049439309` completed all four scale cases: the small control,
million JSON (1,145.738 s) and million CSV (1,161.806 s) passed; million Markdown
timed out. The two ignored calendar cases in its UI selection were already
obsolete on main: calendar actions moved from Today rows to the editor/detail.
They were not newly skipped by this remediation. New optimized head `f922eeda`
passes 149 platform unit tests and all 34 native import cases; all three original
million formats are running in hosted run `43061793626`.

## Hosted UI follow-up awaiting runtime verification

Nineteen hosted failures originate in Espresso's missing IMM result callback.
The affected fixtures now request dismissal through the window controller and
wait for actual IME insets to become hidden, failing if they stay visible. Dialog
root selection and all product assertions remain intact. The existing real-IME
layout case also uses this helper to verify a visible-to-hidden transition. The
outing test uses the displayed protocol format name instead of `Json`. Both
review axes found no concrete defect, and both QA APKs build successfully. Native
runtime checks and the full hosted baseline remain pending; no passing fix is
claimed from compilation alone. The calendar intent timeout is still under
investigation.

## Final local keyboard verification and latest hosted result

All 86 active cases across the eight affected fixture classes pass without skips
on the revised helper/selector build. A separate real software-keyboard case also
passes: the IME must first be visible, then hide, while the invalid date draft,
disabled Apply and visible Cancel remain intact. Exact case/APK receipts are
adjacent. The old tabletop case instead fails its pane-containment assertion
before calling dismissal; that assertion is retained and tracked as [#116](https://github.com/CodyBontecou/whereday/issues/116).

Hosted database run `43064053407` on `39b9e667` has no keyboard callback failures.
It stops after an outing-editor input failure (missing `outing-name`) and an
incomplete replay-localization case. Only 335 of the expected 594 cases were
received; this is not a baseline pass. Exact downloaded reports remain in
`/private/tmp/whereday-hosted-baseline-43064053407`. The editor transition and
instrumentation termination still need diagnosis.

The local million JSON run on the identity/null path also times out during
reimport at the original 1,200,000 ms deadline. It reviewed in 525,739 ms and
committed all one million points in 280,493 ms (136,234 ms verification). This is
a failed complete gate despite the successful first commit. Hosted all-format
run `43061793626` is still live. No duration, count, cleanup or memory requirement
was relaxed.

Hosted scale run `43061793626` is now terminal: all three original million-record
formats timed out at 20 minutes. The adjacent per-format phase receipt retains
the measured first imports and incomplete reimports. Further profiling/bisection
is required; the smaller control and successful first commits are not substitutes.

A 22-case outing/replay/keyboard follow-up could not start instrumentation: Android
killed the QA process for a startup ANR before any test status arrived. Its captured
main stack is in WorkManagerInitializer → WorkDatabase.create → RoomDatabase.Builder,
with high device CPU pressure. This is not a replay-test reproduction or passing
verification. The new explicit editor wait is compiled and statically reviewed but
still awaits native/hosted runtime verification.

The hosted UI wrapper now preserves the exact 94-class selection and original
Gradle arguments, collecting logcat, exit information and memory before emulator
teardown. Each diagnostic has its own 15-second bound and cannot replace the
original test exit code. Bash syntax, actionlint and diff checks pass.
