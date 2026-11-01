§ Docs · Guide 10 of 11

# Record Apple Watch GPS routes without your iPhone

Start a route on Apple Watch, record without your iPhone nearby, then check the transfer back to your phone. Learn the recording controls, sync status, and recovery checks.

Platform

watchOS 10+

Updated

October 30, 2026

Reading

6 min

Back to

[All docs](https://whereday.com/docs/)

## Record on the Watch, then check the phone copy

Whereday can record GPS points on Apple Watch without the iPhone nearby. The Watch keeps a local queue for transfer to the companion iPhone. From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement.

Install the Watch app and allow its location request. The phone app is needed to review transferred records in its journal and use its export workflow. Controls can vary by version; if a control described below is missing, check the available app update.

## Before your first route

- Open Whereday on the Watch. If it asks for location access, respond to the permission prompt. If access was denied, review the Watch's location settings.
- Tap Start and confirm the status changes from standby to tracking. After an initial permission request, check the state rather than assuming a recording began.
- Try a short route first. Look for the elapsed time and point count to change before relying on a longer session.
- When finished, tap Stop on the Watch. Confirm it has left the tracking state.

Recording away from the phone is separate from syncing to it. You do not need an active phone connection for the Watch to collect points, but the phone must be available later for transfer. GPS conditions, permission, battery, and watchOS behavior can affect the recording.

## Sync and verify the result

- Bring the paired Watch and iPhone together and open Whereday on the iPhone.
- Open Whereday on the Watch. If available in your version, tap Sync Now and read the status.
- In the phone app, open Timeline for the day of the recording. Check the outing and use its map to inspect the expected points and times.
- Check both route points and visits. A transferred route is not proof that every stop was detected or named correctly.

Read the sync state before retrying
| Watch status | What to do next
| Connecting to iPhone | Keep the paired devices available and open the phone app.
| Sync queued | The request is queued. Check the phone result; this is not confirmation that every record is imported.
| Synced with iPhone | The phone acknowledged the delivered data. Still check the intended day and route.
| Nothing to sync | Check the phone's existing history and the selected day. An empty pending queue is not a full history report.

Apple notes that [background transfers may not arrive immediately](https://developer.apple.com/documentation/watchconnectivity/wcsession). Check the saved phone records as well as the transfer status.

Completed transfers are acknowledged by the phone; points remain in the Watch queue until acknowledged. This helps retry delivery, but the queue is not a full backup of history already transferred. Do not uninstall the Watch app as a first troubleshooting step.

## Checked queue example: 503 points, two batches

We replayed an invented Watch queue through the queue logic and iPhone receiver included in Whereday 1.7.16 (46). The example contains 503 points, two completed visits, and one completed outing. Queue files and a separate phone database were saved to disk and reopened between deliveries.

Queued points and saved phone points are different counts
| Stage | Points still queued | Points saved on phone
| Before delivery | 503 | 0
| First 500 saved, acknowledgement withheld | 503 | 500
| Same batch retried and acknowledged | 3 | 500
| Final three saved and acknowledged | 0 | 503
| Queue and phone store reopened | 0 | 503

The retry added zero points because that batch’s record IDs were already saved. The completed outing stayed queued until its final point batch was acknowledged. At the end, the phone retained all 503 point IDs and timestamps, two visits, and one outing. A delayed delivery of the first batch still left the phone at 503 points.

This explains why a pending queue can contain points already saved on the phone: the Watch may still be waiting for an acknowledgement. Conversely, an empty queue says nothing about whether you selected the right date or whether a route was recorded before transfer.

What was checked: released queue batching, acknowledgements, receiver imports, and reopening temporary disk stores in an iOS 26.5 simulator. All 15 selected native tests passed on October 26, 2026. Delivery was supplied by the test with WatchConnectivity disabled. No physical Watch recording, Bluetooth or Wi-Fi delivery, background transfer, app-process restart, or Watch sync screen was newly verified.

## Export the phone copy as GPX

Once the recording is saved on the iPhone, follow the [Apple Watch to GPX walkthrough](https://whereday.com/blog/record-iphone-route-gpx/#watch-to-gpx). It covers selecting points or an outing, checking the saved file, and comparing a partial 500-point transfer with the complete 503-point synthetic example. A GPX that opens successfully can still be missing the last batch.

## If the route is missing or incomplete

- Nothing recorded: check the Watch's location permission, tracking state, and whether the point count advanced during the session.
- Watch has data, phone looks empty: check the recording date, open both apps, and read the Watch sync status before starting another route.
- Points arrived but visits differ: Watch visits are derived from recorded movement and are not identical to the iPhone's automatic visit detection. Check the route itself and correct places where needed.
- Older app version: an update can change sync behavior, but cannot recreate every record that is no longer retained. Keep existing data while diagnosing a gap.

If a retry does not resolve it, contact support with the app versions, recording date, and exact sync message. You can describe the missing period without sending your private route.

## Daily counters and complications

The Watch shows tracking state, elapsed time, coordinates, distance, points, and a movement-based visit count. Its visit counter is not a count of named places confirmed by the iPhone. Reset today resets the Watch's daily counters; it does not restore missing records or delete the iPhone's imported history.

Circular, rectangular, inline, and corner complications offer glanceable status. A complication is a snapshot, so open the Watch app to inspect the current recording and sync state.

## Check the phone copy before removing anything

- Open the recording’s actual date in Timeline, not only Today. Check the outing’s start and end time.
- Inspect the outing’s point count and map. A saved session title or timer alone does not prove that GPS points were collected.
- Check the expected visits separately from the path. Watch movement-based visits can differ from iPhone visit detection.
- Keep the Watch app and its pending data while resolving a transfer problem. Once the phone copy is checked, use supported exports if you need a separate archive.

## Common questions

### Does Sync queued mean my route is missing from the phone?

Not necessarily. In the checked example, 500 points were already saved while all 503 still remained queued because the acknowledgement had not been applied. Inspect the phone’s intended date before deciding what is missing.

### Will retrying add the same points twice?

The checked replay of the same Watch batch added zero duplicate points, including after the phone store was reopened. This depends on those same record IDs. A repeated file import is a separate workflow. The [historical backup import check in 1.7.16](https://whereday.com/blog/back-up-iphone-location-history/#checked-import) added repeated points. Version 1.8 adds duplicate preview and skipping for supported imports; see the [current import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/).

### Does Nothing to sync prove I have a complete backup?

No. It describes pending transfer data. It does not recreate unrecorded movement, establish the completeness of older phone history, or provide a separate archive. Verify the phone copy and retain supported exports for records you need to keep.

## Keep a portable copy after syncing

Once the route is visible on the phone, follow [the GPX walkthrough](https://whereday.com/blog/record-iphone-route-gpx/) to open it in another route tool, or [back up supported visits and points](https://whereday.com/blog/back-up-iphone-location-history/) for later import. See [Timeline and outings](https://whereday.com/docs/timeline-outings/) for reviewing the transferred day.

If a recorded day looks incomplete, follow the [missing visits and route points checklist](https://whereday.com/blog/missing-location-history-iphone/) before deleting records or retrying an import.

[← PreviousSiri & Shortcuts for iPhone location tracking](https://whereday.com/docs/siri-shortcuts/) [Next →Privacy & your data](https://whereday.com/docs/privacy-data/)

Source: https://whereday.com/docs/apple-watch/
