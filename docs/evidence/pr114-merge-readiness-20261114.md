# PR #114 current-main integration and baseline diagnosis

## Current-main verification

Merged main `5344145e` without conflicts (`bb1882d7`).

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A'
```

Full iOS run at `bb1882d7`: **491 XCTest cases, zero failures; 20 Swift Testing tests passed**. Local log: `artifacts/merge-readiness/pr114-ios.log`; the runner prints the result bundle path. Subsequent changes `73a3768b` and `9641ec66` only add Android test failure diagnostics, reused from the existing baseline investigation lane.

## Heavy run audit

Downloaded `android-database-reports` from hosted run **43083419021** (head `4b74f395`). Its native `instrumentation.log` contains all 594 announced results, the two original ignores, and **three actual assertion failures**. Unlike earlier runs, this is not a missing-result/transport-only failure:

1. `RoadDisplayProducerTest.coveredSourceReleasesAdmissionAndLateWorkerCannotReplaceItsSuccessor`: final resume wait (`calls == 3 && rendered roads > 0`).
2. `CalendarEventFlowTest.exactOutingHandsOffStoredBoundsAndFullRouteFirstPointWithoutMutation`: intent handoff wait.
3. `VisitLinkFlowTest.pastVisitLinkKeepsBothSuppliedTimesThroughTheNormalSavePath`: editor does not close after Save.

Native summary: `Tests run: 592, Failures: 3`, elapsed 3153.102 seconds. The strict completeness verifier correctly rejected the run. Downstream suites and import-scale in that run did not execute.

## Next diagnostic surface

Existing failure-only probes now capture the road controller/native-map/focus/lifecycle state, calendar handoff count and semantics, and the visit editor's validation semantics before teardown. No assertions, selections, expected ignores, timeouts, or retries changed. No production map behavior was changed on speculation.

These probes distinguish lifecycle/focus/rendering state, missing action delivery, and validation errors. They are not fixes or proof that the baseline passes.

## Completed heavy-run audit and native synchronization fix

Run **43547932939** at `80ee5cb5` finished, not green. Native UI results contain all **594** starts/completions: **590 passed, two failed, exactly two original ignores**. Failures:

- Road resume: calls=4 instead of exactly 3; the controller is enabled/resumed/foreground and the native road is rendered with focus/style/attachment all valid. This narrows the problem to request/lifecycle ownership rather than missing rendering, but does not establish its root cause.
- Workspace posture transition: native accessibility dialog bounds still describe the preceding book position while the owning pane already reports tabletop bounds.

All three million-record formats (Markdown, CSV, JSON) timed out at the original **1,200,000 ms** each. No limit was increased; no format passed on this head. Downstream database selections remain blocked by the UI failure.

On a private read-only API 35 emulator (ADB 5064, emulator-5586), the existing bounded map trace draft was copied into a separate diagnosis checkout without changing the original dirty lane. The original geometry assertion failed twice with exactly the hosted rectangle mismatch. A bounded native-bounds wait passed the same containment checks. A proposed controlled road-focus test passed even on old production code and was discarded; it is not a demonstrated regression or justification for a production fix.

The retained fix **f68aa519** changes only the posture test's existing five-second native-window helper to wait for the expected owner containment, not merely existence of any old dialog bounds. The final `owns` containment/positive-size assertions remain intact. No production geometry, retries, class selection or timeout was changed.

A clean PR checkout without the trace draft passed **all six original WorkspaceDialogTest native cases**, unskipped, using the strict instrumentation verifier. Evidence: `artifacts/merge-readiness/workspace-clean-1/{session.json,native/verification.json,native/instrumentation.log}`. Original synthetic native animation settings were restored/read back, the owned read-only emulator exited gracefully and the private ADB server stopped; no physical/default device was used. The separate diagnostic trace remains outside this PR.

This narrow local result does not prove the full hosted baseline or resolve the road request-count/performance failures. No unchanged expensive heavy run was restarted merely for the test synchronization change.

## Latest measured resolution and remaining merge gates

The original full-size import gate **43570950739**, exact candidate `6a6b7fe0`, now **passes all four original cases**, zero failures/errors/skips. The million-record Markdown/CSV/JSON cases completed in920.583/732.981/724.206seconds, within the unchanged20-minute limit, including review, commit, reimport/dedup/local-edit preservation and clear. Downloaded XML and phase logs, not only the green check, are captured in [the point-tuple receipt](import-point-tuples-20261114.md). Database was correctly skipped for that import-only dispatch.

The actual four-call road pattern is now traced and corrected: the resumed coroutine admitted work with a stale composition-time focus Boolean although both live WindowInfo and native focus were false. Six nominally green old-code class runs still contained four actual requests, exposing a synchronizing-read race in their predicate. The strengthened unchanged-deadline/exact-count regression fails clean old production with calls=4; a live snapshot focus wait before foreground admission fixes it. **24 traced and24 clean repeated native cases**, plus the related **16-case road/consent/workspace selection**, pass. **107 app +154 platform JVM cases** pass. No observer/reflection/native-view probe ships. See [actual RED, trace and clean GREEN](road-resume-focus-20261114.md).

The app-only focus correction changes no platform import source, dependency, schema or scale-test input from the passing `6a6b7fe0` component. Preserve that completed gate rather than repeating unaffected work.

The completed run43575545218 now verifies the **full original594-case default selection**:592 passed, zero failures/errors and exactly the two original ignores. It then passes expanded-workspace32 and short-keyboard2, but fails **eight of23 WorkspaceActivityResizeTest cases**. Later database/downstream selections did not execute. These are additional real blockers, not a green full baseline.

New ordinary recovery failed only combined reboot after eight successful prefix cases. The separate hosted observation reproduced it with the cached Activity demonstrably DESTROYED after visit-service acknowledgment and before route start. The production foreground guard is correct. A test-only live ActivityScenario owner resolver plus a deterministic actual-recreation RED→GREEN regression corrects the fixture while preserving the original focus-wait budget and service deadlines. Both native lifecycle cases and a canonical actual-process combined reboot pass locally. Hosted run43583502993 at871b40fd now also passes all nine original recovery cases; actual native logs/proofs were downloaded and audited, not only the green status. [Exact trace, regression, gate audit and still-open expanded failures](location-owner-20261114.md).

Follow-up camera work separates an inherited-padding test quantity mismatch from a real consumed-focus replay bug. The literal camera case retains its exact assertions with explicit zero-padding input; a new padded visual-center case preserves the removed ambiguity's coverage and fails a deliberately broken conversion counterfactual. The production focus fix retains an already-consumed request through transient null selection, while the next new request still recenters. The first clean full resize selection after that fix had **24 run,19 pass/five fail**; all eight visit-map focus cases plus107 app JVM cases pass. See [camera contract/counterfactual](workspace-camera-contract-20261114.md) and [actual focus replay trace/correction](workspace-focus-replay-20261114.md).

The five remaining failures were traced to obsolete navigation fixture assumptions: scrolling floating map chrome, the old map replay text entry, expecting the timeline list while its replacing outing detail is active, and opening a Map day sheet while still in Settings. Actual native semantic captures retain each failure boundary. Corrected fixtures preserve all23 original methods and assertions for real actions/data, add explicit visibility and both-size Back-to-timeline checks, and now pass the **entire24-case resize class** (104.658seconds, zero failures/errors/skips). See [native RED boundaries and clean full-class acceptance](workspace-navigation-fixtures-20261114.md). No additional production change or deadline/selection relaxation was needed.

Current-head hosted run43588141287 at603efd83 fails the default selection with591 pass/one failure/exactly two original ignores, before expanded/downstream execution. The sole failure is the initial selected-pin rendered-feature query atNativeMapInputReadinessTest.kt95, not its later resume checks. Twelve isolated native cases and an exact38-case original-prefix control pass locally. Observer-only hosted run43597995738 also passes38/38, with the original first query returning one pin and57 retained events/zero drops; it does not reproduce or explain the zero result. These are not a fix. Later observer-only full run43599667798 completes594 cases with469 pass/123 fail/two original ignores: first pin query still returns1, but the earliest failure is case5's photo-dot pixel assertion and the picker later times out at its first selection. This is a different unresolved pattern, not baseline acceptance. A bounded post-bitmap power/focus/screenshot probe is isolated on the diagnostic branch; no production assertion or original deadline is changed. See [actual failure boundaries, controls and hosted observations](native-picker-initial-pin-20261114.md).

Keep draft until that boundary, the full hosted expanded context and complete database/downstream gates are verified on the integrated head. The earlier intermittent photo-library measure/layout exception has not recurred locally but is not claimed causally fixed. No merge-ready/completion claim.
