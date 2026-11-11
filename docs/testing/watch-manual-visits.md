# Watch manual visits (#75)

Status: implementation prepared for review. Keep #75 open until the physical paired Watch/iPhone acceptance checklist below is completed.

## Current-main integration — November 11, 2026

Integrated main `f003fe3e` with the original PR #84 head `f0623fc9`.
The integration retains the shared `AppModelStore`, startup recovery guard,
paid-tracking migration, current Watch route/glance dashboard, Mark transport,
recorded-day count and route distance. The receipt model is registered in the
shared app schema rather than introducing a second intent-side container.
Today context and manual-visit snapshots preserve each other's application-context
keys and are both consumed on activation and subsequent delivery.

`WatchManualLocationTests` first reproduced the original first-grant failure.
The one-shot request now waits for authorization, requests a fix once, and
releases its delegate and request on completion or cancellation. Its three tests
use injected permission callbacks; they do not establish physical Watch behavior.

Verification via `scripts/test-ios.sh` on the Whereday Reimagined Tests iOS 27.0
simulator:

- Targeted run: **6 XCTest route-import tests + 20 Swift Testing cases passed**.
- Complete run: **482 XCTest + 40 Swift Testing cases passed**. This builds the
  phone, Watch and both widget targets.
- The complete run required canceling a simulator Apple Account sign-in dialog
  during the StoreKit fault test and denying a subsequent notification prompt.
  No account credentials were entered. This is a passing run with recorded UI
  intervention, not an unattended CI proof.
- Full result bundle: `/private/tmp/whereday-watch-fix-20261111/artifacts/test-results/IsoMe-20261111-105252-64682.xcresult`.

Reproduce the complete suite with:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=YOUR_SIMULATOR_UDID'
```

PR #84 remains draft and issue #75 remains open pending the paired hardware
checklist below. No physical WatchConnectivity acceptance is claimed.

The Watch dashboard's **Check In / Out** button opens Manual Visits. The phone supplies saved places and open visits with their source and confirmation status. Saved places work without Watch location permission; Current location requests a fresh one-shot location without starting route recording. Check Out always targets the selected manual visit. Automatic and imported visits cannot be checked out through these controls.

Check In rejects overlaps unless the user enables Allow Overlap. A failed command stays failed; changing the choice creates a new command rather than silently changing a retry. Multiple open manual visits require a selection. Missing/deleted places, missing or inaccurate locations, invalid times and missing/closed visits produce visible errors.

## Delivery and persistence

- `WatchVisitCommandQueue` is saved atomically in the Watch app group before any transmission. An unreadable queue is preserved and blocks new commands; a write failure sends nothing new.
- Commands retain UUID, device ID, original action time, destination and overlap choice. Location must be within 30 seconds of the action with accuracy at most 100 metres. Validation compares the sample to the action time, so an offline replay does not turn a previously fresh sample into a stale one.
- Only the first pending command is transmitted. A queued Check Out targets the Check In command's deterministic visit UUID and depends on that Check In. A failed Check In fails its dependent Check Out. The queue permits at most 100 pending commands and keeps recent terminal results for display.
- `transferUserInfo` provides background delivery and reachable peers additionally use `sendMessageData`. Reconnection, activation, foreground entry and Refresh / Retry Pending retry the queue. A transport callback never removes a command.
- The phone reads durable receipts in a fresh SwiftData context for **each delivery**. The shared `ManualVisitStore` inserts a receipt in the same save as the visit mutation. Save failures roll back and return retryable; no terminal success is recorded. A receipt stores a SHA-256 command fingerprint, not another copy of the command's name/location. Reusing an ID with altered content fails.
- Success and terminal validation failures are acknowledged only after their receipts save. Matching retries return the original result, even after a restart. Checkout retries preserve the original departure and correction/detection metadata.
- Replies also carry a fresh snapshot. Status uses `updateApplicationContext` (latest state wins), bounded to 20 open visits, prioritizing manual visits, and 50 saved places. Names are bounded to 80 characters. A count warns when additional manual visits remain on iPhone. Older snapshots cannot overwrite newer status.
- Shared status is optional, so older `SharedLocationData` payloads still decode. Existing recording/sync payloads continue through their original receiver. The phone configures its receiver at app initialization to handle background launches.

## Automated verification

`WatchManualVisitTests` covers persisted/restarted replay, changed-payload reuse, repeated checkout, correction metadata, overlap override, offline timestamps, missing/stale/inaccurate/invalid location, future time, version mismatch, saved/deleted places, no-open/ambiguous/nonmanual targets, out-of-order dependency, queue persistence/FIFO/duplicate acknowledgments, failed dependencies, retryable results, device isolation, legacy shared payloads, snapshot ordering, receiver dispatch, read-only save failures and additive store migration.

The read-only-store regression is intentional: SwiftData can retain registered objects from a failed save even after rollback. Reusing that identity map for receipt lookup can incorrectly acknowledge an unsaved command. A fresh context per delivery is required.

Run the app's IsoMe scheme tests on an iPhone simulator. The scheme also builds the Watch app and both widget targets.

## Paired hardware acceptance still required

The Watch dashboard entry point was rendered on an isolated Series 11 (46 mm), watchOS 26.5 simulator. Automated tests exercise command replay against real SwiftData storage. Simulator checks do not establish physical WatchConnectivity background delivery: Apple documents `transferUserInfo` as unsupported in Simulator.

On a physical paired Watch and iPhone, verify:

1. Reachable Check In at a saved place and Check Out become Completed only after phone persistence; inspect the phone journal.
2. Disconnect, queue Check In and dependent Check Out, relaunch Watch, reconnect, and confirm exactly one closed visit with the original action times.
3. Retry repeatedly, background/terminate the phone, reopen both apps, and verify one receipt/mutation per command.
4. Exercise location denied, unavailable/inaccurate location, overlap rejection and explicit override, multiple manual visits, and deleted/closed checkout targets.
5. Verify existing Watch route recording and subsequent sync still work, and automatic/imported visits retain their metadata.

Do not mark paired hardware acceptance complete solely from simulator replay.

## Integration verification — October 26, 2026

Merged current main into `codex/watch-manual-visits` without conflicts. The full signed iOS 26.5 simulator suite completed successfully: **358 passed, 0 failed, 0 skipped**. This builds the phone, Watch and widget targets and includes the Watch command processor, queue, legacy payload, migration and existing recording/sync regression coverage. A Watch authorization callback now captures the authorization value before dispatching to the main queue rather than sending the non-Sendable location manager into that closure.

Result bundle: `~/Library/Developer/XcodeBuildMCP/workspaces/iso.me-b28a86da2136/result-bundles/test_sim_2026-10-26T20-18-39-106Z_pid32373_4f4b91ea.xcresult`.

Reproduce with:

```sh
xcodebuild test -project IsoMe.xcodeproj -scheme IsoMe \
  -destination 'platform=iOS Simulator,id=BF9F9B4E-43B7-4888-BC70-31CA40ADA914' \
  -derivedDataPath /private/tmp/isome-watch75-derived \
  -parallel-testing-enabled NO
```

No new paired hardware test was performed in this integration run. The hardware checklist remains open; simulator success does not complete issue #75.
