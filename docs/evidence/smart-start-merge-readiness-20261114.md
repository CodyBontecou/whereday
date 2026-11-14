# Smart Start current-main integration

## Scope and reused work

Based on main `5344145e`. Reused policy/session metadata (`78ec1451`), motion/ambient runtime (`6eb41569`), and admission/expiry/threshold corrections (`18cb7104`). Integrated main's existing shared phone/Watch recording control callbacks rather than replacing them. Actual Swift reconciliation producer resolves source-hash drift; no hashes were hand-pinned.

The initial full integrated iOS suite passed **565 XCTest +20 Swift Testing tests**. Review then identified a previously uncovered peer-start integration seam; that green suite alone was not treated as sufficient evidence.

## Peer-start regression and fix

Two new tests drive the real `LocationManager.applySharedRecordingIntent` path with in-memory SwiftData, isolated defaults, existing injected access/monitoring and queued-expiry seams. Visibility is internal for tests; the production access closure still defaults to `StoreManager.shared.canTrack`. This path may not prompt for permissions.

**Actual RED:** two methods executed, seven assertion failures. While recording, an accepted successor updated the in-memory start/session row but public start's idempotent early return left the previous persisted timestamp, `.shortcut`/`.smartMotion` source, and old scheduling count. The automatic-source residue allowed stationary policy to stop an explicitly requested peer session.

**Fix:** accepted peer starts explicitly take manual ownership and reattach through the existing private start method with permission prompts disabled. It persists the peer session's metadata and reschedules its expiry lease even if tracking is already enabled. Public repeated starts remain idempotent. No transport, Lamport ordering, permission or entitlement rule is weakened.

**GREEN:** all **17** SmartStartCorrectionTests pass, including both reproduced failures and a new access/permission rejection test. The tests also retain the predecessor queued-expiry and single-open-session assertions.

## Final verification

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A'
```

Final full signed suite: **568 XCTest cases, zero failures; 20 Swift Testing tests passed**.

Result: `artifacts/test-results/IsoMe-20261113-230035-73995.xcresult`. Local logs under `artifacts/merge-readiness/`: `smart-peer-red.log`, `smart-peer-green.log`, `smart-final-ios.log`.

All **30** CI source-reference checks and `git diff --check` pass. Actual reconciliation regeneration preserves all **17** expected case outputs from main. No temporary production logging or sensor instrumentation was added.

## Shared boot follow-up

`AppTrackingServices` now owns the graph before any view task: AppDelegate boots it on foreground/location/background launches, and ContentView and Start intents reuse its manager. It runs LaunchGuard before manager construction, attaches the real SwiftData context before webhook/export services, and refuses to initialize against the recovery stand-in. Start intents surface a store-unavailable error. A crash-loop pause suppresses the automatic runtime for this process without resetting the user's opt-in.

Four new real-manager/in-memory-store tests verify one instance/attachment across background→intent→foreground entry, ability to persist before any view model, failed-store refusal including the actual intent helper, and launch-guard ordering/automatic-runtime suppression. The initial authored-test compile attempt also had a missing test-container helper; that authoring error was corrected, not hidden as a sole intended RED. Targeted four-test GREEN and final full suite pass **572 XCTest +20 Swift Testing tests**, result `artifacts/test-results/IsoMe-20261113-233438-5374.xcresult`, log `tracking-boot-final.log`. All 30 source-reference checks and diff checks pass.

This proves the synchronous boot/intent seam, not actual OS relaunch, permission prompts, wake delivery, or physical background persistence. Watch receiver configuration remains unchanged in this branch.

## #60 requirement boundary

Implemented and tested at source/simulator level: off-by-default persisted settings, session/source/cooldowns, pure admission policy, motion adapter, ambient/active split, coordinator lifecycle, no auto permission prompt, movement thresholds, stationary stop ownership, permissions/access guards, stale fix admission and expired timer leases. Existing simple Start intent carries shortcut source.

Follow-up: the real opt-in Settings page now includes confirmation, permission status/help, activity/timing controls and Start/Stop automation recipes. Eight new intent-model tests plus actual isolated simulator navigation/cancel/enable/disable/picker/relaunch checkpoints are documented in [the Settings receipt](smart-start-settings-20261114.md). Full signed suite is now **580 XCTest +20 Swift Testing**, zero failures. Its video/screenshot evidence is scoped and does not imply full motion or physical acceptance.

Still incomplete: physical verification of the shared boot/AppDelegate wake path, optional onboarding and final localized permission/limitation presentation, expanded Shortcuts intent options and actual automation acceptance, complete motion/accessibility verification, diagnostics/energy tuning, real-device locked/background/force-quit/reboot/battery and denied-state matrix. Main's shared recording control path must retain its own paired acceptance boundaries. No fresh device motion, energy, physical Watch or UI acceptance is claimed. Hosted checks on the pushed head remain required. This is not whole-epic completion.
