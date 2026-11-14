# Location recovery: destroyed Activity owner, actual native RED → GREEN

## Hosted failure boundary

Original PR114 run43575549275 failed combined-mode reboot recovery with `RecordingNeedsForeground`. Diagnostic branch run **43580578663** (head2a5fbd6f, artifact14108697453) preserved the **same nine-case order, assertions, API35, service deadlines and25-minute job limit**, adding only bounded observation. Eight prefix cases completed their actual recovered/new-PID/preserved-point proofs; the final combined reboot case failed again.

`location-owner-20261114/hosted-destroyed-owner.log` contains all six retained observations, zero drops. Before the visit command, owner265978135 is RESUMED/focused/attached/not destroyed. After awaiting its acknowledgment (~3.129seconds), that **same object is DESTROYED, detached and unfocused** (not finishing). The following route command receives it and the existing foreground guard correctly rejects it. No native-focus callback was delivered for that destruction; none was dropped. Worker-thread scalar reads are labeled, not presented as authoritative Main snapshots. Destruction of that old instance is established; its exact configuration trigger/replacement identity was not observed and is not invented here.

The fixture cached MainActivity once and reused it across two independent suspending actions. Production `RecordingCommands`/`VisitMonitoringCommands` must continue rejecting destroyed/unfocused owners; no guard, permission or entitlement change is justified.

Two earlier local traced combined reboot controls passed, including expected missing-location rejection/regrant. Those were not reproduced failures or fixes. One early probe imposed per-boundary Main synchronization; it was superseded by thread-labeled observation without those barriers. Diagnostic code/workflow input remains on its separate branch, not in this PR.

## Deterministic regression at the real native seam

A new method in the already-selected dedicated `RoadActivityLifecycleTest` class starts actual visit monitoring, calls **ActivityScenario.recreate()**, proves the original owner is destroyed and the current owner differs, then requests actual route recording. It uses isolated mapQa state, actual framework scenario ownership, synthetic data, real service acknowledgments and explicit runtime permissions; no fake focus flag or mocked Activity substitutes for recreation.

**Actual RED:** counterfactual starter resolves/caches one focused owner before both commands, reproducing the original pattern. One native test fails with `RecordingNeedsForeground`, same guard as hosted recovery. The first authored build had a wrongly qualified enum; that compile error was corrected before this actual native RED and is not counted as the intended failure.

**Correction:** test-only `SelectedLocationServiceStarter` resolves the current RESUMED/focused/nonfinishing/nondestroyed Activity through ActivityScenario **before each independent command**. It does not retry a rejected service command or alter production admission. Both owner waits share the original single20s/60s focus allowance; time consumed by each owner wait is subtracted from that shared budget. Original15s service receipts and subsequent runtime waits remain unchanged. Focus lookup timeout retains its diagnostic; service receipt timeouts are not mislabeled as focus errors.

`LocationProcessRecoveryTest` now passes its scenario rather than a once-captured Activity. Every original proof/assertion, mode/termination selection and denied-location/regrant branch remains. New regression resides in the dedicated system-permission class which the heavy workflow already runs on its own fresh mapQa installation, outside denied-permission fixtures.

**GREEN:** both native RoadActivityLifecycleTest cases pass, zero failures/errors/skips, including original road-session lifecycle ownership and forced-recreation service regression. The canonical actual-process driver also passes **both/reboot** with seed/new process/changed boot proof, preserved row and successful recovered phase, without any observer in the APK. These local results do **not** replace the full hosted nine-case selection.

Actual strict-verifier/session receipts and instrumentation digests: `location-owner-20261114/red-green-verification.json`. Local raw artifacts: `artifacts/merge-readiness/{location-owner-red-1,location-owner-green-1,location-owner-both-reboot-1}` plus build logs. Owned read-only API35 guest,2cores/2GiB/private ADB5064/emulator5586; settings restored/read back, scoped packages removed, emulator/private ADB stopped and lock released. No physical/default device used.

## Completed baseline audit — additional blockers, not whole-job acceptance

Downloaded run **43575545218**, head763d6346, artifact **14108333739**. The initial default selection now completes **594 announced cases:592 passed, zero failures/errors, exactly the two original Calendar ignores**. Native instrumentation ends `OK (592 tests)`; the previously failing covered-road method explicitly has start1/end0. This verifies the road-focus correction in its original full context, not merely a local subset.

Later expanded-workspace selection passes32 cases; short-workspace keyboard passes2. The next **WorkspaceActivityResizeTest** selection runs all23 cases and has **eight real failures** (missing navigation/scroll semantics, camera restoration mismatches, measure-during-layout exception, stale-action timeout). Original assertions and timeouts are retained. `hosted-baseline-audit.json` records exact cases/messages and counts. Remaining database/downstream selections after that step did not execute.

Keep PR114 draft. Verify the new-head full nine-case recovery selection; diagnose the eight expanded recreation failures without weakening camera values, semantics or deadlines; then complete downstream gates. No duplicate million-record run is needed for these app-test-only changes: platform import inputs are unchanged from the passing6a6b7fe0 component.
