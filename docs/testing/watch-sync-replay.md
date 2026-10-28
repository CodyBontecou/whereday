# Watch sync snapshot replay

This harness uses a synthetic Watch queue in the same `WatchLocationSync/state.json`
format as the Watch app. It contains no personal history: 503 points in San Juan,
two completed visits, and one completed outing on October 24, 2026.

## Test contract

| Layer / start state | Action | Required outcome / evidence |
| --- | --- | --- |
| Queue + empty in-memory phone store | Send first 500 points; lose ack; restore queue; resend | UUID deduplication: 500 points, 2 visits, 1 session |
| Partially acknowledged queue | Send remaining 3 points; acknowledge | 503 persisted points, empty completed queue |
| Existing phone history | Deliver old payload again | Counts remain unchanged |
| Phone not configured | Import, configure, retry | Failure before readiness; successful import on retry |
| Legacy queue with no visits | Decode, migrate, import | 2 recovered visits |
| Existing outing, points already delivered | Send completion only | Save end time AND emit phone refresh notification |
| Simulator app, isolated replay store | Replay snapshot twice across process restarts | Both reports assert 503 points, 2 visits, 1 session, zero queued points |
| Simulator app with fixture staged (manual UI check) | Launch replay, open Timeline, open recorded outing | Visible completed outing and its 503 recorded points |

The model tests invoke the production queue batching/ack code, payload decoder,
and SwiftData importer. Only delivery scheduling is simulated. They do not prove
Bluetooth connectivity, OS background scheduling, or physical Watch sensor behavior.

## Run

```sh
python3 scripts/replay_watch_snapshot.py --simulator <IOS_SIMULATOR_UDID>
```

This builds and runs the focused XCTest suite, installs the Debug app, and replays
twice. The first replay resets only `Documents/WatchSyncReplay/phone.store*`; the
second retains that store and the normalized snapshot to verify persistence and duplicate delivery.
The normalized snapshot preserves visit IDs created during legacy migration. Normal
application history is untouched. The app stays open in replay mode for inspection.

For an already built Debug app:

```sh
python3 scripts/replay_watch_snapshot.py --simulator <IOS_SIMULATOR_UDID> \
  --skip-tests --app /absolute/path/to/IsoMe.app
```

Use `--snapshot /absolute/path/to/state.json` for another queue, including a later
captured Watch queue. Preserve its default Swift JSON date encoding (seconds since
January 1, 2001). The script checks point/session counts from the input; modern
queues also check visit counts. A queue contains only retained records, so it is
not a full Watch backup. Store personal captures outside the repo.

The debug launch argument `--watch-sync-replay` reads
`Documents/WatchSyncReplay/snapshot.json`, uses the isolated database, and writes
`report.json` and `remaining-state.json` there. The launch hook is compiled only
for Debug simulator builds. Removing the argument returns to the normal database.

## Report and diagnosis

The user supplied a support email for iso.me **1.7.13 (39)** on iOS 27.0.1:
Watch tracking/logs were present, but visited places did not appear on iPhone,
and the reporter requested a way to trigger manual sync. This was an email,
not a GitHub issue. No personal email address or real location history is saved here.

At the 1.7.13 source commit `57fe894`, `recordLocation` appended route points,
but never created `WatchLocationSyncVisit` records. This explains missing places
when route transport itself succeeds; it does not establish whether the reporter
also experienced a WatchConnectivity delivery failure.

The existing local commit `65365fc` adds visit recording, recovery from still-queued
legacy points, and the Watch's **Sync Now** button. The legacy integration test now
recreates the old wire payload (503 phone points, **zero visits**), then imports the
upgraded queue (same 503 points, **two visits**). Already acknowledged points that
are no longer on the Watch cannot be reconstructed by this Watch-side migration.

Run the legacy queue through the visible simulator app with:

```sh
python3 scripts/replay_watch_snapshot.py --simulator <IOS_SIMULATOR_UDID> --legacy-queue
```

Today’s additional fix addresses a separate refresh gap:
`testSessionOnlyCompletionRefreshesPhoneWithoutNewPointsOrVisits` failed before
the fix because `.watchLocationDataImported` was never posted for an existing
session's end-time update. The receiver now includes session mutations in its
refresh condition. Duplicate unchanged sessions still produce no refresh.

## Validation

- All 278 app XCTest cases passed, including 14 Watch-sync cases.
- The Watch simulator target builds successfully.
- The app replay passed twice with 503 points, two visits, one session, and zero queued points.
- The legacy-format app replay also passed twice with the same counts. Its normalized
  snapshot is persisted before delivery, so replaying legacy data preserves recovered visit IDs.
- Manual Timeline/outing inspection showed 503 points and a completed path.
- A recorded Argent UI test was discarded: SwiftUI accessibility labels/IDs were
  present in the accessibility service but absent from the runner's UIKit hierarchy.
  Automated proof comes from XCTest and the report-asserting replay script;
  there is no completed coordinate-based UI regression test.
