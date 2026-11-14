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

## Open merge gates

Keep draft until current-head complete UI/database + downstream selections and all three unchanged million-record formats pass. Historical million-record run **43079881479** succeeded at `585facc5`; it does not prove the newly integrated head. Current-head ordinary hosted checks, native diagnostic execution, and heavy acceptance remain required.
