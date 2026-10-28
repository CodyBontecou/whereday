§ Docs · Guide 10 of 11 

# Record Apple Watch GPS routes without your iPhone

Start a route on Apple Watch, record without your iPhone nearby, then check the transfer back to your phone. Learn the recording controls, sync status, and recovery checks.

Platform

watchOS 10+

Updated

October 26, 2026

Reading

3 min

Back to

[All docs](https://whereday.com/docs/)

## Record on the Watch, then check the phone copy

Whereday can record GPS points on Apple Watch without the iPhone nearby. The Watch keeps a local queue for transfer to the companion iPhone. Recording and reviewing history are free; writing export files on the phone requires the optional unlock.

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

Completed transfers are acknowledged by the phone; points remain in the Watch queue until acknowledged. This helps retry delivery, but the queue is not a full backup of history already transferred. Do not uninstall the Watch app as a first troubleshooting step.

## If the route is missing or incomplete

- Nothing recorded: check the Watch's location permission, tracking state, and whether the point count advanced during the session. 
- Watch has data, phone looks empty: check the recording date, open both apps, and read the Watch sync status before starting another route. 
- Points arrived but visits differ: Watch visits are derived from recorded movement and are not identical to the iPhone's automatic visit detection. Check the route itself and correct places where needed. 
- Older app version: an update can change sync behavior, but cannot recreate every record that is no longer retained. Keep existing data while diagnosing a gap.  

If a retry does not resolve it, contact support with the app versions, recording date, and exact sync message. You can describe the missing period without sending your private route.

## Daily counters and complications

The Watch shows tracking state, elapsed time, coordinates, distance, points, and a movement-based visit count. Its visit counter is not a count of named places confirmed by the iPhone. Reset today resets the Watch's daily counters; it does not restore missing records or delete the iPhone's imported history.

Circular, rectangular, inline, and corner complications offer glanceable status. A complication is a snapshot, so open the Watch app to inspect the current recording and sync state.

## Keep a portable copy after syncing

Once the route is visible on the phone, follow [the GPX walkthrough](https://whereday.com/blog/record-iphone-route-gpx/) to open it in another route tool, or [back up supported visits and points](https://whereday.com/blog/back-up-iphone-location-history/) for later import. See [Timeline and outings](https://whereday.com/docs/timeline-outings/) for reviewing the transferred day.

If a recorded day looks incomplete, follow the [missing visits and route points checklist](https://whereday.com/blog/missing-location-history-iphone/) before deleting records or retrying an import.

[← PreviousSiri & Shortcuts](https://whereday.com/docs/siri-shortcuts/) [Next →Privacy & your data](https://whereday.com/docs/privacy-data/)

Source: https://whereday.com/docs/apple-watch/
