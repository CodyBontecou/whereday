# Apple shared recording control

## Implemented behavior

- A successful local start on iPhone or Apple Watch publishes a shared session UUID, start time, and recording intent. The other device adopts that UUID when it can start recording.
- A stop on either device (including phone auto-stop/access expiry and Watch access expiry) publishes a stop intent. Remote application does not echo a new user action.
- Watch points still use the existing durable Watch-to-phone queue and import into the shared recording session. Both devices may collect points; this does not select a single GPS source or deduplicate similar coordinates from different sensors.
- Phone-to-Watch remains today's display snapshot plus recording controls, not a full historical route/point mirror. Starting the phone does not copy its already-collected route points onto the Watch.
- Pending intent, applied intent, acknowledgement, and a device origin UUID persist locally. Local prerequisite failures remain pending; permission/access changes retry application.
- Existing Watch and phone sync surfaces show pending/blocked control status separately from route delivery. An acknowledgement means the peer applied the control, not that all route batches arrived.

## Conflict policy

Each local action increments the largest observed Lamport revision. Higher revisions win without comparing device wall clocks. At an equal revision, stop wins over start; otherwise the origin UUID breaks ties. Offline concurrent starts converge on one session identity; points already collected under the losing session retain that identity/history rather than being relabeled or discarded.

The command uses versioned dictionary keys, durable `transferUserInfo`, and opportunistic `sendMessage`. Duplicate commands do not reapply successful side effects. Old acknowledgements do not clear newer commands. Existing session delegates retain ownership of `WCSession`.

Neither connectivity nor background execution is instantaneous or guaranteed. Both devices require their own recording access and location permission. Remote commands do not trigger permission prompts or bypass purchases.

## Automated verification

Run through the repository's signed simulator runner:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=YOUR_ISOLATED_SIMULATOR' \
  -only-testing:IsoMeTests/WatchLocationSyncTests \
  -only-testing:IsoMeTests/LocationManagerTrackingTests
```

On November 12, 2026, 24 tests passed using an isolated iOS 27.0 simulator (the installed SDK is 27.2, whose matching simulator runtime is unavailable). The run also compiled the bundled Watch targets. Result bundle:

`artifacts/test-results/IsoMe-20261112-151108-25915.xcresult`

The new control tests disable WatchConnectivity and use synthetic sessions with isolated defaults. They verify conflict ordering, durable pending state, successful-application acknowledgements, duplicate delivery, and stale acknowledgements. They do not verify physical wireless transport or device tracking start/stop through the complete UI.

## Required hardware acceptance

On a paired iPhone and Watch with recording access and location permission:

1. Start on phone; verify both record, share a session ID, and show acknowledged control status.
2. Stop on Watch; verify both stop and Watch batches finish importing into the shared phone outing.
3. Repeat starting on Watch and stopping on phone, including suspended/background apps.
4. Disconnect, start/stop offline, reconnect; verify revision policy and pending status, including concurrent starts and a concurrent start/stop.
5. Relaunch each app while a command is pending; verify convergence without duplicate sessions or losing queued Watch points.
6. Deny/re-enable location permission and expire/restore access; verify blocked status and retry behavior.
7. Exercise phone auto-stop, especially delayed delivery of a start older than the configured auto-stop window.
8. Check both status surfaces at large text sizes and in light/dark appearance. No new visual simulator acceptance is claimed here.
