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

## Open merge gates

Keep draft until current-head complete UI/database + downstream selections and all three unchanged million-record formats pass. Historical million-record run **43079881479** succeeded at `585facc5`; it does not prove the newly integrated head. Current-head ordinary hosted checks, native diagnostic execution, and heavy acceptance remain required.
