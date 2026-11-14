# Scoped Google import progress — verified component, not epic acceptance

Isolated follow-up to PR119/a64231c0 on `feat/import-staged-progress`. No main merge, PR merge, deployment, permission bypass or public-format/schema change. PR119 itself is not updated by this receipt.

## Requirement → artifact

| Requirement | Implementation / evidence |
| --- | --- |
| Scanning/parsing/checking/saving stages | Worker preview reports scanning before authorized metadata/folder discovery, parsing per file, and duplicate planning. Commit rechecks current storage, stages inserts, reports indeterminate finalization, then completes **after** the single save succeeds. Backup restore follows the same reporting boundary. |
| Observations must not alter admission | Optional `ImportProgress.Operation` flows through existing ViewModel/service entry points. Defaults preserve callers without progress. No callback result is consumed, no waits/retries added, no cancellation/byte/record/transaction limits changed. Folder validation still must finish before a preview returns. |
| Old/canceled operation cannot overwrite another | Per-sheet StateObject, UUID-scoped operation, cancellation/end invalidation. Actual native RED on old implementation: queue400/500, reset, await original queued post; next import incorrectly becomes400/500 (**one test/two failures**). Current regression retains the original zero assertions and passes. |
| Coherent progress | One published snapshot; stage order and counts cannot regress; contradictory stage totals/negative values rejected. Unknown total differs from known zero. No shared singleton or unscoped worker post. Staged rows are not acknowledged as durable; finalizing uses a spinner, completion is separate. |
| Real persistence, not only a UI model | Native integration previews1,001 synthetic Google points with zero writes, observes actual planner progress, commits and checks1,001 visible rows at completion. Existing six-model backup round-trip test now also observes terminal6/6 only with the real restored CountryStay visible. All original field/round-trip assertions remain. |
| New localized labels | Three keys/33 new values across all11 locales. Existing2,396 catalog entries and every original byte unchanged; Saving import label remains the old approved entry. Two Python tests inventory actual UI titles and validate all four labels.44 actual compiled Bundle lookups pass; the earlier compiled bundle genuinely fails Arabic Scanning files with the missing sentinel. |
| Reference compatibility | Real Swift Google and duplicate producers rerun:50+50 case outputs identical, only provenance changes. All31 actual workflow source checks pass. Android resource check1,730/ten packs unchanged. |
| Signed regression gate | Final `scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9DBF4207-832B-4A34-88DB-AE841A6383DA'`: **589 XCTest +20 Swift Testing**, zero failures. Result `artifacts/test-results/IsoMe-20261114-132328-73453.xcresult`; full log `artifacts/merge-readiness/import-progress-full.log`. Earlier focused46/46 includes six progress,26 import and14 backup methods. No original assertions/selections/signing/Keychain setup weakened. |

Existing CI gains the two targeted localization tests before the unchanged signed runner and actual compiled44 lookups afterward. Original30-minute budget unchanged. The global catalog audit is **still RED160 missing values**, all in16 pre-existing English-only Google feature entries; this slice does not quietly skip or misreport that inventory. Other folder/error copy localization also remains incomplete. No native-speaker certification.

## Actual synthetic native walkthrough

Fresh explicitly owned **1778B03A-D2F5-4656-B92A-8CDEAF3C2755**, `whereday-import-progress-ui-20261114`, iPhone17Pro/iOS26.5, UI-only/no XCTest. Real app plus separate synthetic Files helper; production app file sharing unchanged. Fixture: two raw Google JSON files/150,000 unique points/13.8MB/one excluded note, fixed synthetic12/34 coordinates and2020 times. Existing onboarding bypass only; no import-preview injection or billing/permission fixture grant.

Recorded before first launch. Actual Archive→Settings→Import→system Files→helper→SyntheticProgress→Open. Review shows150,000 points/one inferred visit/two Google files/one exclusion, before confirmation. One actual confirm shows **Saving import…**, indeterminate spinner and150,001-of150,001 staged records while the transaction is finalizing; then actual completion150,000 points/one visit/zero duplicates. Done returns Settings with150,000 points/one visit. No second write.

Second recorded path uses **dark/accessibility-extra-large** and repeats review of the same folder. Actual Checking duplicates title wraps without clipping, native spinner visible; duplicate-only review disables Import, and Cancel preserves150,000 points. Scanning/parsing finished too quickly to establish a stable visible frame in the first light walkthrough; no artificial minimum duration, render hold or fake delay was introduced. Worker/resource checks are not substitutes for those unobserved slow-provider UI states.

Argent tool discovery returned no tools. Used existing AXe/simctl fallback, semantic targets where available; Files exposes only partial accessibility, so its observed Browse/folder/Open screenshots supply the documented coordinates. No Argent YAML, automated replay verdict, flawless full-motion pass, release60fps or physical-performance claim. Videos were recorded and selected temporal frames inspected, **not watched in their entirety/frame by frame**.

The first observer incorrectly waited for `confirm-import`, but the native tree exposes the existing inherited `import-review-screen` identifier on footer buttons. Its60-second observer bound expired despite the app already being ready; retained115+ raw snapshots establish that this was an observer-selector failure, **not a parsing/import timeout**. Corrected checks use the observed exact button label. No gate was weakened to relabel that failed observation.

Visual inspection also found a **pre-existing completion-copy defect**, “Two days of days now show in Atlas.” It is not fixed or hidden by this progress slice; completion-copy/localized full-flow polish remains required. Existing approved catalog values were not overwritten.

Raw `/tmp/whereday-merge-readiness/import-progress-ui-1/`: full light538.681667s movie, dark/XL137.561667s movie, all trees/screenshots/fixture/cleanup and operation times. Adjacent verification records retain media hashes and selected actual frames. Restored/read-back light/large, terminated app, uninstalled synthetic helper, verified guest Shutdown and both recorders gone. Test-host9DB also remains separate; no physical/default guest touched.

## Still open

Native slow scanning/parsing, errors/cancellation during materialization, cloud/stale/denied FileProvider coordination, all new Google copy locales, completion-copy correction, full localized/spoken/motion states, inference controls/device performance and Watch/widget acceptance. Current-head hosted verification and authorized main/catalog integration are separate. This component does not complete #59, PR119 readiness or the thread goal.
